package com.ironhub.modules.slayer;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.SlayerTasksPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * The slayer suite brain (DESIGN.md §3.16, rebuilt 2026-07-17): current
 * task from the game's own varps/DBTables (core Slayer plugin parity),
 * per-task records (kills, Slayer xp, loot value, duration, completed vs
 * not), NPC target highlighting via {@link NpcOverlayService} using the
 * pack's core-parity target names, per-master block-list decode from the
 * game's own varbits, and live point-unlock state mirrored into the
 * requirement graph's {@code unlock:slayerreward_*} flags.
 */
@Slf4j
@Singleton
public class SlayerOptimizerModule implements IronHubModule
{
	/** SLAYER_TARGET value meaning "boss task" — resolve via the sublist. */
	private static final int CREATURE_BOSS = 98;
	private static final String SUPERIOR_MESSAGE = "A superior foe has appeared...";
	static final java.awt.Color HIGHLIGHT = java.awt.Color.decode("#DDFF00");

	/**
	 * Master focus id -> the game's per-master block-slot varbits (6 slots
	 * + the Lumbridge Elite diary slot), each holding a blocked task id.
	 * Mapping decoded from clientscript 8025/8026-8033 (slayer_rewards_
	 * tasks_blocked_draw); Spria (9) shares Turael's list in the game.
	 */
	private static final Map<Integer, int[]> BLOCK_VARBITS = Map.of(
		1, new int[]{VarbitID.SLAYER_BLOCKED_TURAEL_1, VarbitID.SLAYER_BLOCKED_TURAEL_2,
			VarbitID.SLAYER_BLOCKED_TURAEL_3, VarbitID.SLAYER_BLOCKED_TURAEL_4,
			VarbitID.SLAYER_BLOCKED_TURAEL_5, VarbitID.SLAYER_BLOCKED_TURAEL_6,
			VarbitID.SLAYER_BLOCKED_TURAEL_DIARY},
		2, new int[]{VarbitID.SLAYER_BLOCKED_MAZCHNA_1, VarbitID.SLAYER_BLOCKED_MAZCHNA_2,
			VarbitID.SLAYER_BLOCKED_MAZCHNA_3, VarbitID.SLAYER_BLOCKED_MAZCHNA_4,
			VarbitID.SLAYER_BLOCKED_MAZCHNA_5, VarbitID.SLAYER_BLOCKED_MAZCHNA_6,
			VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY},
		3, new int[]{VarbitID.SLAYER_BLOCKED_VANNAKA_1, VarbitID.SLAYER_BLOCKED_VANNAKA_2,
			VarbitID.SLAYER_BLOCKED_VANNAKA_3, VarbitID.SLAYER_BLOCKED_VANNAKA_4,
			VarbitID.SLAYER_BLOCKED_VANNAKA_5, VarbitID.SLAYER_BLOCKED_VANNAKA_6,
			VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY},
		4, new int[]{VarbitID.SLAYER_BLOCKED_CHAELDAR_1, VarbitID.SLAYER_BLOCKED_CHAELDAR_2,
			VarbitID.SLAYER_BLOCKED_CHAELDAR_3, VarbitID.SLAYER_BLOCKED_CHAELDAR_4,
			VarbitID.SLAYER_BLOCKED_CHAELDAR_5, VarbitID.SLAYER_BLOCKED_CHAELDAR_6,
			VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY},
		5, new int[]{VarbitID.SLAYER_BLOCKED_DURADEL_1, VarbitID.SLAYER_BLOCKED_DURADEL_2,
			VarbitID.SLAYER_BLOCKED_DURADEL_3, VarbitID.SLAYER_BLOCKED_DURADEL_4,
			VarbitID.SLAYER_BLOCKED_DURADEL_5, VarbitID.SLAYER_BLOCKED_DURADEL_6,
			VarbitID.SLAYER_BLOCKED_DURADEL_DIARY},
		6, new int[]{VarbitID.SLAYER_BLOCKED_NIEVE_1, VarbitID.SLAYER_BLOCKED_NIEVE_2,
			VarbitID.SLAYER_BLOCKED_NIEVE_3, VarbitID.SLAYER_BLOCKED_NIEVE_4,
			VarbitID.SLAYER_BLOCKED_NIEVE_5, VarbitID.SLAYER_BLOCKED_NIEVE_6,
			VarbitID.SLAYER_BLOCKED_NIEVE_DIARY},
		7, new int[]{VarbitID.SLAYER_BLOCKED_KRYSTILIA_1, VarbitID.SLAYER_BLOCKED_KRYSTILIA_2,
			VarbitID.SLAYER_BLOCKED_KRYSTILIA_3, VarbitID.SLAYER_BLOCKED_KRYSTILIA_4,
			VarbitID.SLAYER_BLOCKED_KRYSTILIA_5, VarbitID.SLAYER_BLOCKED_KRYSTILIA_6,
			VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY},
		8, new int[]{VarbitID.SLAYER_BLOCKED_KONAR_1, VarbitID.SLAYER_BLOCKED_KONAR_2,
			VarbitID.SLAYER_BLOCKED_KONAR_3, VarbitID.SLAYER_BLOCKED_KONAR_4,
			VarbitID.SLAYER_BLOCKED_KONAR_5, VarbitID.SLAYER_BLOCKED_KONAR_6,
			VarbitID.SLAYER_BLOCKED_KONAR_DIARY});

	private final AccountState state;
	private final Client client;
	private final ClientThread clientThread;
	private final IronHubConfig config;
	private final InfoBoxManager infoBoxManager;     // null in unit tests
	private final Provider<? extends Plugin> plugin; // Provider breaks the DI cycle
	private final EventBus eventBus;                 // null in unit tests
	private final NpcOverlayService npcOverlayService; // null in unit tests
	private final ItemManager itemManager;           // null in unit tests
	private final Notifier notifier;                 // null in unit tests

	private final SlayerTasksPack pack;
	private final Map<String, Integer> unlockVarbitIds = new LinkedHashMap<>(); // unlock key -> varbit id

	private final Runnable listener = this::onStateChanged;
	private SlayerTab tab;
	private SlayerInfoBox infoBox;

	// current task snapshot (client-thread writes, volatile reads anywhere)
	private volatile String taskName = "";
	private volatile String areaName = "";
	private volatile int resolvedCreature = -1;
	private volatile int resolvedBossId = -1;
	private volatile int resolvedArea = -1;

	// record bookkeeping — mutated on the client thread, iterated by the EDT tab
	private final List<PersistedState.SlayerTaskRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
	private int lastRemaining = -1;
	private int lastStreakSum = -1;
	private boolean recordsLoaded;
	private int recordsGeneration;

	// NPC highlighting (highlighter built in the ctor — it captures config)
	private final List<Pattern> targetPatterns = new ArrayList<>();
	private final Set<NPC> targets = ConcurrentHashMap.newKeySet();
	private final Function<NPC, HighlightedNpc> highlighter;

	// blocked task id -> name, resolved lazily on the client thread
	private final Map<Integer, String> taskNamesById = new ConcurrentHashMap<>();

	private final com.ironhub.integrations.ShortestPathBridge pathBridge; // null in unit tests
	private final net.runelite.client.ui.overlay.OverlayManager overlayManager; // null in unit tests
	private final net.runelite.client.chat.ChatMessageManager chatMessageManager; // null in unit tests
	private final com.ironhub.modules.farming.FarmBankLayout bankLayout; // null-service tolerant

	private SlayerSuiteOverlay overlay;
	private volatile long superiorSeenMs;
	/** "Show in bank" armed from the tab; the next bank opens lay the setup out. */
	private volatile boolean bankShow;
	private String lastAdvisedMaster;

	@Inject
	public SlayerOptimizerModule(AccountState state, Client client, ClientThread clientThread,
		IronHubConfig config, InfoBoxManager infoBoxManager, Provider<com.ironhub.IronHubPlugin> plugin,
		EventBus eventBus, NpcOverlayService npcOverlayService, ItemManager itemManager,
		Notifier notifier, DataPack dataPack, com.ironhub.integrations.ShortestPathBridge pathBridge,
		net.runelite.client.ui.overlay.OverlayManager overlayManager,
		net.runelite.client.chat.ChatMessageManager chatMessageManager,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager)
	{
		this.pathBridge = pathBridge;
		this.overlayManager = overlayManager;
		this.chatMessageManager = chatMessageManager;
		this.bankLayout = new com.ironhub.modules.farming.FarmBankLayout(
			"slayer", bankTagsService, tagManager, layoutManager, itemManager);
		this.state = state;
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
		this.eventBus = eventBus;
		this.npcOverlayService = npcOverlayService;
		this.itemManager = itemManager;
		this.notifier = notifier;
		this.highlighter = npc ->
			config.slayerHighlight() && targets.contains(npc)
				? HighlightedNpc.builder().npc(npc).highlightColor(HIGHLIGHT)
					.outline(true).render(n -> !n.isDead()).build()
				: null;
		this.pack = dataPack == null ? null
			: dataPack.load("slayer-tasks", SlayerTasksPack.class);
		if (pack != null)
		{
			for (SlayerTasksPack.Unlock unlock : pack.unlocks)
			{
				try
				{
					unlockVarbitIds.put(unlock.key, VarbitID.class.getField(unlock.varbit).getInt(null));
				}
				catch (ReflectiveOperationException e)
				{
					// SlayerTasksPackTest fails the build first; never crash live
					log.warn("unresolvable unlock varbit {}", unlock.varbit);
				}
			}
		}
	}

	@Override
	public String name()
	{
		return "Slayer";
	}

	@Override
	public boolean enabled()
	{
		return config.slayerOptimizer();
	}

	@Override
	public void startUp()
	{
		state.watchVarps(VarPlayerID.SLAYER_COUNT, VarPlayerID.SLAYER_TARGET,
			VarPlayerID.SLAYER_AREA, VarPlayerID.SLAYER_COUNT_ORIGINAL);
		List<Integer> varbits = new ArrayList<>(List.of(VarbitID.SLAYER_POINTS,
			VarbitID.SLAYER_TASKS_COMPLETED, VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED,
			VarbitID.SLAYER_TARGET_BOSSID, VarbitID.SLAYER_MASTER));
		for (int[] slots : BLOCK_VARBITS.values())
		{
			for (int id : slots)
			{
				varbits.add(id);
			}
		}
		varbits.addAll(unlockVarbitIds.values());
		state.watchVarbits(varbits.stream().mapToInt(Integer::intValue).toArray());
		state.addListener(listener);
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (npcOverlayService != null)
		{
			npcOverlayService.registerHighlighter(highlighter);
		}
		if (infoBoxManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new SlayerInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(infoBox);
		}
		if (overlayManager != null)
		{
			overlay = new SlayerSuiteOverlay(this, config, client);
			overlayManager.add(overlay);
		}
	}

	@Override
	public void shutDown()
	{
		state.removeListener(listener);
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (npcOverlayService != null)
		{
			npcOverlayService.unregisterHighlighter(highlighter);
		}
		targets.clear();
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (clientThread != null)
		{
			clientThread.invoke(bankLayout::clear);
		}
		bankShow = false;
		if (infoBox != null)
		{
			infoBoxManager.removeInfoBox(infoBox);
			infoBox = null;
		}
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new SlayerTab(state, this, config.osrsTheme());
		}
		return tab;
	}

	@Override
	public void onThemeChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	// ── reads for tab + infobox + overlay ─────────────────────────────

	SlayerTasksPack pack()
	{
		return pack;
	}

	int remaining()
	{
		return state.getVarp(VarPlayerID.SLAYER_COUNT);
	}

	int initialAmount()
	{
		return state.getVarp(VarPlayerID.SLAYER_COUNT_ORIGINAL);
	}

	int points()
	{
		return state.getVarbit(VarbitID.SLAYER_POINTS);
	}

	/** The streak that applies to the current master (Krystilia's is separate). */
	int streak()
	{
		return masterFocus() == 7
			? state.getVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED)
			: state.getVarbit(VarbitID.SLAYER_TASKS_COMPLETED);
	}

	int masterFocus()
	{
		return state.getVarbit(VarbitID.SLAYER_MASTER);
	}

	/** The assigning master's name, or "" when unknown. */
	String masterName()
	{
		if (pack == null)
		{
			return "";
		}
		SlayerTasksPack.Master master = pack.masterByFocus(masterFocus());
		return master == null ? "" : master.name;
	}

	String taskName()
	{
		return taskName;
	}

	/** The task's assigned area (Konar), or "" when none. */
	String areaName()
	{
		return areaName;
	}

	/** Task records, oldest first; the last may be active (end == 0). */
	List<PersistedState.SlayerTaskRecord> records()
	{
		ensureRecordsLoaded();
		return records;
	}

	/** The active record, or null when no task. */
	PersistedState.SlayerTaskRecord activeRecord()
	{
		ensureRecordsLoaded();
		if (!records.isEmpty())
		{
			PersistedState.SlayerTaskRecord last = records.get(records.size() - 1);
			if (last.end == 0)
			{
				return last;
			}
		}
		return null;
	}

	/** Blocked task ids for a master focus id (nonzero slots, diary last). */
	List<Integer> blockedTaskIds(int focusId)
	{
		int[] slots = BLOCK_VARBITS.get(focusId == 9 ? 1 : focusId); // Spria shares Turael
		if (slots == null)
		{
			return List.of();
		}
		List<Integer> out = new ArrayList<>();
		for (int varbit : slots)
		{
			int taskId = state.getVarbit(varbit);
			if (taskId > 0)
			{
				out.add(taskId);
			}
		}
		return out;
	}

	/** DBTable lookups already queued — one invoke + one rebuild per id,
	 *  not one per tab rebuild per id (2026-07-20 audit: opening Blocks
	 *  with several unresolved ids queued O(n^2) duplicate lookups). */
	private final Set<Integer> taskNameLookups = ConcurrentHashMap.newKeySet();

	/** Task name for a blocked task id, or null until resolved (client thread). */
	String taskNameById(int taskId)
	{
		String cached = taskNamesById.get(taskId);
		if (cached == null && client != null && clientThread != null
			&& taskNameLookups.add(taskId))
		{
			clientThread.invoke(() ->
			{
				String resolved = lookupTaskName(taskId);
				taskNameLookups.remove(taskId); // unresolved may retry later
				if (resolved != null)
				{
					taskNamesById.put(taskId, resolved);
					if (tab != null)
					{
						SwingUtilities.invokeLater(tab::rebuild);
					}
				}
			});
		}
		return cached;
	}

	/** Whether a point unlock's varbit is currently on. */
	boolean unlockOwned(SlayerTasksPack.Unlock unlock)
	{
		Integer id = unlockVarbitIds.get(unlock.key);
		return id != null && state.getVarbit(id) > 0;
	}

	// ── detection ─────────────────────────────────────────────────────

	private void ensureRecordsLoaded()
	{
		// reload on profile switch too — pushing profile A's cached records
		// into profile B overwrote B's whole slayer history (2026-07-20 audit)
		int generation = state.profileGeneration();
		if (!recordsLoaded || generation != recordsGeneration)
		{
			recordsLoaded = true;
			recordsGeneration = generation;
			records.clear();
			records.addAll(state.getSlayerRecords());
			lastRemaining = -1;
			lastStreakSum = -1;
		}
	}

	private void onStateChanged()
	{
		ensureRecordsLoaded();
		mirrorUnlockFlags();

		int creature = state.getVarp(VarPlayerID.SLAYER_TARGET);
		int bossId = state.getVarbit(VarbitID.SLAYER_TARGET_BOSSID);
		int area = state.getVarp(VarPlayerID.SLAYER_AREA);
		if (creature != resolvedCreature || bossId != resolvedBossId || area != resolvedArea)
		{
			resolvedCreature = creature;
			resolvedBossId = bossId;
			resolvedArea = area;
			if (creature <= 0)
			{
				applyResolvedTask("", "");
			}
			else if (client != null && clientThread != null)
			{
				clientThread.invoke(() -> applyResolvedTask(
					resolveTaskName(creature, bossId), resolveAreaName(area)));
			}
		}
		else
		{
			updateProgress();
		}
	}

	/** Adopt a resolved task name + area — the varp path lands here from the
	 *  client thread; headless tests call it directly. */
	void applyResolvedTask(String name, String area)
	{
		ensureRecordsLoaded();
		boolean changed = !name.equals(taskName);
		taskName = name;
		areaName = area == null ? "" : area;
		state.setSlayerTask(name); // shared: the loadout tab keys strategies off it

		PersistedState.SlayerTaskRecord active = activeRecord();
		if (active != null && !active.name.equalsIgnoreCase(name))
		{
			// the previous task ended without an observed completion
			// (points skip, or it changed while the plugin was off)
			active.end = System.currentTimeMillis();
			finishRecord(active);
			active = null;
		}
		if (active == null && !name.isEmpty())
		{
			PersistedState.SlayerTaskRecord record = new PersistedState.SlayerTaskRecord();
			record.name = name;
			record.master = masterName();
			record.assigned = initialAmount();
			record.start = System.currentTimeMillis();
			record.xpStart = state.getXp(Skill.SLAYER);
			records.add(record);
			lastRemaining = -1;
			pushRecords();
		}
		updateProgress();
		if (changed)
		{
			rebuildTargetPatterns();
			rebuildTargetList();
			if (npcOverlayService != null)
			{
				npcOverlayService.rebuild();
			}
			if (!name.isEmpty() && onSkipList() && config.slayerBlockAdvice())
			{
				chat("You always skip " + name + " — 30 pts at the rewards board");
			}
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		}
	}

	/** Kill counting, xp attribution and completion detection. */
	private void updateProgress()
	{
		PersistedState.SlayerTaskRecord active = activeRecord();
		int remaining = remaining();
		int streakSum = state.getVarbit(VarbitID.SLAYER_TASKS_COMPLETED)
			+ state.getVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);

		boolean dirty = false;
		if (active != null)
		{
			if (active.xpStart == 0)
			{
				int xp = state.getXp(Skill.SLAYER);
				if (xp > 0)
				{
					active.xpStart = xp; // stats had not landed at assignment
					dirty = true;
				}
			}
			if (active.assigned == 0 && initialAmount() > 0)
			{
				active.assigned = initialAmount();
				dirty = true;
			}
			if (active.master.isEmpty() && !masterName().isEmpty())
			{
				active.master = masterName();
				dirty = true;
			}
			int delta = lastRemaining - remaining;
			// a drop to exactly 0 bigger than a Dusk&Dawn double-kill is a
			// points skip or reset, never kills
			if (lastRemaining > 0 && delta > 0 && !(remaining == 0 && delta > 2))
			{
				active.killed += delta;
				if (active.xpStart > 0)
				{
					active.xpGained = Math.max(0, state.getXp(Skill.SLAYER) - active.xpStart);
				}
				dirty = true;
			}
			if (lastStreakSum >= 0 && streakSum > lastStreakSum)
			{
				active.completed = true;
				active.end = System.currentTimeMillis();
				finishRecord(active);
				dirty = false; // finishRecord already pushed
			}
		}
		lastRemaining = remaining;
		lastStreakSum = streakSum;
		if (dirty)
		{
			pushRecords();
		}
	}

	private void finishRecord(PersistedState.SlayerTaskRecord record)
	{
		if (record.xpStart > 0)
		{
			record.xpGained = Math.max(0, state.getXp(Skill.SLAYER) - record.xpStart);
		}
		pushRecords();
	}

	private void pushRecords()
	{
		state.setSlayerRecords(records);
	}

	/** Mirror lit unlock varbits into the graph's unlock:slayerreward_* flags. */
	private void mirrorUnlockFlags()
	{
		List<String> newlyOn = null;
		for (Map.Entry<String, Integer> e : unlockVarbitIds.entrySet())
		{
			if (state.getVarbit(e.getValue()) > 0 && !state.isUnlocked(e.getKey()))
			{
				if (newlyOn == null)
				{
					newlyOn = new ArrayList<>();
				}
				newlyOn.add(e.getKey());
			}
		}
		if (newlyOn != null)
		{
			state.setUnlockedBulk(newlyOn);
		}
	}

	// ── NPC highlighting (core Slayer plugin parity) ──────────────────

	private void rebuildTargetPatterns()
	{
		targetPatterns.clear();
		targetPatterns.addAll(targetPatterns(taskName,
			pack == null || pack.task(taskName) == null ? List.of() : pack.task(taskName).targets));
	}

	/** Core-parity patterns: every pack target name plus the de-pluralized
	 *  task name, each matched on word boundaries, case-insensitive. */
	static List<Pattern> targetPatterns(String taskName, List<String> targets)
	{
		List<Pattern> out = new ArrayList<>();
		if (taskName == null || taskName.isEmpty())
		{
			return out;
		}
		for (String target : targets)
		{
			out.add(targetNamePattern(target));
		}
		out.add(targetNamePattern(taskName.replaceAll("s$", "")));
		return out;
	}

	private static Pattern targetNamePattern(String targetName)
	{
		return Pattern.compile("(?:\\s|^)" + Pattern.quote(targetName) + "(?:\\s|$)",
			Pattern.CASE_INSENSITIVE);
	}

	/** Pure matcher for tests: NPC display name + menu actions -> on-task. */
	static boolean matchesTarget(List<Pattern> patterns, String npcName, String[] actions)
	{
		if (npcName == null || patterns.isEmpty())
		{
			return false;
		}
		String name = npcName.replace('\u00A0', ' ').toLowerCase();
		boolean attackable = false;
		if (actions != null)
		{
			for (String action : actions)
			{
				// Pick is for zygomite-fungi (core parity)
				if ("Attack".equals(action) || "Pick".equals(action))
				{
					attackable = true;
					break;
				}
			}
		}
		if (!attackable)
		{
			return false;
		}
		for (Pattern pattern : patterns)
		{
			Matcher m = pattern.matcher(name);
			if (m.find())
			{
				return true;
			}
		}
		return false;
	}

	private boolean isTarget(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null
			&& matchesTarget(targetPatterns, composition.getName(), composition.getActions());
	}

	private void rebuildTargetList()
	{
		targets.clear();
		if (client == null || targetPatterns.isEmpty())
		{
			return;
		}
		for (NPC npc : client.getNpcs())
		{
			if (isTarget(npc))
			{
				targets.add(npc);
			}
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (!targetPatterns.isEmpty() && isTarget(event.getNpc()))
		{
			targets.add(event.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		targets.remove(event.getNpc());
	}

	// ── loot + superior ───────────────────────────────────────────────

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		PersistedState.SlayerTaskRecord active = activeRecord();
		if (active == null || itemManager == null)
		{
			return;
		}
		NPCComposition composition = event.getNpc().getTransformedComposition();
		if (composition == null
			|| !matchesTarget(targetPatterns, composition.getName(), composition.getActions()))
		{
			return;
		}
		long value = 0;
		for (net.runelite.client.game.ItemStack stack : event.getItems())
		{
			value += (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
		}
		if (value > 0)
		{
			active.lootValue += value;
			pushRecords();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		if (SUPERIOR_MESSAGE.equals(Text.removeTags(event.getMessage())))
		{
			superiorSeenMs = System.currentTimeMillis();
			if (notifier != null && config.slayerSuperiorNotify())
			{
				notifier.notify(SUPERIOR_MESSAGE);
			}
		}
	}

	// ── master-visit block advice + bank setup layout ─────────────────

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick tick)
	{
		if (client == null || pack == null)
		{
			return;
		}
		net.runelite.api.widgets.Widget nameWidget =
			client.getWidget(net.runelite.api.gameval.InterfaceID.ChatLeft.NAME);
		String speaker = nameWidget == null ? null : Text.removeTags(nameWidget.getText());
		SlayerTasksPack.Master master = pack.masterByName(speaker);
		if (master == null)
		{
			lastAdvisedMaster = null; // next visit advises again
			return;
		}
		if (master.name.equals(lastAdvisedMaster))
		{
			return;
		}
		lastAdvisedMaster = master.name;
		if (config.slayerBlockAdvice())
		{
			String advice = blockAdvice(master);
			if (advice != null)
			{
				chat(advice);
			}
		}
	}

	/**
	 * "Block Cave kraken, Smoke devils here — 100 pts each" for preferred
	 * blocks not yet made at this master, or null. Stays silent while any
	 * live slot's task id is still unresolved — never advise on a guess.
	 */
	String blockAdvice(SlayerTasksPack.Master master)
	{
		List<String> prefs = state.getSlayerBlockPref(master.name);
		if (prefs.isEmpty())
		{
			return null;
		}
		List<String> liveLower = new ArrayList<>();
		for (Integer taskId : blockedTaskIds(master.focusId))
		{
			String name = taskNamesById.get(taskId);
			if (name == null)
			{
				taskNameById(taskId); // schedule resolution for next time
				return null;
			}
			liveLower.add(name.toLowerCase());
		}
		List<String> toBlock = new ArrayList<>();
		for (String pref : prefs)
		{
			if (!liveLower.contains(pref.toLowerCase()))
			{
				toBlock.add(pref);
			}
		}
		if (toBlock.isEmpty())
		{
			return null;
		}
		return "Block " + String.join(", ", toBlock) + " here — 100 pts each (you have "
			+ points() + ")";
	}

	private void chat(String message)
	{
		if (chatMessageManager == null)
		{
			return;
		}
		chatMessageManager.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
				.append(java.awt.Color.CYAN, "[Iron Hub] ")
				.append(java.awt.Color.WHITE, message)
				.build())
			.build());
	}

	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_INIT || !bankShow)
		{
			return;
		}
		PersistedState.SavedSetup setup = taskSetup();
		if (setup == null || clientThread == null)
		{
			return;
		}
		String name = taskName;
		// never open a bank tag during the bank's own build script — defer
		// (2026-07-18 bank-open freeze audit)
		clientThread.invokeLater(() -> bankLayout.apply("slayer " + name, setup));
	}

	@Subscribe
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_FINISHBUILDING
			|| client == null || !bankLayout.isApplied())
		{
			return;
		}
		net.runelite.api.widgets.Widget title =
			client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.TITLE);
		if (title != null)
		{
			title.setText("<col=ff981f>" + taskName + "</col> — slayer setup");
		}
	}

	/** The saved gear+inventory setup for the current task (Loadout key space). */
	PersistedState.SavedSetup taskSetup()
	{
		return taskName.isEmpty() ? null : state.savedSetup(taskName);
	}

	/** Save the player's current gear+inventory as this task's setup. */
	void saveTaskSetup()
	{
		if (!taskName.isEmpty())
		{
			state.saveSetup(taskName, state.captureSetup());
		}
	}

	boolean bankShowArmed()
	{
		return bankShow;
	}

	/** Arm/disarm the bank layout; disarming clears an applied layout. */
	void setBankShow(boolean show)
	{
		bankShow = show;
		if (!show && clientThread != null)
		{
			clientThread.invoke(bankLayout::clear);
		}
	}

	// ── overlay reads ─────────────────────────────────────────────────

	boolean superiorRecentlySeen()
	{
		return System.currentTimeMillis() - superiorSeenMs < 10_000;
	}

	/** Whether the current task is on its master's always-skip list. */
	boolean onSkipList()
	{
		if (taskName.isEmpty())
		{
			return false;
		}
		for (String task : state.getSlayerSkipPref(masterName()))
		{
			if (task.equalsIgnoreCase(taskName))
			{
				return true;
			}
		}
		return false;
	}

	/** Required bring items not currently carried (names). */
	List<String> missingBring()
	{
		SlayerTasksPack.Task entry = pack == null ? null : pack.task(taskName);
		if (entry == null || entry.bring == null)
		{
			return List.of();
		}
		List<String> missing = new ArrayList<>();
		for (SlayerTasksPack.BringItem item : entry.bring)
		{
			if (item.required && item.id != null && state.carriedCount(item.id) == 0)
			{
				missing.add(item.name);
			}
		}
		return missing;
	}

	/** The preferred location's name (or the first routable one), or null. */
	String preferredLocationName(SlayerTasksPack.Task entry)
	{
		String pref = state.getSlayerLocationPref(entry.name);
		if (pref != null)
		{
			return pref;
		}
		return entry.locations == null || entry.locations.isEmpty()
			? null : entry.locations.get(0).name;
	}

	/** True while the player stands inside any of the task's Turael kill areas. */
	boolean inTuraelArea()
	{
		SlayerTasksPack.Task entry = pack == null ? null : pack.task(taskName);
		if (entry == null || entry.turael == null || client == null
			|| client.getLocalPlayer() == null)
		{
			return false;
		}
		net.runelite.api.coords.WorldPoint at = client.getLocalPlayer().getWorldLocation();
		for (SlayerTasksPack.TuraelLocation location : entry.turael.locations)
		{
			for (List<Integer> area : location.areas)
			{
				if (area.size() >= 5 && at.getPlane() == area.get(4)
					&& at.getX() >= area.get(0) && at.getX() <= area.get(2)
					&& at.getY() >= area.get(1) && at.getY() <= area.get(3))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ── client-thread DBTable resolution ──────────────────────────────

	private String resolveTaskName(int creature, int bossId)
	{
		try
		{
			int taskRow;
			if (creature == CREATURE_BOSS)
			{
				var bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
					DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
				if (bossRows.isEmpty())
				{
					return "Boss task";
				}
				taskRow = (Integer) client.getDBTableField(bossRows.get(0),
					DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			}
			else
			{
				var rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID,
					DBTableID.SlayerTask.COL_ID, 0, creature);
				if (rows.isEmpty())
				{
					return "";
				}
				taskRow = rows.get(0);
			}
			return (String) client.getDBTableField(taskRow,
				DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		}
		catch (RuntimeException e)
		{
			log.warn("failed to resolve slayer task name", e);
			return "";
		}
	}

	private String resolveAreaName(int areaId)
	{
		if (areaId <= 0)
		{
			return "";
		}
		try
		{
			var rows = client.getDBRowsByValue(DBTableID.SlayerArea.ID,
				DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (rows.isEmpty())
			{
				return "";
			}
			return (String) client.getDBTableField(rows.get(0),
				DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
		}
		catch (RuntimeException e)
		{
			log.warn("failed to resolve slayer area name", e);
			return "";
		}
	}

	/** Task id -> catalog name via the SlayerTask DBTable. Client thread. */
	private String lookupTaskName(int taskId)
	{
		try
		{
			var rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID,
				DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (rows.isEmpty())
			{
				return null;
			}
			return (String) client.getDBTableField(rows.get(0),
				DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Test seam: pre-seed a blocked task id's display name. */
	void seedTaskName(int taskId, String name)
	{
		taskNamesById.put(taskId, name);
	}

	/** Route to a point via Shortest Path (inert when not installed). */
	void route(net.runelite.api.coords.WorldPoint point)
	{
		if (pathBridge != null && point != null)
		{
			pathBridge.pathTo(point);
		}
	}

	ItemManager itemManager()
	{
		return itemManager;
	}
}
