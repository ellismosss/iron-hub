package com.ironhub.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;

/**
 * Single source of truth for account progression state (DESIGN.md §2.2).
 *
 * Ingests client events and exposes a normalized, module-friendly view of
 * skills, quest states, item ownership and unlock flags. Modules read this
 * service rather than the client directly, which keeps them unit-testable.
 *
 * Everything is profile-scoped by account hash; the slice that cannot be
 * re-read after a restart (bank snapshot, unlocks, kill counts) persists
 * via {@link ProfileStore} and survives relog and client restart.
 *
 * Ingestion runs on the client thread. Reads may come from the EDT, so
 * incremental maps are concurrent and container snapshots are swapped
 * wholesale as immutable maps.
 */
@Slf4j
@Singleton
public class AccountState implements StateView
{
	/** Quest states refresh at most once per this many ticks (~6 s) when dirty. */
	private static final int QUEST_REFRESH_TICKS = 10;

	private final Client client;
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private final ProfileStore store;

	private final Map<Skill, Integer> realLevels = new ConcurrentHashMap<>();
	private final Map<Skill, Integer> xp = new ConcurrentHashMap<>();
	private final Map<Quest, QuestState> questStates = new ConcurrentHashMap<>();
	private final Set<String> unlocks = ConcurrentHashMap.newKeySet();
	private final Map<String, Integer> killCounts = new ConcurrentHashMap<>();

	// display names for items seen in the bank — persisted so bank search
	// works after a restart, before the bank is reopened
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();

	// manual daily ticks: daily id -> epoch millis when marked done
	private final Map<String, Long> dailiesDoneAt = new ConcurrentHashMap<>();

	// aggregated loot: npc name -> item id -> total quantity (persisted)
	private final Map<String, Map<Integer, Integer>> lootBySource = new ConcurrentHashMap<>();

	// supplies consumed per source (canonical item ids, persisted); the
	// checkpoint is carried gear at the last bank interaction or kill
	private final Map<String, Map<Integer, Integer>> suppliesBySource = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Integer>> savedLoadouts = new ConcurrentHashMap<>();
	private final Map<String, PersistedState.SavedSetup> savedSetups = new ConcurrentHashMap<>();

	// completed farm run durations, persisted (avg/best/count stats)
	private final java.util.List<Long> herbRunsMs = new CopyOnWriteArrayList<>();
	// custom farm runs: name -> ordered farm-runs.json location ids
	private final Map<String, PersistedState.FarmRun> farmRuns = new ConcurrentHashMap<>();

	/** Rolling consumption events for runway rates, capped. */
	public static final int MAX_CONSUMPTION_EVENTS = 500;
	private final java.util.List<PersistedState.ConsumptionEvent> consumptionLog = new CopyOnWriteArrayList<>();

	/** Periodic account-score snapshots for trend sparklines, capped. */
	public static final int MAX_SCORE_SNAPSHOTS = 60;
	private static final long SNAPSHOT_MIN_GAP_MS = 20 * 3_600_000L; // ~daily
	private final java.util.List<PersistedState.ScoreSnapshot> scoreSnapshots = new CopyOnWriteArrayList<>();

	// collection log progress from the last log open (persisted)
	private volatile int collectionLogSlots;
	private volatile int collectionLogTotal;
	private volatile long collectionLogSeenMs;

	// goal planner selections (persisted)
	private final Set<String> selectedGoals = ConcurrentHashMap.newKeySet();
	private volatile String activeGoal = "";
	private final Map<String, PersistedState.CaGoal> caGoals = new ConcurrentHashMap<>();
	private final Map<String, PersistedState.DiaryGoal> diaryGoals = new ConcurrentHashMap<>();
	// collection log (persisted): obtained slots, ranking skips, sync baseline
	private final Set<Integer> clogObtained = ConcurrentHashMap.newKeySet();
	private final Set<Integer> clogSkipped = ConcurrentHashMap.newKeySet();
	private volatile int clogBaseline = -1;
	private volatile long clogSyncedMs;
	private final Map<String, PersistedState.ClogGoal> clogGoals = new ConcurrentHashMap<>();
	private final Set<String> plannerPins = ConcurrentHashMap.newKeySet();
	private final Set<String> plannerSnoozes = ConcurrentHashMap.newKeySet();
	private final Set<String> plannerBans = ConcurrentHashMap.newKeySet();
	private final Map<String, String> plannerPreferred = new ConcurrentHashMap<>();
	private volatile double lastPlanHours;
	private volatile boolean plannerRouteChapters;
	private final Map<String, PersistedState.CustomGoal> customGoals = new ConcurrentHashMap<>();

	/** Recent deaths, oldest first, capped. */
	public static final int MAX_DEATHS = 10;
	private final java.util.List<PersistedState.DeathRecord> deaths = new CopyOnWriteArrayList<>();
	private volatile Map<Integer, Integer> supplyCheckpoint = Map.of();

	// varbits/varps modules registered interest in (diary tiers, CA points,
	// slayer task, …)
	private final Set<Integer> watchedVarbits = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> varbitValues = new ConcurrentHashMap<>();
	private final Set<Integer> watchedVarps = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> varpValues = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

	/** Quest points from the last quest refresh. */
	@Getter
	private volatile int questPoints;

	private volatile Map<Integer, Integer> bank = Map.of();
	private volatile Map<Integer, Integer> inventory = Map.of();
	private volatile Map<Integer, Integer> equipment = Map.of();
	private volatile int[] inventorySlots = new int[0]; // container order, -1 = empty
	private volatile int[] equipmentSlots = new int[0]; // EquipmentInventorySlot index order
	private volatile boolean inventoryDirty;
	private volatile String slayerTask = "";
	private volatile String combatNpcName = "";
	private volatile int combatNpcId = -1;

	/** Epoch millis of the last bank snapshot; 0 = never seen a bank. */
	@Getter
	private volatile long bankTimestamp;

	private volatile long profile = -1;

	// client thread only
	private boolean questsDirty;
	private volatile boolean varbitsRefreshNeeded;
	private int tick;
	private int lastQuestRefreshTick;
	private boolean containersSeeded;

	@Inject
	public AccountState(Client client, net.runelite.client.game.ItemManager itemManager, ProfileStore store)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.store = store;
	}

	// ── reads (any thread) ────────────────────────────────────────────

	public int getRealLevel(Skill skill)
	{
		return realLevels.getOrDefault(skill, 1);
	}

	public int getXp(Skill skill)
	{
		return xp.getOrDefault(skill, 0);
	}

	public QuestState getQuestState(Quest quest)
	{
		return questStates.getOrDefault(quest, QuestState.NOT_STARTED);
	}

	/** Total owned across bank, inventory and equipment. */
	public int ownedCount(int itemId)
	{
		return bank.getOrDefault(itemId, 0)
			+ inventory.getOrDefault(itemId, 0)
			+ equipment.getOrDefault(itemId, 0);
	}

	/** Bank contents from the last bank visit (item id → quantity). */
	public Map<Integer, Integer> getBankSnapshot()
	{
		return bank;
	}

	/** Current inventory contents (item id → quantity). */
	public Map<Integer, Integer> getInventorySnapshot()
	{
		return inventory;
	}

	/** Inventory in container order (28 entries, -1 = empty); empty pre-login. */
	public int[] getInventorySlots()
	{
		return inventorySlots.clone();
	}

	/** Worn equipment by EquipmentInventorySlot index (-1 = empty). */
	public int[] getEquipmentSlots()
	{
		return equipmentSlots.clone();
	}

	/** Current slayer task creature name, or empty. */
	public String getSlayerTask()
	{
		return slayerTask;
	}

	public void setSlayerTask(String name)
	{
		String value = name == null ? "" : name;
		if (!value.equals(slayerTask))
		{
			slayerTask = value;
			notifyListeners();
		}
	}

	/** Most recently fought NPC (name, id), or empty/-1. */
	public String getCombatNpcName()
	{
		return combatNpcName;
	}

	public int getCombatNpcId()
	{
		return combatNpcId;
	}

	public void setCombatTarget(String name, int npcId)
	{
		if (name != null && !name.equals(combatNpcName))
		{
			combatNpcName = name;
			combatNpcId = npcId;
			notifyListeners();
		}
	}

	/** Saved loadout for an activity (slot name → item id), or null. */
	public Map<String, Integer> savedLoadout(String activity)
	{
		Map<String, Integer> saved = savedLoadouts.get(activity);
		return saved == null ? null : Map.copyOf(saved);
	}

	public void saveLoadout(String activity, Map<String, Integer> slotToItem)
	{
		savedLoadouts.put(activity, new ConcurrentHashMap<>(slotToItem));
		persist();
		notifyListeners();
	}

	/** Full remembered setup (gear + inventory + rune pouch), or null. */
	public PersistedState.SavedSetup savedSetup(String activity)
	{
		return savedSetups.get(activity);
	}

	/** Names of all remembered setups. */
	public java.util.List<String> savedSetupNames()
	{
		java.util.List<String> names = new java.util.ArrayList<>(savedSetups.keySet());
		java.util.Collections.sort(names);
		return names;
	}

	public void saveSetup(String activity, PersistedState.SavedSetup setup)
	{
		savedSetups.put(activity, setup);
		persist();
		notifyListeners();
	}

	/** Display name of an item seen in the bank, or "item <id>" if unknown. */
	public String itemName(int itemId)
	{
		return itemNames.getOrDefault(itemId, "item " + itemId);
	}

	public boolean isUnlocked(String key)
	{
		return unlocks.contains(key);
	}

	public int getKillCount(String source)
	{
		return killCounts.getOrDefault(source, 0);
	}

	/** Last seen value of a watched varbit (0 until first refresh). */
	public int getVarbit(int varbitId)
	{
		return varbitValues.getOrDefault(varbitId, 0);
	}

	/**
	 * Register varbits to track. Values populate from the client on the
	 * next game tick and update from VarbitChanged events afterwards.
	 */
	public void watchVarbits(int... varbitIds)
	{
		for (int id : varbitIds)
		{
			watchedVarbits.add(id);
		}
		varbitsRefreshNeeded = true;
	}

	/** Last seen value of a watched varp (0 until first refresh). */
	public int getVarp(int varpId)
	{
		return varpValues.getOrDefault(varpId, 0);
	}

	/** Register varps to track — same lifecycle as watchVarbits. */
	public void watchVarps(int... varpIds)
	{
		for (int id : varpIds)
		{
			watchedVarps.add(id);
		}
		varbitsRefreshNeeded = true;
	}

	/** Listeners fire on the client thread after meaningful state changes. */
	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	private void notifyListeners()
	{
		listeners.forEach(Runnable::run);
	}

	// ── writes from modules (chat parsing, manual ticks) ──────────────

	public void setUnlocked(String key, boolean unlocked)
	{
		if (unlocked ? unlocks.add(key) : unlocks.remove(key))
		{
			persist();
			notifyListeners(); // manual ticks/marks re-render like any state change
		}
	}

	public void setKillCount(String source, int count)
	{
		killCounts.put(source, count);
		persist();
	}

	/** One kill observed (loot event); feeds kc: requirements live. */
	public void incrementKillCount(String source)
	{
		killCounts.merge(source, 1, Integer::sum);
		persist();
		notifyListeners();
	}

	/** Aggregate a loot drop into the per-source totals (client thread). */
	public void ingestLoot(String source, Map<Integer, Integer> items)
	{
		attributeConsumption(source);
		Map<Integer, Integer> totals =
			lootBySource.computeIfAbsent(source, s -> new ConcurrentHashMap<>());
		items.forEach((id, qty) -> totals.merge(id, qty, Integer::sum));
		if (itemManager != null) // resolve names so the loot tab reads offline
		{
			for (int id : items.keySet())
			{
				itemNames.computeIfAbsent(id, i -> itemManager.getItemComposition(i).getName());
			}
		}
		persist();
		notifyListeners();
	}

	/** Sources with recorded loot, for the loot tab's selector. */
	public java.util.Set<String> lootSources()
	{
		return java.util.Collections.unmodifiableSet(lootBySource.keySet());
	}

	/** Supplies consumed while killing a source (canonical id -> qty). */
	public Map<Integer, Integer> suppliesFor(String source)
	{
		return suppliesBySource.getOrDefault(source, Map.of());
	}

	/** Completed herb run durations in millis, oldest first. */
	public java.util.List<Long> getHerbRunsMs()
	{
		return java.util.Collections.unmodifiableList(herbRunsMs);
	}

	public void recordHerbRun(long durationMs)
	{
		herbRunsMs.add(durationMs);
		persist();
		notifyListeners();
	}

	/** Custom farm runs (name -> ordered location ids), insertion order. */
	public Map<String, PersistedState.FarmRun> getFarmRuns()
	{
		return java.util.Collections.unmodifiableMap(farmRuns);
	}

	public void saveFarmRun(String name, java.util.List<String> locationIds)
	{
		PersistedState.FarmRun run = new PersistedState.FarmRun();
		run.locationIds = new java.util.ArrayList<>(locationIds);
		farmRuns.put(name, run);
		persist();
		notifyListeners();
	}

	public void deleteFarmRun(String name)
	{
		if (farmRuns.remove(name) != null)
		{
			persist();
			notifyListeners();
		}
	}

	/** Rolling consumption events (time, canonical item id, qty), oldest first. */
	public java.util.List<Consumption> getConsumptionLog()
	{
		return consumptionLog.stream()
			.map(e -> new Consumption(e.timeMs, e.itemId, e.quantity))
			.collect(java.util.stream.Collectors.toList());
	}

	/** Immutable consumption view. */
	public static class Consumption
	{
		public final long timeMs;
		public final int itemId;
		public final int quantity;

		Consumption(long timeMs, int itemId, int quantity)
		{
			this.timeMs = timeMs;
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	/** The variant id actually owned for an item (any variant), or -1. */
	public int ownedVariantOf(int itemId)
	{
		int base = net.runelite.client.game.ItemVariationMapping.map(itemId);
		for (int variant : net.runelite.client.game.ItemVariationMapping.getVariations(base))
		{
			if (ownedCount(variant) > 0)
			{
				return variant;
			}
		}
		return -1;
	}

	/** Owned stock summed across all variations of an item (any variant id). */
	public int canonicalStock(int itemId)
	{
		// map to the group base first: recolours/broken/charged variants all count
		int base = net.runelite.client.game.ItemVariationMapping.map(itemId);
		int total = 0;
		for (int variant : net.runelite.client.game.ItemVariationMapping.getVariations(base))
		{
			total += ownedCount(variant);
		}
		return total;
	}

	/** Score snapshots oldest-first as (timeMs, score) pairs. */
	public java.util.List<long[]> getScoreSnapshots()
	{
		return scoreSnapshots.stream()
			.map(sn -> new long[]{sn.timeMs, sn.score})
			.collect(java.util.stream.Collectors.toList());
	}

	/** Record a snapshot at most ~daily; returns true when recorded. */
	public boolean maybeSnapshotScore(int score)
	{
		PersistedState.ScoreSnapshot last = scoreSnapshots.isEmpty()
			? null : scoreSnapshots.get(scoreSnapshots.size() - 1);
		if (last != null && System.currentTimeMillis() - last.timeMs < SNAPSHOT_MIN_GAP_MS)
		{
			return false;
		}
		PersistedState.ScoreSnapshot snapshot = new PersistedState.ScoreSnapshot();
		snapshot.timeMs = System.currentTimeMillis();
		snapshot.score = score;
		scoreSnapshots.add(snapshot);
		while (scoreSnapshots.size() > MAX_SCORE_SNAPSHOTS)
		{
			scoreSnapshots.remove(0);
		}
		persist();
		return true;
	}

	/** Collection log slots filled, recorded when the log was opened. */
	public int getCollectionLogSlots()
	{
		return collectionLogSlots;
	}

	public int getCollectionLogTotal()
	{
		return collectionLogTotal;
	}

	public long getCollectionLogSeenMs()
	{
		return collectionLogSeenMs;
	}

	public void recordCollectionLog(int slots, int total)
	{
		collectionLogSlots = slots;
		collectionLogTotal = total;
		collectionLogSeenMs = System.currentTimeMillis();
		persist();
		notifyListeners();
	}

	/** All tracked kill counts (source -> kills). */
	public Map<String, Integer> getKillCounts()
	{
		return java.util.Collections.unmodifiableMap(killCounts);
	}

	/** Goal ids the player is pursuing. */
	public Set<String> getSelectedGoals()
	{
		return java.util.Collections.unmodifiableSet(selectedGoals);
	}

	public void selectGoal(String goalId, boolean selected)
	{
		if (selected ? selectedGoals.add(goalId) : selectedGoals.remove(goalId))
		{
			if (!selected && goalId.equals(activeGoal))
			{
				activeGoal = "";
			}
			if (selected && activeGoal.isEmpty())
			{
				activeGoal = goalId; // first goal becomes active automatically
			}
			persist();
			notifyListeners();
		}
	}

	/** CA-task goal seeds (task id → snapshot) for the goal planner. */
	public Map<String, PersistedState.CaGoal> getCaGoals()
	{
		return java.util.Collections.unmodifiableMap(caGoals);
	}

	/** Add a Combat Achievement task to the goal planner (id "ca:&lt;task&gt;"). */
	public void addCaGoal(int taskId, String name, String description, String tier)
	{
		PersistedState.CaGoal seed = new PersistedState.CaGoal();
		seed.name = name;
		seed.description = description;
		seed.tier = tier;
		caGoals.put(String.valueOf(taskId), seed);
		String goalId = "ca:" + taskId;
		if (selectedGoals.contains(goalId))
		{
			persist();
			notifyListeners();
		}
		else
		{
			selectGoal(goalId, true); // persists + notifies
		}
	}

	/** Remove a CA task from the goal planner. */
	public void removeCaGoal(int taskId)
	{
		caGoals.remove(String.valueOf(taskId));
		String goalId = "ca:" + taskId;
		if (selectedGoals.contains(goalId))
		{
			selectGoal(goalId, false); // persists + notifies
		}
		else
		{
			persist();
			notifyListeners();
		}
	}

	/** Diary-task goal seeds (task slug → snapshot) for the goal planner. */
	public Map<String, PersistedState.DiaryGoal> getDiaryGoals()
	{
		return java.util.Collections.unmodifiableMap(diaryGoals);
	}

	/** Add an achievement diary task to the goal planner (id "diary:&lt;slug&gt;"). */
	public void addDiaryGoal(String slug, String task, String region, String tier)
	{
		PersistedState.DiaryGoal seed = new PersistedState.DiaryGoal();
		seed.task = task;
		seed.region = region;
		seed.tier = tier;
		diaryGoals.put(slug, seed);
		String goalId = "diary:" + slug;
		if (selectedGoals.contains(goalId))
		{
			persist();
			notifyListeners();
		}
		else
		{
			selectGoal(goalId, true); // persists + notifies
		}
	}

	/** Remove a diary task from the goal planner. */
	public void removeDiaryGoal(String slug)
	{
		diaryGoals.remove(slug);
		String goalId = "diary:" + slug;
		if (selectedGoals.contains(goalId))
		{
			selectGoal(goalId, false); // persists + notifies
		}
		else
		{
			persist();
			notifyListeners();
		}
	}

	// ── collection log (obtained slots, ranking skips, sync baseline) ─

	/** Canonical item ids of every log slot seen obtained (chat drops +
	 *  widget harvests). */
	public Set<Integer> getClogObtained()
	{
		return java.util.Collections.unmodifiableSet(clogObtained);
	}

	/** Merge newly observed slots into the obtained set (canonical ids);
	 *  persists and notifies only when something was actually new. */
	public void markClogObtained(java.util.Collection<Integer> canonicalIds)
	{
		if (clogObtained.addAll(canonicalIds))
		{
			persist();
			notifyListeners();
		}
	}

	/** Activity indices hidden from the TTNS ranking. */
	public Set<Integer> getClogSkipped()
	{
		return java.util.Collections.unmodifiableSet(clogSkipped);
	}

	public void setClogSkipped(int activityIndex, boolean skipped)
	{
		if (skipped ? clogSkipped.add(activityIndex) : clogSkipped.remove(activityIndex))
		{
			persist();
			notifyListeners();
		}
	}

	/** The player's COLLECTION_COUNT at the last full sync; -1 = never. */
	public int getClogBaseline()
	{
		return clogBaseline;
	}

	public long getClogSyncedMs()
	{
		return clogSyncedMs;
	}

	/** A full widget sync completed: our data is known-complete at this
	 *  count. */
	public void recordClogFullSync(int playerCount)
	{
		clogBaseline = playerCount;
		clogSyncedMs = System.currentTimeMillis();
		persist();
		notifyListeners();
	}

	/** A live drop advanced the player's count by one — keep the baseline
	 *  in lockstep so it doesn't read as drift. No-op until first sync. */
	public void bumpClogBaseline()
	{
		if (clogBaseline >= 0)
		{
			clogBaseline++;
			persist();
		}
	}

	/** Collection-log goal seeds (slot item id → snapshot). */
	public Map<String, PersistedState.ClogGoal> getClogGoals()
	{
		return java.util.Collections.unmodifiableMap(clogGoals);
	}

	/** Add a log slot to the goal planner (id "clog:&lt;itemId&gt;"). */
	public void addClogGoal(int itemId, String name, String activity, java.util.List<String> reqs)
	{
		PersistedState.ClogGoal seed = new PersistedState.ClogGoal();
		seed.name = name;
		seed.activity = activity;
		seed.reqs = new java.util.ArrayList<>(reqs);
		clogGoals.put(String.valueOf(itemId), seed);
		String goalId = "clog:" + itemId;
		if (selectedGoals.contains(goalId))
		{
			persist();
			notifyListeners();
		}
		else
		{
			selectGoal(goalId, true); // persists + notifies
		}
	}

	/** Remove a log slot from the goal planner. */
	public void removeClogGoal(int itemId)
	{
		clogGoals.remove(String.valueOf(itemId));
		String goalId = "clog:" + itemId;
		if (selectedGoals.contains(goalId))
		{
			selectGoal(goalId, false); // persists + notifies
		}
		else
		{
			persist();
			notifyListeners();
		}
	}

	/** The player's standing planner constraints (pins/snoozes/bans/prefs). */
	public com.ironhub.engine.PlanConstraints plannerConstraints()
	{
		com.ironhub.engine.PlanConstraints constraints = new com.ironhub.engine.PlanConstraints();
		constraints.pinned.addAll(plannerPins);
		constraints.snoozed.addAll(plannerSnoozes);
		constraints.bannedMethods.addAll(plannerBans);
		constraints.preferredMethods.putAll(plannerPreferred);
		return constraints;
	}

	public boolean isPlannerPinned(String actionId)
	{
		return plannerPins.contains(actionId);
	}

	public boolean isPlannerSnoozed(String actionId)
	{
		return plannerSnoozes.contains(actionId);
	}

	/** Toggle a pin (clears any snooze on the same action). */
	public void togglePlannerPin(String actionId)
	{
		if (!plannerPins.remove(actionId))
		{
			plannerPins.add(actionId);
			plannerSnoozes.remove(actionId);
		}
		persist();
		notifyListeners();
	}

	/** Toggle a snooze (clears any pin on the same action). */
	public void togglePlannerSnooze(String actionId)
	{
		if (!plannerSnoozes.remove(actionId))
		{
			plannerSnoozes.add(actionId);
			plannerPins.remove(actionId);
		}
		persist();
		notifyListeners();
	}

	public void togglePlannerBan(String methodId)
	{
		if (!plannerBans.remove(methodId))
		{
			plannerBans.add(methodId);
		}
		persist();
		notifyListeners();
	}

	public void setPlannerPreferred(String skillName, String methodId)
	{
		if (methodId == null)
		{
			plannerPreferred.remove(skillName);
		}
		else
		{
			plannerPreferred.put(skillName, methodId);
		}
		persist();
		notifyListeners();
	}

	/** Custom (user-typed) goal seeds for the goal planner. */
	public Map<String, PersistedState.CustomGoal> getCustomGoals()
	{
		return java.util.Collections.unmodifiableMap(customGoals);
	}

	/** Add a user-typed goal (id "custom:...") to the planner. */
	public void addCustomGoal(String goalId, String name, String req)
	{
		PersistedState.CustomGoal seed = new PersistedState.CustomGoal();
		seed.name = name;
		seed.req = req;
		customGoals.put(goalId, seed);
		if (selectedGoals.contains(goalId))
		{
			persist();
			notifyListeners();
		}
		else
		{
			selectGoal(goalId, true); // persists + notifies
		}
	}

	public void removeCustomGoal(String goalId)
	{
		customGoals.remove(goalId);
		if (selectedGoals.contains(goalId))
		{
			selectGoal(goalId, false); // persists + notifies
		}
		else
		{
			persist();
			notifyListeners();
		}
	}

	/** Route view layout: chapter headers on, or pure execution order. */
	public boolean isPlannerRouteChapters()
	{
		return plannerRouteChapters;
	}

	public void setPlannerRouteChapters(boolean chapters)
	{
		plannerRouteChapters = chapters;
		persist(); // view preference — no replan needed, no notify
	}

	/** Known plan hours recorded at the last replan (for session diffs). */
	public double getLastPlanHours()
	{
		return lastPlanHours;
	}

	public void recordPlanHours(double hours)
	{
		lastPlanHours = hours;
		persist(); // no notify: recording the plan must not trigger a replan
	}

	/** Mark many unlock flags at once (one persist + one notify). */
	public void setUnlockedBulk(java.util.Collection<String> keys)
	{
		if (unlocks.addAll(keys))
		{
			persist();
			notifyListeners();
		}
	}

	/** The pinned active goal id, or empty. */
	public String getActiveGoal()
	{
		return activeGoal;
	}

	public void setActiveGoal(String goalId)
	{
		activeGoal = goalId;
		persist();
		notifyListeners();
	}

	/** Death of the local player: capture location + carried items. */
	public void recordDeath(net.runelite.api.coords.WorldPoint where)
	{
		PersistedState.DeathRecord death = new PersistedState.DeathRecord();
		death.timeMs = System.currentTimeMillis();
		death.x = where.getX();
		death.y = where.getY();
		death.plane = where.getPlane();
		inventory.forEach((id, qty) -> death.carried.merge(id, qty, Integer::sum));
		equipment.forEach((id, qty) -> death.carried.merge(id, qty, Integer::sum));
		deaths.add(death);
		while (deaths.size() > MAX_DEATHS)
		{
			deaths.remove(0);
		}
		persist();
		notifyListeners();
	}

	/** Recent deaths, oldest first (read-only views of persisted records). */
	public java.util.List<Death> getDeaths()
	{
		return deaths.stream()
			.map(d -> new Death(d.timeMs,
				new net.runelite.api.coords.WorldPoint(d.x, d.y, d.plane),
				java.util.Collections.unmodifiableMap(d.carried)))
			.collect(java.util.stream.Collectors.toList());
	}

	/** Immutable death view for modules. */
	public static class Death
	{
		public final long timeMs;
		public final net.runelite.api.coords.WorldPoint where;
		public final Map<Integer, Integer> carried;

		Death(long timeMs, net.runelite.api.coords.WorldPoint where, Map<Integer, Integer> carried)
		{
			this.timeMs = timeMs;
			this.where = where;
			this.carried = carried;
		}
	}

	/**
	 * A kill happened: carried items that decreased since the checkpoint
	 * were consumed fighting this source. Potion sips cancel out via
	 * variation mapping until the potion empties.
	 * ponytail: drops/alchs between kills also count as consumption, and
	 * eating a shark while picking one up nets to zero — per-kill
	 * averages converge regardless; refine when Supplies Runway needs it.
	 */
	private void attributeConsumption(String source)
	{
		Map<Integer, Integer> current = carriedCanonical();
		Map<Integer, Integer> checkpoint = supplyCheckpoint;
		supplyCheckpoint = current;
		if (checkpoint.isEmpty())
		{
			return; // no baseline yet (fresh login mid-trip)
		}
		Map<Integer, Integer> used = suppliesBySource.computeIfAbsent(source, s -> new ConcurrentHashMap<>());
		long now = System.currentTimeMillis();
		checkpoint.forEach((id, before) ->
		{
			int delta = before - current.getOrDefault(id, 0);
			if (delta > 0)
			{
				used.merge(id, delta, Integer::sum);
				PersistedState.ConsumptionEvent event = new PersistedState.ConsumptionEvent();
				event.timeMs = now;
				event.itemId = id;
				event.quantity = delta;
				consumptionLog.add(event);
			}
		});
		while (consumptionLog.size() > MAX_CONSUMPTION_EVENTS)
		{
			consumptionLog.remove(0);
		}
	}

	/** Inventory + equipment, variation-mapped to canonical item ids. */
	private Map<Integer, Integer> carriedCanonical()
	{
		Map<Integer, Integer> carried = new HashMap<>();
		inventory.forEach((id, qty) ->
			carried.merge(net.runelite.client.game.ItemVariationMapping.map(id), qty, Integer::sum));
		equipment.forEach((id, qty) ->
			carried.merge(net.runelite.client.game.ItemVariationMapping.map(id), qty, Integer::sum));
		return carried;
	}

	/** Reset the consumption baseline (bank interaction = trip boundary). */
	void checkpointSupplies()
	{
		supplyCheckpoint = carriedCanonical();
	}

	/** Lifetime loot totals for a source (item id -> quantity). */
	public Map<Integer, Integer> lootFor(String source)
	{
		return lootBySource.getOrDefault(source, Map.of());
	}

	/** Epoch millis a daily was manually ticked, or 0 if never. */
	public long dailyDoneAt(String dailyId)
	{
		return dailiesDoneAt.getOrDefault(dailyId, 0L);
	}

	public void markDaily(String dailyId, boolean done)
	{
		if (done)
		{
			dailiesDoneAt.put(dailyId, System.currentTimeMillis());
		}
		else
		{
			dailiesDoneAt.remove(dailyId);
		}
		persist();
		notifyListeners();
	}

	// ── event ingestion (client thread) ───────────────────────────────

	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				long hash = client.getAccountHash();
				if (hash != -1 && hash != profile)
				{
					activateProfile(hash);
				}
				refreshSkills();
				refreshContainers();
				questsDirty = true;
				varbitsRefreshNeeded = true;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				persist();
				break;
			default:
				break;
		}
	}

	public void onStatChanged(StatChanged event)
	{
		ingestStat(event.getSkill(), event.getLevel(), event.getXp());
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		// quest varps are not individually mapped; mark dirty and refresh
		// on a tick throttle instead of chasing per-quest varbit ids
		questsDirty = true;

		if (watchedVarbits.contains(event.getVarbitId()))
		{
			Integer previous = varbitValues.put(event.getVarbitId(), event.getValue());
			if (previous == null || previous != event.getValue())
			{
				notifyListeners();
			}
		}
		// raw varp updates arrive with varbitId == -1
		else if (event.getVarbitId() == -1 && watchedVarps.contains(event.getVarpId()))
		{
			Integer previous = varpValues.put(event.getVarpId(), event.getValue());
			if (previous == null || previous != event.getValue())
			{
				notifyListeners();
			}
		}
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK.getId())
		{
			ingestBank(itemsOf(event.getItemContainer()));
			persist();
		}
		else if (event.getContainerId() == InventoryID.INVENTORY.getId())
		{
			inventory = Map.copyOf(itemsOf(event.getItemContainer()));
			inventorySlots = slotsOf(event.getItemContainer(), 28);
			inventoryDirty = true; // notified on a tick throttle — changes are frequent
		}
		else if (event.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			equipment = Map.copyOf(itemsOf(event.getItemContainer()));
			equipmentSlots = slotsOf(event.getItemContainer(), 14);
			notifyListeners(); // gear swaps are rare and loadout-relevant
		}
	}

	public void onGameTick()
	{
		tick++;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (questsDirty && tick - lastQuestRefreshTick >= QUEST_REFRESH_TICKS)
		{
			refreshQuests();
		}
		if (varbitsRefreshNeeded)
		{
			varbitsRefreshNeeded = false;
			refreshWatchedVarbits();
		}
		if (!containersSeeded)
		{
			// plugin started mid-session: no GameStateChanged will fire, so
			// seed skills/containers once from the live client
			containersSeeded = true;
			refreshSkills();
			refreshContainers();
			notifyListeners();
		}
		if (inventoryDirty && tick % 10 == 0) // ~6s: keeps panel rebuilds sane
		{
			inventoryDirty = false;
			notifyListeners();
		}
	}

	// ── ingestion internals (package-private for tests) ───────────────

	void ingestStat(Skill skill, int level, int experience)
	{
		Integer previousLevel = realLevels.put(skill, level);
		xp.put(skill, experience);
		if (previousLevel == null || previousLevel != level)
		{
			notifyListeners(); // levels gate requirements; xp drops alone don't
		}
	}

	void ingestQuest(Quest quest, QuestState state)
	{
		questStates.put(quest, state);
	}

	void ingestVarbit(int varbitId, int value)
	{
		varbitValues.put(varbitId, value);
	}

	void ingestVarp(int varpId, int value)
	{
		varpValues.put(varpId, value);
	}

	void ingestBank(Map<Integer, Integer> contents)
	{
		bank = Map.copyOf(contents);
		bankTimestamp = System.currentTimeMillis();
		// every bank interaction re-baselines, so deposits/withdrawals
		// are never misread as consumption
		checkpointSupplies();
		if (itemManager != null) // client thread; compositions are cached
		{
			for (int id : contents.keySet())
			{
				itemNames.computeIfAbsent(id, i -> itemManager.getItemComposition(i).getName());
			}
		}
		notifyListeners();
	}

	void ingestItemNames(Map<Integer, String> names)
	{
		itemNames.putAll(names);
	}

	void ingestInventory(Map<Integer, Integer> contents)
	{
		inventory = Map.copyOf(contents);
	}

	void ingestEquipment(Map<Integer, Integer> contents)
	{
		equipment = Map.copyOf(contents);
	}

	void ingestInventorySlots(int[] slots)
	{
		inventorySlots = slots.clone();
	}

	void ingestEquipmentSlots(int[] slots)
	{
		equipmentSlots = slots.clone();
	}

	/** Switch to a profile and load its persisted slice. */
	void activateProfile(long hash)
	{
		profile = hash;
		PersistedState persisted = store.load(hash);
		bank = Map.copyOf(persisted.bank);
		bankTimestamp = persisted.bankTimestamp;
		itemNames.clear();
		itemNames.putAll(persisted.itemNames);
		unlocks.clear();
		unlocks.addAll(persisted.unlocks);
		killCounts.clear();
		killCounts.putAll(persisted.killCounts);
		dailiesDoneAt.clear();
		dailiesDoneAt.putAll(persisted.dailiesDoneAt);
		lootBySource.clear();
		persisted.lootBySource.forEach((src, items) ->
			lootBySource.put(src, new ConcurrentHashMap<>(items)));
		suppliesBySource.clear();
		persisted.suppliesBySource.forEach((src, items) ->
			suppliesBySource.put(src, new ConcurrentHashMap<>(items)));
		savedLoadouts.clear();
		persisted.savedLoadouts.forEach((activity, slots) ->
			savedLoadouts.put(activity, new ConcurrentHashMap<>(slots)));
		savedSetups.clear();
		savedSetups.putAll(persisted.savedSetups);
		herbRunsMs.clear();
		herbRunsMs.addAll(persisted.herbRunsMs);
		farmRuns.clear();
		farmRuns.putAll(persisted.farmRuns);
		consumptionLog.clear();
		consumptionLog.addAll(persisted.consumptionLog);
		deaths.clear();
		deaths.addAll(persisted.deaths);
		selectedGoals.clear();
		selectedGoals.addAll(persisted.selectedGoals);
		caGoals.clear();
		caGoals.putAll(persisted.caGoals);
		diaryGoals.clear();
		diaryGoals.putAll(persisted.diaryGoals);
		clogObtained.clear();
		clogObtained.addAll(persisted.clogObtained);
		clogSkipped.clear();
		clogSkipped.addAll(persisted.clogSkipped);
		clogBaseline = persisted.clogBaseline;
		clogSyncedMs = persisted.clogSyncedMs;
		clogGoals.clear();
		clogGoals.putAll(persisted.clogGoals);
		plannerPins.clear();
		plannerPins.addAll(persisted.plannerPins);
		plannerSnoozes.clear();
		plannerSnoozes.addAll(persisted.plannerSnoozes);
		plannerBans.clear();
		plannerBans.addAll(persisted.plannerBans);
		plannerPreferred.clear();
		plannerPreferred.putAll(persisted.plannerPreferred);
		lastPlanHours = persisted.lastPlanHours;
		plannerRouteChapters = persisted.plannerRouteChapters;
		customGoals.clear();
		customGoals.putAll(persisted.customGoals);
		scoreSnapshots.clear();
		scoreSnapshots.addAll(persisted.scoreSnapshots);
		collectionLogSlots = persisted.collectionLogSlots;
		collectionLogTotal = persisted.collectionLogTotal;
		collectionLogSeenMs = persisted.collectionLogSeenMs;
		activeGoal = persisted.activeGoal == null ? "" : persisted.activeGoal;
		log.debug("activated profile {} ({} banked item stacks)", hash, bank.size());
	}

	public void persist()
	{
		if (profile == -1)
		{
			return;
		}
		PersistedState state = new PersistedState();
		state.bank = new HashMap<>(bank);
		state.bankTimestamp = bankTimestamp;
		state.itemNames = new HashMap<>(itemNames);
		state.unlocks = new HashSet<>(unlocks);
		state.killCounts = new HashMap<>(killCounts);
		state.dailiesDoneAt = new HashMap<>(dailiesDoneAt);
		lootBySource.forEach((src, items) -> state.lootBySource.put(src, new HashMap<>(items)));
		suppliesBySource.forEach((src, items) -> state.suppliesBySource.put(src, new HashMap<>(items)));
		savedLoadouts.forEach((activity, slots) -> state.savedLoadouts.put(activity, new HashMap<>(slots)));
		state.savedSetups.putAll(savedSetups);
		state.herbRunsMs = new java.util.ArrayList<>(herbRunsMs);
		state.farmRuns = new HashMap<>(farmRuns);
		state.consumptionLog = new java.util.ArrayList<>(consumptionLog);
		state.deaths = new java.util.ArrayList<>(deaths);
		state.selectedGoals = new HashSet<>(selectedGoals);
		state.activeGoal = activeGoal;
		state.caGoals = new HashMap<>(caGoals);
		state.diaryGoals = new HashMap<>(diaryGoals);
		state.clogObtained = new HashSet<>(clogObtained);
		state.clogSkipped = new HashSet<>(clogSkipped);
		state.clogBaseline = clogBaseline;
		state.clogSyncedMs = clogSyncedMs;
		state.clogGoals = new HashMap<>(clogGoals);
		state.plannerPins = new HashSet<>(plannerPins);
		state.plannerSnoozes = new HashSet<>(plannerSnoozes);
		state.plannerBans = new HashSet<>(plannerBans);
		state.plannerPreferred = new HashMap<>(plannerPreferred);
		state.lastPlanHours = lastPlanHours;
		state.plannerRouteChapters = plannerRouteChapters;
		state.customGoals = new HashMap<>(customGoals);
		state.scoreSnapshots = new java.util.ArrayList<>(scoreSnapshots);
		state.collectionLogSlots = collectionLogSlots;
		state.collectionLogTotal = collectionLogTotal;
		state.collectionLogSeenMs = collectionLogSeenMs;
		store.save(profile, state);
	}

	private void refreshSkills()
	{
		for (Skill skill : Skill.values())
		{
			ingestStat(skill, client.getRealSkillLevel(skill), client.getSkillExperience(skill));
		}
	}

	private void refreshContainers()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			ingestInventory(itemsOf(inv));
			inventorySlots = slotsOf(inv, 28);
		}
		ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equip != null)
		{
			ingestEquipment(itemsOf(equip));
			equipmentSlots = slotsOf(equip, 14);
		}
	}

	private void refreshQuests()
	{
		boolean changed = false;
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			changed |= questStates.put(quest, state) != state;
		}
		int points = client.getVarpValue(VarPlayer.QUEST_POINTS);
		changed |= points != questPoints;
		questPoints = points;

		questsDirty = false;
		lastQuestRefreshTick = tick;
		if (changed)
		{
			notifyListeners();
		}
	}

	private void refreshWatchedVarbits()
	{
		boolean changed = false;
		for (int id : watchedVarbits)
		{
			int value = client.getVarbitValue(id);
			Integer previous = varbitValues.put(id, value);
			changed |= previous == null || previous != value;
		}
		for (int id : watchedVarps)
		{
			int value = client.getVarpValue(id);
			Integer previous = varpValues.put(id, value);
			changed |= previous == null || previous != value;
		}
		if (changed)
		{
			notifyListeners();
		}
	}

	/** Slot-ordered item ids (-1 = empty), padded/truncated to size. */
	private static int[] slotsOf(ItemContainer container, int size)
	{
		int[] slots = new int[size];
		java.util.Arrays.fill(slots, -1);
		Item[] items = container.getItems();
		for (int i = 0; i < Math.min(size, items.length); i++)
		{
			if (items[i].getId() > 0 && items[i].getQuantity() > 0)
			{
				slots[i] = items[i].getId();
			}
		}
		return slots;
	}

	private static Map<Integer, Integer> itemsOf(ItemContainer container)
	{
		Map<Integer, Integer> contents = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0) // skips empty slots + bank placeholders
			{
				contents.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return contents;
	}
}
