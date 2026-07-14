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
	private final ProfileStore store;

	private final Map<Skill, Integer> realLevels = new ConcurrentHashMap<>();
	private final Map<Skill, Integer> xp = new ConcurrentHashMap<>();
	private final Map<Quest, QuestState> questStates = new ConcurrentHashMap<>();
	private final Set<String> unlocks = ConcurrentHashMap.newKeySet();
	private final Map<String, Integer> killCounts = new ConcurrentHashMap<>();

	// varbits modules registered interest in (diary tiers, CA points, …)
	private final Set<Integer> watchedVarbits = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> varbitValues = new ConcurrentHashMap<>();
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
	public AccountState(Client client, ProfileStore store)
	{
		this.client = client;
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

	void ingestBank(Map<Integer, Integer> contents)
	{
		bank = Map.copyOf(contents);
		bankTimestamp = System.currentTimeMillis();
		notifyListeners();
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
		unlocks.clear();
		unlocks.addAll(persisted.unlocks);
		killCounts.clear();
		killCounts.putAll(persisted.killCounts);
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
		state.unlocks = new HashSet<>(unlocks);
		state.killCounts = new HashMap<>(killCounts);
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
