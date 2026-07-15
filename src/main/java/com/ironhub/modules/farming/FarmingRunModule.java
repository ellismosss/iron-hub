package com.ironhub.modules.farming;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.FarmRunsPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.farming.rl.PatchPrediction;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.state.AccountState;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Farming runs (DESIGN.md §3.8, frames 2e/3b): the full Time Tracking
 * behaviour — every patch category, bird houses and the farming contract
 * predicted from the core plugin's persisted data via the vendored engine
 * (rl/, see FarmTrackingService) — plus per-category ready infoboxes
 * (Time Tracking Reminder parity), guided runs that advance a stop once
 * its seed is planted (and, for herb/hops, composted), and persisted run
 * history.
 */
@Slf4j
@Singleton
public class FarmingRunModule implements IronHubModule
{
	/** Tracking data moves on farming ticks (minutes) — re-read slowly. */
	private static final int REFRESH_TICKS = 10;

	/** The built-in run templates, keyed by display name (pack categories
	 *  in the source plugin's route order). */
	static final java.util.LinkedHashMap<String, String> TEMPLATES = new java.util.LinkedHashMap<>(
		java.util.Map.of());

	static
	{
		TEMPLATES.put("Herb run", "herb");
		TEMPLATES.put("Tree run", "tree");
		TEMPLATES.put("Fruit tree run", "fruit");
		TEMPLATES.put("Hops run", "hops");
	}

	private final AccountState state;
	private final Client client;
	private final EventBus eventBus;
	private final OverlayManager overlayManager;   // null in unit tests
	private final InfoBoxManager infoBoxManager;   // null in unit tests
	private final Provider<? extends Plugin> plugin;
	private final IronHubConfig config;
	private final ShortestPathBridge pathBridge;
	private final DataPack dataPack;
	private final net.runelite.client.Notifier notifier; // null in unit tests
	private final ConfigManager configManager;     // null in unit tests
	private final ItemManager itemManager;         // null in unit tests
	private final net.runelite.client.callback.ClientThread clientThread; // null in unit tests
	private final net.runelite.client.plugins.banktags.BankTagsService bankTagsService; // null in tests
	private final net.runelite.client.plugins.banktags.TagManager tagManager; // null in tests
	private final net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager; // null in tests

	private FarmRunsPack pack;
	FarmTrackingService tracking; // package-private test seam
	private FarmBankLayout bankLayout;
	private FarmingTab tab;
	private FarmingRunOverlay overlay;
	private RunTimerInfoBox infoBox;
	private final java.util.List<FarmReadyInfoBox> readyBoxes = new java.util.ArrayList<>();

	// run state — written on the client thread, read from EDT/overlay
	private volatile long runStartMs;
	private volatile String runName = "";
	private volatile String runCategory = "";
	private volatile List<Stop> stops = List.of();
	private final Set<String> visited = ConcurrentHashMap.newKeySet();
	// per-run tracking, baselined at startRun
	private volatile int runStartFarmingXp;
	private volatile int runStartHerbCount;

	/** Grimy (farmable) herb ids — a harvest of any counts toward "herbs
	 *  this run". Stable ids from gameval UNIDENTIFIED_&lt;herb&gt;. */
	private static final Set<Integer> GRIMY_HERBS = Set.of(
		199, 201, 203, 205, 207, 209, 211, 213, 215, 217, 219, 2485, 3049, 3051, 30094);

	/** "You treat the herb patch with ultracompost." — the vendored
	 *  CompostTracker's pattern; a compost applied to any patch. */
	private static final java.util.regex.Pattern COMPOST_APPLIED = java.util.regex.Pattern.compile(
		"You treat the .+ with (?:ultra|super|)compost\\.");

	// tracking refresh (client thread)
	private int refreshTick;
	private volatile boolean trackingDirty = true;
	private String lastFingerprint = "";
	/** Cross-module read seam (WhatNow, Dashboard): patches ready now. */
	private static volatile int sharedReadyPatches;

	// notification bookkeeping (client thread): category -> already notified
	private final Set<String> notifiedReady = ConcurrentHashMap.newKeySet();
	private boolean firstRefresh = true;

	@Inject
	public FarmingRunModule(AccountState state, Client client, EventBus eventBus,
		OverlayManager overlayManager, InfoBoxManager infoBoxManager,
		Provider<com.ironhub.IronHubPlugin> plugin, IronHubConfig config,
		ShortestPathBridge pathBridge, DataPack dataPack, net.runelite.client.Notifier notifier,
		ConfigManager configManager, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager)
	{
		this.notifier = notifier;
		this.state = state;
		this.client = client;
		this.eventBus = eventBus;
		this.overlayManager = overlayManager;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
		this.config = config;
		this.pathBridge = pathBridge;
		this.dataPack = dataPack;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.bankTagsService = bankTagsService;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
	}

	@Override
	public String name()
	{
		return "Farming runs";
	}

	@Override
	public boolean enabled()
	{
		return config.farmingRuns();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("farm-runs", FarmRunsPack.class);
		if (tracking == null && configManager != null)
		{
			tracking = new FarmTrackingService(client, itemManager, configManager,
				configManager.getConfig(TimeTrackingConfig.class), notifier);
		}
		bankLayout = new FarmBankLayout(bankTagsService, tagManager, layoutManager, itemManager);
		eventBus.register(this);
		if (overlayManager != null)
		{
			overlay = new FarmingRunOverlay(this);
			overlayManager.add(overlay);
		}
		if (infoBoxManager != null && itemManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new RunTimerInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(infoBox);
			addReadyInfoBoxes();
		}
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		runStartMs = 0;
		sharedReadyPatches = 0;
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (infoBox != null)
		{
			infoBoxManager.removeInfoBox(infoBox);
			infoBox = null;
		}
		for (FarmReadyInfoBox box : readyBoxes)
		{
			infoBoxManager.removeInfoBox(box);
		}
		readyBoxes.clear();
		if (bankLayout != null)
		{
			bankLayout.clear(); // shutDown runs on the client thread
			bankLayout = null;
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
			tab = new FarmingTab(state, this, pathBridge);
		}
		return tab;
	}

	/** One render-gated infobox per patch category, plus bird houses and
	 *  the farming contract — visible only while that thing is ready. */
	private void addReadyInfoBoxes()
	{
		Plugin owner = plugin.get();
		for (Tab category : FarmTrackingService.CATEGORIES)
		{
			readyBoxes.add(new FarmReadyInfoBox(itemManager.getImage(category.getItemID()), owner,
				category.getName() + " ready",
				() -> config.farmingReadyInfoboxes() && tracking != null && tracking.harvestable(category)));
		}
		readyBoxes.add(new FarmReadyInfoBox(itemManager.getImage(Tab.BIRD_HOUSE.getItemID()), owner,
			"Bird houses ready",
			() -> config.farmingReadyInfoboxes() && tracking != null
				&& (tracking.birdHouseSummary() == SummaryState.COMPLETED
					|| tracking.birdHouseSummary() == SummaryState.EMPTY)));
		readyBoxes.add(new FarmReadyInfoBox(itemManager.getImage(ItemID.SEED_PACK), owner,
			"Farming contract ready",
			() -> config.farmingReadyInfoboxes() && tracking != null && tracking.contractReady()));
		for (FarmReadyInfoBox box : readyBoxes)
		{
			infoBoxManager.addInfoBox(box);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// the core Time Tracking plugin just recorded new data — re-read soon
		if (TimeTrackingConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			trackingDirty = true;
		}
	}

	/**
	 * The bank is (re)building — reorganise it into the active run's saved
	 * setup, or restore it if there's nothing to show. Client thread, and
	 * the only place the bank layout is applied so it stays in lockstep with
	 * the real bank rebuild.
	 */
	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_INIT || bankLayout == null)
		{
			return;
		}
		if (config.farmBankSetup() && running())
		{
			com.ironhub.state.PersistedState.SavedSetup setup = state.getFarmRunSetup(runName);
			if (setup != null)
			{
				bankLayout.apply(runName, setup);
				return;
			}
		}
		bankLayout.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (trackingDirty || ++refreshTick % REFRESH_TICKS == 0)
		{
			trackingDirty = false;
			refreshTracking();
		}
		// the patch may have finished growing/been planted since last check
		checkAdvance();
	}

	/**
	 * Compost applied to a patch — the final step of working a herb/hops
	 * patch. If it happened at the current stop, remember it (the stop
	 * advances once the seed is also planted, mirroring Easy Farming).
	 */
	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (!running() || (event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE
			&& event.getType() != net.runelite.api.ChatMessageType.SPAM))
		{
			return;
		}
		if (COMPOST_APPLIED.matcher(event.getMessage()).find())
		{
			onCompostApplied(playerRegion());
		}
	}

	/** -1 when the player isn't known (headless tests). */
	private int playerRegion()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return -1;
		}
		return client.getLocalPlayer().getWorldLocation().getRegionID();
	}

	/** Re-read the core plugin's data; notify fresh readiness; repaint the
	 *  tab only when the picture actually changed. Client thread. */
	void refreshTracking()
	{
		if (tracking == null)
		{
			return;
		}
		tracking.refresh();
		sharedReadyPatches = tracking.readyPatchCount();
		notifyTransitions();
		checkAdvance(); // the current stop's patch may now read as planted

		String fingerprint = fingerprint();
		if (!fingerprint.equals(lastFingerprint))
		{
			lastFingerprint = fingerprint;
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		}
	}

	/** One notification per category per readiness transition; re-arms when
	 *  the category stops being ready (harvested/replanted). The first
	 *  refresh seeds silently so login never replays old readiness. */
	private void notifyTransitions()
	{
		for (Tab category : FarmTrackingService.CATEGORIES)
		{
			boolean ready = tracking.harvestable(category);
			if (ready && notifiedReady.add(category.name()))
			{
				if (!firstRefresh && notifier != null && config.notifyPatchReady())
				{
					notifier.notify(category.getName() + " ready to harvest.");
				}
			}
			else if (!ready)
			{
				notifiedReady.remove(category.name());
			}
		}
		firstRefresh = false;
	}

	private String fingerprint()
	{
		StringBuilder sb = new StringBuilder();
		for (Tab category : FarmTrackingService.CATEGORIES)
		{
			sb.append(tracking.summary(category)).append(':')
				.append(tracking.completionTime(category)).append(':')
				.append(tracking.harvestable(category)).append(';');
		}
		sb.append(tracking.birdHouseSummary()).append(':')
			.append(tracking.birdHouseCompletionTime()).append(';')
			.append(tracking.contract().getContractName()).append(':')
			.append(tracking.contract().getSummary());
		return sb.toString();
	}

	/** One resolved stop of the active run: the location plus the best
	 *  teleport the player can actually use (Easy Farming improved — no
	 *  per-location config forest, ownership picks the teleport). */
	static class Stop
	{
		final FarmRunsPack.Location location;
		final FarmRunsPack.Teleport teleport;

		Stop(FarmRunsPack.Location location, FarmRunsPack.Teleport teleport)
		{
			this.location = location;
			this.teleport = teleport;
		}
	}

	/** Compost applied at the current stop (herb/hops) — reset when the stop
	 *  advances, like Easy Farming's per-location composted flag. */
	private volatile boolean compostedHere;

	/** Categories where the run waits for compost before advancing; trees
	 *  and fruit trees take no compost (the farmer is paid instead). */
	private static boolean compostable(String category)
	{
		return "herb".equals(category) || "hops".equals(category);
	}

	/** Record a compost at the current stop (region-attributed; -1 = unknown,
	 *  attributed to the current stop). Advances if the seed is also planted. */
	void onCompostApplied(int playerRegion)
	{
		Stop next = nextStop();
		if (next == null)
		{
			return;
		}
		if (playerRegion == -1 || playerRegion == next.location.worldPoint().getRegionID())
		{
			compostedHere = true;
			checkAdvance();
		}
	}

	/**
	 * Advance the current stop once the player has done the work there —
	 * the patch is planted (growing) and, for herb/hops, composted. Not on
	 * arrival. Ends the run when the last stop is done. Client thread.
	 */
	void checkAdvance()
	{
		if (!running())
		{
			return;
		}
		Stop next = nextStop();
		if (next == null || !plantedAt(next))
		{
			return;
		}
		if (compostable(runCategory) && !compostedHere)
		{
			return; // planted but not yet composted — stay put
		}
		advanceCurrentStop();
	}

	/** True when this stop's patch (the run's category) is freshly planted
	 *  (growing) — the seed is in the ground. */
	private boolean plantedAt(Stop stop)
	{
		Tab category = runCategoryTab();
		if (category == null)
		{
			return false;
		}
		for (StopPatch patch : patchesAt(stop.location))
		{
			if (patch.category == category)
			{
				return patch.view == PatchView.GROWING || patch.view == PatchView.PREDICTED_READY;
			}
		}
		return false;
	}

	private void advanceCurrentStop()
	{
		Stop next = nextStop();
		if (next == null)
		{
			return;
		}
		visited.add(next.location.id);
		compostedHere = false;
		if (visited.size() == stops.size())
		{
			endRun(true);
		}
		else
		{
			routeToNext(); // keep the path bridge on the true next stop
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		}
	}

	/**
	 * Manual advance from the sidebar: mark this stop and every stop before
	 * it done ("I'm past here"). The escape hatch for a patch you skip or
	 * don't compost.
	 */
	void markThrough(String locationId)
	{
		boolean changed = false;
		for (Stop stop : stops)
		{
			changed |= visited.add(stop.location.id);
			if (stop.location.id.equals(locationId))
			{
				break;
			}
		}
		if (changed)
		{
			compostedHere = false;
			if (visited.size() == stops.size())
			{
				endRun(true);
			}
			else
			{
				routeToNext();
			}
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		}
	}

	// ── run control + reads ───────────────────────────────────────────

	/** Start a run over the given pack locations (a template category or a
	 *  custom run's resolved ids), auto-picking each stop's teleport. */
	void startRun(String name, List<FarmRunsPack.Location> locations)
	{
		List<Stop> resolved = new java.util.ArrayList<>();
		for (FarmRunsPack.Location location : locations)
		{
			if (isUnlocked(location)) // locked patches never enter a run
			{
				resolved.add(new Stop(location, pickTeleport(location)));
			}
		}
		stops = List.copyOf(resolved);
		runName = name;
		runCategory = locations.isEmpty() ? "" : locations.get(0).category;
		visited.clear();
		compostedHere = false;
		runStartFarmingXp = state.getXp(net.runelite.api.Skill.FARMING);
		runStartHerbCount = currentHerbCount();
		runStartMs = System.currentTimeMillis();
		routeToNext();
	}

	/** Farming xp gained since this run started. */
	int farmingXpGained()
	{
		return Math.max(0, state.getXp(net.runelite.api.Skill.FARMING) - runStartFarmingXp);
	}

	/** Grimy herbs picked up since this run started (approx: current carried
	 *  grimy count minus the start baseline). */
	int herbsHarvested()
	{
		return Math.max(0, currentHerbCount() - runStartHerbCount);
	}

	private int currentHerbCount()
	{
		int total = 0;
		for (java.util.Map.Entry<Integer, Integer> slot : state.getInventorySnapshot().entrySet())
		{
			if (GRIMY_HERBS.contains(slot.getKey()))
			{
				total += slot.getValue();
			}
		}
		return total;
	}

	/** The Tab (patch category) this run is farming, or null. */
	Tab runCategoryTab()
	{
		switch (runCategory)
		{
			case "herb":
				return Tab.HERB;
			case "tree":
				return Tab.TREE;
			case "fruit":
				return Tab.FRUIT_TREE;
			case "hops":
				return Tab.HOPS;
			default:
				return null;
		}
	}

	/** Start a built-in template run ("Herb run", ...). */
	void startTemplate(String name)
	{
		startRun(name, pack.category(TEMPLATES.get(name)));
	}

	/** Start a saved custom run; unknown location ids are skipped. */
	void startCustom(String name)
	{
		com.ironhub.state.PersistedState.FarmRun run = state.getFarmRuns().get(name);
		if (run == null)
		{
			return;
		}
		List<FarmRunsPack.Location> locations = new java.util.ArrayList<>();
		for (String id : run.locationIds)
		{
			FarmRunsPack.Location location = pack.location(id);
			if (location != null)
			{
				locations.add(location);
			}
		}
		startRun(name, locations);
	}

	void endRun(boolean complete)
	{
		boolean wasRunning = running();
		if (wasRunning && complete)
		{
			state.recordHerbRun(System.currentTimeMillis() - runStartMs);
		}
		runStartMs = 0;
		runName = "";
		runCategory = "";
		stops = List.of();
		// The run just ended — if the bank is open on the setup view, restore
		// it now (on the client thread; the next bank open would also do it).
		if (wasRunning && bankLayout != null && bankLayout.isApplied() && clientThread != null)
		{
			clientThread.invoke(bankLayout::clear);
		}
		// Auto-complete lands here off the tab's own button; refresh the
		// sidebar so it isn't stale. (recordHerbRun already notifies a
		// completed run; the tab's End run button rebuilds itself.)
		if (wasRunning && !complete && tab != null)
		{
			SwingUtilities.invokeLater(tab::rebuild);
		}
	}

	boolean running()
	{
		return runStartMs > 0;
	}

	long elapsedMs()
	{
		return running() ? System.currentTimeMillis() - runStartMs : 0;
	}

	String runName()
	{
		return runName;
	}

	boolean isVisited(String locationId)
	{
		return visited.contains(locationId);
	}

	int visitedCount()
	{
		return visited.size();
	}

	/** First unvisited stop in route order, or null. */
	Stop nextStop()
	{
		return stops.stream().filter(s -> !visited.contains(s.location.id)).findFirst().orElse(null);
	}

	List<Stop> stops()
	{
		return stops;
	}

	FarmRunsPack pack()
	{
		return pack;
	}

	/** True when the player can access this location's patch (all its
	 *  requirement-graph gates met). Empty reqs = always open. */
	boolean isUnlocked(FarmRunsPack.Location location)
	{
		if (location.reqs == null)
		{
			return true;
		}
		for (String req : location.reqs)
		{
			if (!com.ironhub.requirements.Requirements.parse(req).isMet(state))
			{
				return false;
			}
		}
		return true;
	}

	/** The locations of a run/category the player can actually access. */
	List<FarmRunsPack.Location> unlockedLocations(List<FarmRunsPack.Location> locations)
	{
		List<FarmRunsPack.Location> out = new java.util.ArrayList<>();
		for (FarmRunsPack.Location location : locations)
		{
			if (isUnlocked(location))
			{
				out.add(location);
			}
		}
		return out;
	}

	AccountState state()
	{
		return state;
	}

	FarmTrackingService tracking()
	{
		return tracking;
	}

	/** Item display name for overlay copy (client thread), or a fallback. */
	String itemName(int itemId)
	{
		if (itemManager == null)
		{
			return "item " + itemId;
		}
		return itemManager.getItemComposition(itemId).getName();
	}

	/** Send the path bridge toward the next unvisited stop (no-op when the
	 *  integration is off or the run is done). */
	private void routeToNext()
	{
		Stop next = nextStop();
		if (next != null && pathBridge != null)
		{
			pathBridge.pathTo(next.location.worldPoint());
		}
	}

	// ── teleport selection (ownership beats configuration) ───────────

	/**
	 * The best teleport the player can actually use, in the pack's order:
	 * first one whose items are all owned (bank counts — you can withdraw
	 * before the run), then access-based options (house portal, fairy
	 * ring), then the walk-there fallback.
	 */
	FarmRunsPack.Teleport pickTeleport(FarmRunsPack.Location location)
	{
		// the player's explicit choice wins (Easy Farming-style per-location
		// preference) — their call, even if the items aren't in hand yet
		String preferred = state.getFarmTeleportPref(location.id);
		if (preferred != null)
		{
			for (FarmRunsPack.Teleport teleport : location.teleports)
			{
				if (teleport.id.equals(preferred))
				{
					return teleport;
				}
			}
		}
		for (FarmRunsPack.Teleport teleport : location.teleports)
		{
			if (teleport.supplier == null && !teleport.items.isEmpty() && ownsAll(teleport))
			{
				return teleport;
			}
		}
		for (FarmRunsPack.Teleport teleport : location.teleports)
		{
			if (teleport.supplier != null)
			{
				return teleport;
			}
		}
		return location.teleports.get(location.teleports.size() - 1);
	}

	private boolean ownsAll(FarmRunsPack.Teleport teleport)
	{
		for (FarmRunsPack.Item item : teleport.items)
		{
			// canonical: any charge/variant of a glory or ring counts
			if (state.canonicalStock(item.itemId) < item.qty)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Items the player is NOT carrying for this stop's teleport (inventory
	 * + worn only — the run is live, the bank is behind you).
	 * ponytail: runes inside a rune pouch read as missing; watching the
	 * RUNE_POUCH varbits would fix that if it grates.
	 */
	List<FarmRunsPack.Item> missingItems(Stop stop)
	{
		List<FarmRunsPack.Item> missing = new java.util.ArrayList<>();
		for (FarmRunsPack.Item item : stop.teleport.items)
		{
			if (carriedCount(item.itemId) < item.qty)
			{
				missing.add(item);
			}
		}
		return missing;
	}

	/** How many of an item the player currently carries (inventory + worn),
	 *  counting every ItemVariationMapping variant — a charged glory counts
	 *  for the setup's glory slot. Package-visible for the bank overlay. */
	int carriedCount(int itemId)
	{
		int base = net.runelite.client.game.ItemVariationMapping.map(itemId);
		java.util.Set<Integer> variants = new java.util.HashSet<>(
			net.runelite.client.game.ItemVariationMapping.getVariations(base));
		int total = 0;
		for (java.util.Map.Entry<Integer, Integer> slot : state.getInventorySnapshot().entrySet())
		{
			if (variants.contains(slot.getKey()))
			{
				total += slot.getValue();
			}
		}
		for (int worn : state.getEquipmentSlots())
		{
			if (variants.contains(worn))
			{
				total++;
			}
		}
		return total;
	}

	// ── patch views over the vendored predictions ─────────────────────

	/** Display states for one patch — the tab/overlay vocabulary. */
	enum PatchView
	{
		READY,
		PREDICTED_READY,
		GROWING,
		DISEASED,
		DEAD,
		EMPTY,
		UNKNOWN
	}

	/** The farming patches at a stop with their live views: every vendored
	 *  world patch in the stop's region. */
	List<StopPatch> patchesAt(FarmRunsPack.Location location)
	{
		List<StopPatch> out = new java.util.ArrayList<>();
		if (tracking == null)
		{
			return out;
		}
		int region = location.worldPoint().getRegionID();
		long now = Instant.now().getEpochSecond();
		for (java.util.Map.Entry<Tab, java.util.Set<com.ironhub.modules.farming.rl.FarmingPatch>> entry
			: tracking.tracker().getTabData())
		{
			for (com.ironhub.modules.farming.rl.FarmingPatch patch : entry.getValue())
			{
				if (patch.getRegion().getRegionID() == region)
				{
					PatchPrediction prediction = tracking.tracker().predictPatch(patch);
					out.add(new StopPatch(entry.getKey(), viewOf(prediction, now)));
				}
			}
		}
		out.sort(java.util.Comparator.comparing(sp -> sp.category.ordinal()));
		return out;
	}

	/** One patch at a run stop: its category and live view. */
	static class StopPatch
	{
		final Tab category;
		final PatchView view;

		StopPatch(Tab category, PatchView view)
		{
			this.category = category;
			this.view = view;
		}
	}

	/** Map a vendored prediction onto the display vocabulary — static for
	 *  tests. PREDICTED_READY = growing but past its computed final tick. */
	static PatchView viewOf(PatchPrediction prediction, long nowSeconds)
	{
		if (prediction == null)
		{
			return PatchView.UNKNOWN;
		}
		if (prediction.getProduce() == Produce.WEEDS || prediction.getProduce().getItemID() < 0)
		{
			return PatchView.EMPTY;
		}
		switch (prediction.getCropState())
		{
			case HARVESTABLE:
				return PatchView.READY;
			case DISEASED:
				return PatchView.DISEASED;
			case DEAD:
				return PatchView.DEAD;
			case EMPTY:
				return PatchView.EMPTY;
			case GROWING:
				if (prediction.getStage() == prediction.getStages() - 1
					|| (prediction.getDoneEstimate() > 0 && prediction.getDoneEstimate() <= nowSeconds))
				{
					return PatchView.PREDICTED_READY;
				}
				return PatchView.GROWING;
			default:
				return PatchView.UNKNOWN;
		}
	}

	/** Patches harvestable across the whole farming world right now —
	 *  published by the last refresh for WhatNow and the dashboard. */
	public static int sharedReadyPatches()
	{
		return sharedReadyPatches;
	}

	/** Test seam (TimetrackingFixture): publish without a live refresh. */
	static void publishSharedReadyPatches(int count)
	{
		sharedReadyPatches = count;
	}

	/** "5:40" from millis — static for testing. */
	static String formatDuration(long ms)
	{
		long totalSeconds = ms / 1000;
		return totalSeconds / 60 + ":" + String.format("%02d", totalSeconds % 60);
	}

	/** "avg 5:40 · best 4:55 · 62 runs logged" or a hint when empty. */
	static String statsLine(List<Long> runsMs)
	{
		if (runsMs.isEmpty())
		{
			return "no runs logged yet";
		}
		long best = runsMs.stream().mapToLong(Long::longValue).min().orElse(0);
		long avg = (long) runsMs.stream().mapToLong(Long::longValue).average().orElse(0);
		return "avg " + formatDuration(avg) + " · best " + formatDuration(best)
			+ " · " + runsMs.size() + (runsMs.size() == 1 ? " run logged" : " runs logged");
	}
}
