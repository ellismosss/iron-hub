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
public class AccountState
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

	/** Epoch millis of the last bank snapshot; 0 = never seen a bank. */
	@Getter
	private volatile long bankTimestamp;

	private volatile long profile = -1;

	// client thread only
	private boolean questsDirty;
	private volatile boolean varbitsRefreshNeeded;
	private int tick;
	private int lastQuestRefreshTick;

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
		checkpoint.forEach((id, before) ->
		{
			int delta = before - current.getOrDefault(id, 0);
			if (delta > 0)
			{
				used.merge(id, delta, Integer::sum);
			}
		});
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
		}
		else if (event.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			equipment = Map.copyOf(itemsOf(event.getItemContainer()));
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
		}
		ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equip != null)
		{
			ingestEquipment(itemsOf(equip));
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
