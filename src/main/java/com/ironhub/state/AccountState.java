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
	/** Quest states refresh at most once per this many ticks (~30 s) when
	 *  dirty. Each refresh runs a clientscript per quest (~210 runScripts —
	 *  Quest.getState is QUEST_STATUS_GET), and the dirty flag trips on EVERY
	 *  varbit change, so during any activity this cadence IS the cost. Quest
	 *  completions are rare; 30 s staleness beats a 6 s script storm
	 *  (2026-07-17 freeze audit). */
	private static final int QUEST_REFRESH_TICKS = 50;

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
	// items hidden from the bank Highest-alchs view: id -> banked quantity
	// when excluded (persisted). Excluded at quantity 1 = "not worth alching
	// my only one" — auto-returns once the player owns more than one.
	private final Map<Integer, Integer> alchExcludedAtQty = new ConcurrentHashMap<>();
	/** Migration marker for pre-map exclusions: treated as excluded-at-many (permanent). */
	private static final int EXCLUDED_AT_MANY = Integer.MAX_VALUE;
	// bank-tab per-skill target levels: skill name -> level (persisted)
	private final Map<String, Integer> bankSkillTargets = new ConcurrentHashMap<>();
	/** Dailies the player has explicitly included/excluded from the guided run.
	 *  Only explicit choices are stored — an absent id falls back to the pack's
	 *  own default, so a new event opts in and a Wilderness one stays out. */
	private final Map<String, Boolean> dailiesChoice = new ConcurrentHashMap<>();

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
	// per-run saved gear+inventory setups (run name -> setup), shown at the bank
	private final Map<String, PersistedState.SavedSetup> farmRunSetups = new ConcurrentHashMap<>();
	// preferred teleport per farm location (location id -> teleport id)
	private final Map<String, String> farmTeleportPrefs = new ConcurrentHashMap<>();
	/** Farm runs ticked into the combined "start all" run; absent = included. */
	private final Map<String, Boolean> farmRunChoice = new ConcurrentHashMap<>();
	/** The player's run-list order (names; unknown ignored, new appended). */
	private final java.util.List<String> farmRunOrder = new CopyOnWriteArrayList<>();
	/** Completed farm-run outcomes (xp per bucket, herbs), oldest first. */
	public static final int MAX_FARM_RUN_RECORDS = 100;
	private final java.util.List<PersistedState.FarmRunRecord> farmRunLog = new CopyOnWriteArrayList<>();

	// built POH tiers (pack tier ids)
	private final Set<String> pohBuilt = ConcurrentHashMap.newKeySet();

	// hunters' rumours: preferred locations + capped records
	public static final int MAX_RUMOUR_RECORDS = 50;
	private final Map<String, String> rumourPrefLocations = new ConcurrentHashMap<>();
	private final java.util.List<PersistedState.RumourRecord> rumourRecords = new CopyOnWriteArrayList<>();

	// STASH units (object ids)
	private final Set<Integer> stashBuilt = ConcurrentHashMap.newKeySet();
	private final Set<Integer> stashFilled = ConcurrentHashMap.newKeySet();

	/** Slayer task records, oldest first; the last may be active (end == 0). */
	public static final int MAX_SLAYER_RECORDS = 50;
	private final java.util.List<PersistedState.SlayerTaskRecord> slayerRecords = new CopyOnWriteArrayList<>();
	private final Map<String, String> slayerNotes = new ConcurrentHashMap<>();
	private final Map<String, String> slayerLocationPrefs = new ConcurrentHashMap<>();
	private final Map<String, java.util.List<String>> slayerBlockPrefs = new ConcurrentHashMap<>();
	private final Map<String, java.util.List<String>> slayerSkipPrefs = new ConcurrentHashMap<>();

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

	// goal planner selections + unified goal seeds (persisted)
	private final Set<String> selectedGoals = ConcurrentHashMap.newKeySet();
	private volatile String activeGoal = "";
	private final Map<String, PersistedState.GoalSeed> goalSeeds = new ConcurrentHashMap<>();
	/** Completed-goal archive (G2), oldest first, capped. */
	public static final int MAX_GOAL_RECORDS = 200;
	private final java.util.List<PersistedState.GoalRecord> goalRecords = new CopyOnWriteArrayList<>();
	// collection log (persisted): obtained slots, ranking skips, sync baseline
	private final Set<Integer> clogObtained = ConcurrentHashMap.newKeySet();
	private final Set<Integer> clogSkipped = ConcurrentHashMap.newKeySet();
	private volatile int clogBaseline = -1;
	private volatile long clogSyncedMs;
	private final Set<String> plannerPins = ConcurrentHashMap.newKeySet();
	private final Set<String> plannerSnoozes = ConcurrentHashMap.newKeySet();
	private final Set<String> plannerBans = ConcurrentHashMap.newKeySet();
	private final Map<String, String> plannerPreferred = new ConcurrentHashMap<>();
	private volatile double lastPlanHours;
	private volatile boolean plannerRouteChapters;

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
	/** Rune pouch contents (rune item id -> quantity), rebuilt from the pouch
	 *  varbits on the client thread; empty until seen / when unavailable. */
	private volatile Map<Integer, Integer> runePouch = Map.of();
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

	/** Persist coalescing (2026-07-17 freeze audit): a burst tick can ask to
	 *  persist dozens of times (2x per kill), and each snapshot deep-copies
	 *  every collection — so live clients mark dirty and flush on a tick
	 *  cadence, with immediate flushes at logout/hop/profile-switch/shutdown.
	 *  Headless (client == null) persists synchronously: tests have no ticks
	 *  to flush by and assert files right after mutating. */
	private volatile boolean persistDirty;
	private int lastPersistTick;
	private static final int PERSIST_FLUSH_TICKS = 5; // ~3 s

	/** Rune pouch slot varbits: the rune-type index in each slot, paired with
	 *  the amount varbit at the same index (6 slots covers the divine pouch). */
	private static final int[] POUCH_RUNE_VARBITS = {
		net.runelite.api.Varbits.RUNE_POUCH_RUNE1, net.runelite.api.Varbits.RUNE_POUCH_RUNE2,
		net.runelite.api.Varbits.RUNE_POUCH_RUNE3, net.runelite.api.Varbits.RUNE_POUCH_RUNE4,
		net.runelite.api.Varbits.RUNE_POUCH_RUNE5, net.runelite.api.Varbits.RUNE_POUCH_RUNE6,
	};
	private static final int[] POUCH_AMOUNT_VARBITS = {
		net.runelite.api.Varbits.RUNE_POUCH_AMOUNT1, net.runelite.api.Varbits.RUNE_POUCH_AMOUNT2,
		net.runelite.api.Varbits.RUNE_POUCH_AMOUNT3, net.runelite.api.Varbits.RUNE_POUCH_AMOUNT4,
		net.runelite.api.Varbits.RUNE_POUCH_AMOUNT5, net.runelite.api.Varbits.RUNE_POUCH_AMOUNT6,
	};

	@Inject
	public AccountState(Client client, net.runelite.client.game.ItemManager itemManager, ProfileStore store)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.store = store;
		// the rune pouch backs teleport-rune requirements everywhere (farm runs,
		// loadouts); track it as core account state, not per-module.
		watchVarbits(POUCH_RUNE_VARBITS);
		watchVarbits(POUCH_AMOUNT_VARBITS);
	}

	/** Rune pouch contents (rune item id -> quantity); empty when the pouch
	 *  isn't carried or hasn't been seen. Runes here count as carried. */
	public Map<Integer, Integer> getRunePouch()
	{
		return runePouch;
	}

	/** Rebuild the rune-pouch cache from the watched pouch varbits. Client
	 *  thread only (reads the rune enum); a no-op headless. */
	private void rebuildRunePouch()
	{
		if (client == null)
		{
			return;
		}
		net.runelite.api.EnumComposition runeEnum = client.getEnum(net.runelite.api.EnumID.RUNEPOUCH_RUNE);
		Map<Integer, Integer> pouch = new java.util.HashMap<>();
		for (int i = 0; i < POUCH_RUNE_VARBITS.length; i++)
		{
			int runeIndex = varbitValues.getOrDefault(POUCH_RUNE_VARBITS[i], 0);
			if (runeIndex <= 0)
			{
				continue;
			}
			int runeId = runeEnum.getIntValue(runeIndex);
			int amount = varbitValues.getOrDefault(POUCH_AMOUNT_VARBITS[i], 0);
			if (runeId > 0 && amount > 0)
			{
				pouch.merge(runeId, amount, Integer::sum);
			}
		}
		runePouch = Map.copyOf(pouch);
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

	// ── hunters' rumours ──────────────────────────────────────────────

	public String getRumourPrefLocation(String creature)
	{
		return rumourPrefLocations.get(creature);
	}

	public void setRumourPrefLocation(String creature, String location)
	{
		if (location == null)
		{
			rumourPrefLocations.remove(creature);
		}
		else
		{
			rumourPrefLocations.put(creature, location);
		}
		persist();
		notifyListeners();
	}

	/** Rumour records, oldest first (copies). */
	public java.util.List<PersistedState.RumourRecord> getRumourRecords()
	{
		java.util.List<PersistedState.RumourRecord> out = new java.util.ArrayList<>();
		for (PersistedState.RumourRecord r : rumourRecords)
		{
			out.add(r.copy());
		}
		return out;
	}

	public void setRumourRecords(java.util.List<PersistedState.RumourRecord> records)
	{
		java.util.List<PersistedState.RumourRecord> copies = new java.util.ArrayList<>();
		int from = Math.max(0, records.size() - MAX_RUMOUR_RECORDS);
		for (PersistedState.RumourRecord r : records.subList(from, records.size()))
		{
			copies.add(r.copy());
		}
		rumourRecords.clear();
		rumourRecords.addAll(copies);
		persist();
		notifyListeners();
	}

	// ── POH progression ───────────────────────────────────────────────

	public boolean isPohBuilt(String tierId)
	{
		return pohBuilt.contains(tierId);
	}

	public void setPohBuilt(String tierId, boolean built)
	{
		boolean changed = built ? pohBuilt.add(tierId) : pohBuilt.remove(tierId);
		if (changed)
		{
			persist();
			notifyListeners();
		}
	}

	/** Bulk mark from an own-house sweep — one persist + notify. */
	public void setPohBuiltBulk(java.util.Collection<String> tierIds)
	{
		if (pohBuilt.addAll(tierIds))
		{
			persist();
			notifyListeners();
		}
	}

	// ── clues & STASH ─────────────────────────────────────────────────

	public boolean isStashBuilt(int objectId)
	{
		return stashBuilt.contains(objectId);
	}

	public boolean isStashFilled(int objectId)
	{
		return stashFilled.contains(objectId);
	}

	public void setStashBuilt(int objectId, boolean built)
	{
		boolean changed = built ? stashBuilt.add(objectId) : stashBuilt.remove(objectId);
		if (changed)
		{
			persist();
			notifyListeners();
		}
	}

	/** Filling implies built; emptying leaves built alone. */
	public void setStashFilled(int objectId, boolean filled)
	{
		boolean changed = filled ? stashFilled.add(objectId) : stashFilled.remove(objectId);
		if (filled)
		{
			changed |= stashBuilt.add(objectId);
		}
		if (changed)
		{
			persist();
			notifyListeners();
		}
	}

	// ── unified goal seeds (Goals v2 G1): every synthetic goal family ──

	/** Every persisted goal seed, keyed by full goal id ("ca:340"). */
	public Map<String, PersistedState.GoalSeed> getGoalSeeds()
	{
		return java.util.Collections.unmodifiableMap(goalSeeds);
	}

	/** Family-local ids of every seed in a family ("ca" → task ids) —
	 *  the proof-marking modules' scan set. */
	public Set<String> goalSeedIds(String family)
	{
		Set<String> ids = new HashSet<>();
		String prefix = family + ":";
		for (PersistedState.GoalSeed seed : goalSeeds.values())
		{
			if (family.equals(seed.family) && seed.id.startsWith(prefix))
			{
				ids.add(seed.id.substring(prefix.length()));
			}
		}
		return ids;
	}

	/** Add a goal seed (built by {@link GoalSeeds}) and select it. */
	public void addGoalSeed(PersistedState.GoalSeed seed)
	{
		if (seed.addedAt == 0)
		{
			seed.addedAt = System.currentTimeMillis();
		}
		goalSeeds.put(seed.id, seed);
		if (selectedGoals.contains(seed.id))
		{
			persist();
			notifyListeners();
		}
		else
		{
			selectGoal(seed.id, true); // persists + notifies
		}
	}

	/** Remove a goal seed and deselect its goal. */
	public void removeGoalSeed(String goalId)
	{
		goalSeeds.remove(goalId);
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

	// ── completion archive (Goals v2 G2) ──────────────────────────────

	/** Completed-goal records, oldest first; one per goalId (latest wins). */
	public java.util.List<PersistedState.GoalRecord> getGoalRecords()
	{
		java.util.List<PersistedState.GoalRecord> out = new java.util.ArrayList<>();
		for (PersistedState.GoalRecord r : goalRecords)
		{
			out.add(r.copy());
		}
		return out;
	}

	/**
	 * Upsert a completion record by goalId (latest wins — a re-armed supply
	 * goal that completes again replaces its earlier record, never
	 * duplicates on a login replay). Caps at MAX_GOAL_RECORDS.
	 */
	public void recordGoalCompletion(PersistedState.GoalRecord record)
	{
		goalRecords.removeIf(r -> r.goalId.equals(record.goalId));
		goalRecords.add(record.copy());
		while (goalRecords.size() > MAX_GOAL_RECORDS)
		{
			goalRecords.remove(0);
		}
		persist();
		notifyListeners();
	}

	// ── slayer suite ──────────────────────────────────────────────────

	/** Slayer task records, oldest first (copies — mutate via setSlayerRecords). */
	public java.util.List<PersistedState.SlayerTaskRecord> getSlayerRecords()
	{
		java.util.List<PersistedState.SlayerTaskRecord> out = new java.util.ArrayList<>();
		for (PersistedState.SlayerTaskRecord r : slayerRecords)
		{
			out.add(r.copy());
		}
		return out;
	}

	/** Replace the record list (module owns the working copy); caps and copies. */
	public void setSlayerRecords(java.util.List<PersistedState.SlayerTaskRecord> records)
	{
		java.util.List<PersistedState.SlayerTaskRecord> copies = new java.util.ArrayList<>();
		int from = Math.max(0, records.size() - MAX_SLAYER_RECORDS);
		for (PersistedState.SlayerTaskRecord r : records.subList(from, records.size()))
		{
			copies.add(r.copy());
		}
		slayerRecords.clear();
		slayerRecords.addAll(copies);
		persist();
		notifyListeners();
	}

	public String getSlayerNote(String task)
	{
		return slayerNotes.getOrDefault(task, "");
	}

	public void setSlayerNote(String task, String note)
	{
		if (note == null || note.isBlank())
		{
			slayerNotes.remove(task);
		}
		else
		{
			slayerNotes.put(task, note);
		}
		persist();
		notifyListeners();
	}

	/** Preferred location name for a task, or null (auto). */
	public String getSlayerLocationPref(String task)
	{
		return slayerLocationPrefs.get(task);
	}

	public void setSlayerLocationPref(String task, String location)
	{
		if (location == null)
		{
			slayerLocationPrefs.remove(task);
		}
		else
		{
			slayerLocationPrefs.put(task, location);
		}
		persist();
		notifyListeners();
	}

	/** Preferred block list for a master (task names), empty when unset. */
	public java.util.List<String> getSlayerBlockPref(String master)
	{
		return java.util.List.copyOf(slayerBlockPrefs.getOrDefault(master, java.util.List.of()));
	}

	public void setSlayerBlockPref(String master, java.util.List<String> tasks)
	{
		if (tasks == null || tasks.isEmpty())
		{
			slayerBlockPrefs.remove(master);
		}
		else
		{
			slayerBlockPrefs.put(master, new java.util.ArrayList<>(tasks));
		}
		persist();
		notifyListeners();
	}

	/** Always-skip list for a master (task names), empty when unset. */
	public java.util.List<String> getSlayerSkipPref(String master)
	{
		return java.util.List.copyOf(slayerSkipPrefs.getOrDefault(master, java.util.List.of()));
	}

	public void setSlayerSkipPref(String master, java.util.List<String> tasks)
	{
		if (tasks == null || tasks.isEmpty())
		{
			slayerSkipPrefs.remove(master);
		}
		else
		{
			slayerSkipPrefs.put(master, new java.util.ArrayList<>(tasks));
		}
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
		boolean changed = farmRuns.remove(name) != null;
		changed |= farmRunSetups.remove(name) != null; // its gear+inventory setup goes too
		if (changed)
		{
			persist();
			notifyListeners();
		}
	}

	/** The player's preferred teleport id for a farm location, or null (auto). */
	/** Whether a run joins the combined "start all runs" sequence. */
	public boolean isFarmRunSelected(String name)
	{
		return farmRunChoice.getOrDefault(name, true);
	}

	public void setFarmRunSelected(String name, boolean selected)
	{
		if (!Boolean.valueOf(selected).equals(farmRunChoice.put(name, selected)))
		{
			persist();
			notifyListeners();
		}
	}

	/** The player's run-list order, as last saved (may name runs that no
	 *  longer exist, and misses newly added ones — callers reconcile). */
	public java.util.List<String> getFarmRunOrder()
	{
		return java.util.List.copyOf(farmRunOrder);
	}

	public void setFarmRunOrder(java.util.List<String> order)
	{
		farmRunOrder.clear();
		farmRunOrder.addAll(order);
		persist();
		notifyListeners();
	}

	/** Completed farm-run outcomes, oldest first. */
	public java.util.List<PersistedState.FarmRunRecord> getFarmRunLog()
	{
		return java.util.List.copyOf(farmRunLog);
	}

	public void recordFarmRun(PersistedState.FarmRunRecord record)
	{
		farmRunLog.add(record);
		while (farmRunLog.size() > MAX_FARM_RUN_RECORDS)
		{
			farmRunLog.remove(0);
		}
		persist();
		notifyListeners();
	}

	public String getFarmTeleportPref(String locationId)
	{
		return farmTeleportPrefs.get(locationId);
	}

	/** Set (teleportId) or clear (null) the preferred teleport for a location. */
	public void setFarmTeleportPref(String locationId, String teleportId)
	{
		if (teleportId == null)
		{
			farmTeleportPrefs.remove(locationId);
		}
		else
		{
			farmTeleportPrefs.put(locationId, teleportId);
		}
		persist();
		notifyListeners();
	}

	/** The saved gear + inventory setup for a run (name), or null. */
	public PersistedState.SavedSetup getFarmRunSetup(String name)
	{
		return farmRunSetups.get(name);
	}

	/** Remember the player's current gear + inventory as this run's setup;
	 *  null clears it. */
	public void saveFarmRunSetup(String name, PersistedState.SavedSetup setup)
	{
		if (setup == null)
		{
			farmRunSetups.remove(name);
		}
		else
		{
			farmRunSetups.put(name, setup);
		}
		persist();
		notifyListeners();
	}

	/**
	 * Snapshot the player's current worn gear and inventory (item ids +
	 * quantities in slot order) — the "remember this setup" primitive the
	 * farm-run bank view redisplays. Reads the live slot arrays, so it is
	 * thread-safe and needs no client thread.
	 * ponytail: the rune pouch isn't captured (farm teleports are tabs /
	 * jewellery / loose runes, which already show as inventory slots); add
	 * the pouch varbits here if a rune-pouch run ever needs it.
	 */
	/**
	 * Setup items you are not already carrying — what a run's bank glow should
	 * point at. Counts every ItemVariationMapping variant, so a charged glory
	 * settles the glory slot, and returns the variants themselves so the bank's
	 * own item id matches whatever is sitting in it.
	 */
	public Set<Integer> setupItemsToWithdraw(PersistedState.SavedSetup setup)
	{
		if (setup == null)
		{
			return Set.of();
		}
		Map<Integer, Integer> need = new HashMap<>();
		if (setup.equipment != null)
		{
			for (Integer id : setup.equipment.values())
			{
				if (id != null && id > 0)
				{
					need.merge(id, 1, Integer::sum);
				}
			}
		}
		if (setup.inventory != null)
		{
			for (int i = 0; i < setup.inventory.length; i++)
			{
				int id = setup.inventory[i];
				if (id > 0)
				{
					int qty = setup.inventoryQty != null && i < setup.inventoryQty.length
						? Math.max(1, setup.inventoryQty[i]) : 1;
					need.merge(id, qty, Integer::sum);
				}
			}
		}
		Set<Integer> out = new HashSet<>();
		for (Map.Entry<Integer, Integer> entry : need.entrySet())
		{
			if (carriedVariants(entry.getKey()) < entry.getValue())
			{
				out.addAll(net.runelite.client.game.ItemVariationMapping.getVariations(
					net.runelite.client.game.ItemVariationMapping.map(entry.getKey())));
			}
		}
		return out;
	}

	/** Inventory + worn + rune pouch count of an item, variants included. */
	public int carriedCount(int itemId)
	{
		return carriedVariants(itemId);
	}

	/** Inventory + worn + rune pouch, counting every variant of an item. */
	private int carriedVariants(int itemId)
	{
		Set<Integer> ids = new HashSet<>(net.runelite.client.game.ItemVariationMapping.getVariations(
			net.runelite.client.game.ItemVariationMapping.map(itemId)));
		int total = 0;
		for (Map.Entry<Integer, Integer> slot : inventory.entrySet())
		{
			if (ids.contains(slot.getKey()))
			{
				total += slot.getValue();
			}
		}
		for (int worn : getEquipmentSlots())
		{
			if (ids.contains(worn))
			{
				total++;
			}
		}
		for (Map.Entry<Integer, Integer> rune : getRunePouch().entrySet())
		{
			if (ids.contains(rune.getKey()))
			{
				total += rune.getValue();
			}
		}
		return total;
	}

	public PersistedState.SavedSetup captureSetup()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		int[] worn = getEquipmentSlots();
		for (net.runelite.api.EquipmentInventorySlot slot
			: net.runelite.api.EquipmentInventorySlot.values())
		{
			if (slot.getSlotIdx() < worn.length && worn[slot.getSlotIdx()] > 0)
			{
				setup.equipment.put(slot.name(), worn[slot.getSlotIdx()]);
			}
		}
		setup.inventory = getInventorySlots();
		setup.inventoryQty = new int[setup.inventory.length];
		Map<Integer, Integer> quantities = getInventorySnapshot();
		for (int i = 0; i < setup.inventory.length; i++)
		{
			setup.inventoryQty[i] = setup.inventory[i] > 0
				? quantities.getOrDefault(setup.inventory[i], 1) : 0;
		}
		return setup;
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

	// ── bank Highest-alchs exclusions (persisted) ─────────────────────

	/**
	 * Hidden from Highest alchs? An item excluded while it was the player's
	 * ONLY one shows again once they own more than one — the exclusion read
	 * "not worth alching my last one", not "never show this". The auto-return
	 * itself happens in {@link #autoReturnAlchExclusions}, which clears the
	 * entry so excluding the item again at &gt;1 sticks until a reset.
	 */
	public boolean isAlchExcluded(int itemId, int currentQty)
	{
		Integer atQty = alchExcludedAtQty.get(itemId);
		return atQty != null && !(atQty == 1 && currentQty > 1);
	}

	public Set<Integer> getAlchExcluded()
	{
		return java.util.Set.copyOf(alchExcludedAtQty.keySet());
	}

	/** Exclude an item, remembering the banked quantity it was excluded at. */
	public void setAlchExcluded(int itemId, int quantityWhenExcluded)
	{
		Integer previous = alchExcludedAtQty.put(itemId, Math.max(1, quantityWhenExcluded));
		if (previous == null || previous != Math.max(1, quantityWhenExcluded))
		{
			persist();
			notifyListeners();
		}
	}

	/**
	 * Drop qty-1 exclusions the player has outgrown (owns &gt;1 now). The
	 * alch view calls this as it rebuilds, so it persists without notifying —
	 * the caller is already rendering the post-return list.
	 */
	public void autoReturnAlchExclusions(Map<Integer, Integer> bank)
	{
		boolean changed = alchExcludedAtQty.entrySet().removeIf(e ->
			e.getValue() == 1 && bank.getOrDefault(e.getKey(), 0) > 1);
		if (changed)
		{
			persist();
		}
	}

	public void clearAlchExclusions()
	{
		if (!alchExcludedAtQty.isEmpty())
		{
			alchExcludedAtQty.clear();
			persist();
			notifyListeners();
		}
	}

	// ── bank-tab per-skill target levels (persisted) ──────────────────

	/** The bank tab's target level for a skill, 0 when unset. */
	public int getBankSkillTarget(String skillName)
	{
		return bankSkillTargets.getOrDefault(skillName, 0);
	}

	/** Set (target > 0) or clear a bank target; no-op when unchanged. */
	public void setBankSkillTarget(String skillName, int target)
	{
		Integer previous = target > 0
			? bankSkillTargets.put(skillName, target)
			: bankSkillTargets.remove(skillName);
		if ((previous == null ? 0 : previous) != Math.max(0, target))
		{
			persist();
			notifyListeners();
		}
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

	/**
	 * Whether a daily is eligible for the guided run (the tab's checklist).
	 *
	 * @param byDefault what the pack says when the player has never chosen —
	 *                  true for everything except the Wilderness events
	 */
	public boolean isDailySelected(String dailyId, boolean byDefault)
	{
		return dailiesChoice.getOrDefault(dailyId, byDefault);
	}

	public void setDailySelected(String dailyId, boolean selected)
	{
		if (!Boolean.valueOf(selected).equals(dailiesChoice.put(dailyId, selected)))
		{
			persist();
			notifyListeners();
		}
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
				persistNow(); // ticks stop here — flush anything coalesced
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
				if (isPouchVarbit(event.getVarbitId()))
				{
					rebuildRunePouch();
				}
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
		if (persistDirty && tick - lastPersistTick >= PERSIST_FLUSH_TICKS)
		{
			lastPersistTick = tick;
			persistNow();
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

	/** Test seam: seed the rune pouch (rune item id -> quantity) directly,
	 *  bypassing the varbit/enum decode that needs a live client. */
	void ingestRunePouch(Map<Integer, Integer> contents)
	{
		runePouch = Map.copyOf(contents);
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
		if (persistDirty)
		{
			persistNow(); // still the OLD profile — never write its coalesced
		}                 // state under the incoming account's id
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
		alchExcludedAtQty.clear();
		alchExcludedAtQty.putAll(persisted.alchExcludedAtQty);
		for (Integer id : persisted.alchExcluded)
		{
			// pre-map profiles: excluded-at-many = permanent until reset
			alchExcludedAtQty.putIfAbsent(id, EXCLUDED_AT_MANY);
		}
		bankSkillTargets.clear();
		bankSkillTargets.putAll(persisted.bankSkillTargets);
		dailiesChoice.clear();
		dailiesChoice.putAll(persisted.dailiesChoice);
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
		farmRunSetups.clear();
		farmRunSetups.putAll(persisted.farmRunSetups);
		farmTeleportPrefs.clear();
		farmTeleportPrefs.putAll(persisted.farmTeleportPrefs);
		farmRunChoice.clear();
		farmRunChoice.putAll(persisted.farmRunChoice);
		farmRunOrder.clear();
		farmRunOrder.addAll(persisted.farmRunOrder);
		farmRunLog.clear();
		farmRunLog.addAll(persisted.farmRunLog);
		consumptionLog.clear();
		consumptionLog.addAll(persisted.consumptionLog);
		deaths.clear();
		deaths.addAll(persisted.deaths);
		pohBuilt.clear();
		pohBuilt.addAll(persisted.pohBuilt);
		rumourPrefLocations.clear();
		rumourPrefLocations.putAll(persisted.rumourPrefLocations);
		rumourRecords.clear();
		rumourRecords.addAll(persisted.rumourRecords);
		stashBuilt.clear();
		stashBuilt.addAll(persisted.stashBuilt);
		stashFilled.clear();
		stashFilled.addAll(persisted.stashFilled);
		slayerRecords.clear();
		slayerRecords.addAll(persisted.slayerRecords);
		slayerNotes.clear();
		slayerNotes.putAll(persisted.slayerNotes);
		slayerLocationPrefs.clear();
		slayerLocationPrefs.putAll(persisted.slayerLocationPrefs);
		slayerBlockPrefs.clear();
		slayerBlockPrefs.putAll(persisted.slayerBlockPrefs);
		slayerSkipPrefs.clear();
		slayerSkipPrefs.putAll(persisted.slayerSkipPrefs);
		selectedGoals.clear();
		selectedGoals.addAll(persisted.selectedGoals);
		goalSeeds.clear();
		goalSeeds.putAll(persisted.goalSeeds);
		migrateLegacyGoalSeeds(persisted);
		goalRecords.clear();
		goalRecords.addAll(persisted.goalRecords);
		clogObtained.clear();
		clogObtained.addAll(persisted.clogObtained);
		clogSkipped.clear();
		clogSkipped.addAll(persisted.clogSkipped);
		clogBaseline = persisted.clogBaseline;
		clogSyncedMs = persisted.clogSyncedMs;
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
		scoreSnapshots.clear();
		scoreSnapshots.addAll(persisted.scoreSnapshots);
		collectionLogSlots = persisted.collectionLogSlots;
		collectionLogTotal = persisted.collectionLogTotal;
		collectionLogSeenMs = persisted.collectionLogSeenMs;
		activeGoal = persisted.activeGoal == null ? "" : persisted.activeGoal;
		log.debug("activated profile {} ({} banked item stacks)", hash, bank.size());
	}

	/**
	 * One-time migration (Goals v2 G1): profiles written before the unified
	 * seed map carry five per-family maps — rebuild each entry through the
	 * same {@link GoalSeeds} transforms the modules now use at add time.
	 * addedAt stays 0 ("date unknown" — never invented). Idempotent: an
	 * already-migrated profile has empty legacy maps, and an existing
	 * unified seed is never overwritten.
	 */
	private void migrateLegacyGoalSeeds(PersistedState persisted)
	{
		persisted.caGoals.forEach((taskId, s) -> goalSeeds.putIfAbsent("ca:" + taskId,
			GoalSeeds.ca(Integer.parseInt(taskId), s.name, s.description, s.tier)));
		persisted.diaryGoals.forEach((slug, s) -> goalSeeds.putIfAbsent("diary:" + slug,
			GoalSeeds.diary(slug, s.task, s.region, s.tier)));
		persisted.clueGoals.forEach((id, s) -> goalSeeds.putIfAbsent("clue:" + id,
			GoalSeeds.clue(id, s.text, s.tier, s.reqs)));
		persisted.clogGoals.forEach((itemId, s) -> goalSeeds.putIfAbsent("clog:" + itemId,
			GoalSeeds.clog(Integer.parseInt(itemId), s.name, s.activity, s.reqs)));
		persisted.customGoals.forEach((goalId, s) -> goalSeeds.putIfAbsent(goalId,
			GoalSeeds.custom(goalId, s.name, s.req)));
		if (!persisted.caGoals.isEmpty() || !persisted.diaryGoals.isEmpty()
			|| !persisted.clueGoals.isEmpty() || !persisted.clogGoals.isEmpty()
			|| !persisted.customGoals.isEmpty())
		{
			persist(); // write the unified form; the legacy keys stop being written
		}
	}

	public void persist()
	{
		if (profile == -1)
		{
			return;
		}
		if (client != null)
		{
			// live client: coalesce — onGameTick flushes at most every
			// PERSIST_FLUSH_TICKS, logout/hop/shutdown flush immediately
			persistDirty = true;
			return;
		}
		persistNow();
	}

	/** Snapshot and hand off to the store right now — the flush path. */
	public void persistNow()
	{
		if (profile == -1)
		{
			return;
		}
		persistDirty = false;
		PersistedState state = new PersistedState();
		state.bank = new HashMap<>(bank);
		state.bankTimestamp = bankTimestamp;
		state.itemNames = new HashMap<>(itemNames);
		state.unlocks = new HashSet<>(unlocks);
		state.killCounts = new HashMap<>(killCounts);
		state.dailiesDoneAt = new HashMap<>(dailiesDoneAt);
		state.alchExcluded = new HashSet<>(alchExcludedAtQty.keySet()); // legacy readers
		state.alchExcludedAtQty = new HashMap<>(alchExcludedAtQty);
		state.bankSkillTargets = new HashMap<>(bankSkillTargets);
		state.dailiesChoice = new HashMap<>(dailiesChoice);
		lootBySource.forEach((src, items) -> state.lootBySource.put(src, new HashMap<>(items)));
		suppliesBySource.forEach((src, items) -> state.suppliesBySource.put(src, new HashMap<>(items)));
		savedLoadouts.forEach((activity, slots) -> state.savedLoadouts.put(activity, new HashMap<>(slots)));
		state.savedSetups.putAll(savedSetups);
		state.herbRunsMs = new java.util.ArrayList<>(herbRunsMs);
		state.farmRuns = new HashMap<>(farmRuns);
		state.farmRunSetups = new HashMap<>(farmRunSetups);
		state.farmTeleportPrefs = new HashMap<>(farmTeleportPrefs);
		state.farmRunChoice = new HashMap<>(farmRunChoice);
		state.farmRunOrder = new java.util.ArrayList<>(farmRunOrder);
		state.farmRunLog = new java.util.ArrayList<>(farmRunLog);
		state.consumptionLog = new java.util.ArrayList<>(consumptionLog);
		state.deaths = new java.util.ArrayList<>(deaths);
		state.pohBuilt = new HashSet<>(pohBuilt);
		state.rumourPrefLocations = new HashMap<>(rumourPrefLocations);
		for (PersistedState.RumourRecord r : rumourRecords)
		{
			state.rumourRecords.add(r.copy());
		}
		state.stashBuilt = new HashSet<>(stashBuilt);
		state.stashFilled = new HashSet<>(stashFilled);
		goalSeeds.forEach((id, seed) -> state.goalSeeds.put(id, seed.copy()));
		for (PersistedState.GoalRecord r : goalRecords)
		{
			state.goalRecords.add(r.copy());
		}
		for (PersistedState.SlayerTaskRecord r : slayerRecords)
		{
			state.slayerRecords.add(r.copy());
		}
		state.slayerNotes = new HashMap<>(slayerNotes);
		state.slayerLocationPrefs = new HashMap<>(slayerLocationPrefs);
		slayerBlockPrefs.forEach((m, list) -> state.slayerBlockPrefs.put(m, new java.util.ArrayList<>(list)));
		slayerSkipPrefs.forEach((m, list) -> state.slayerSkipPrefs.put(m, new java.util.ArrayList<>(list)));
		state.selectedGoals = new HashSet<>(selectedGoals);
		state.activeGoal = activeGoal;
		state.clogObtained = new HashSet<>(clogObtained);
		state.clogSkipped = new HashSet<>(clogSkipped);
		state.clogBaseline = clogBaseline;
		state.clogSyncedMs = clogSyncedMs;
		state.plannerPins = new HashSet<>(plannerPins);
		state.plannerSnoozes = new HashSet<>(plannerSnoozes);
		state.plannerBans = new HashSet<>(plannerBans);
		state.plannerPreferred = new HashMap<>(plannerPreferred);
		state.lastPlanHours = lastPlanHours;
		state.plannerRouteChapters = plannerRouteChapters;
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
		rebuildRunePouch();
		if (changed)
		{
			notifyListeners();
		}
	}

	private static boolean isPouchVarbit(int varbitId)
	{
		for (int id : POUCH_RUNE_VARBITS)
		{
			if (id == varbitId)
			{
				return true;
			}
		}
		for (int id : POUCH_AMOUNT_VARBITS)
		{
			if (id == varbitId)
			{
				return true;
			}
		}
		return false;
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
