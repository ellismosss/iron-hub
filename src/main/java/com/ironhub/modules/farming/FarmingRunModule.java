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
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
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
 * (Time Tracking Reminder parity), the run timer with its proximity
 * checklist, and persisted run history.
 */
@Slf4j
@Singleton
public class FarmingRunModule implements IronHubModule
{
	/** A patch counts as visited within this many tiles. */
	static final int VISIT_RADIUS = 12;

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

	private FarmRunsPack pack;
	FarmTrackingService tracking; // package-private test seam
	private FarmingTab tab;
	private FarmingRunOverlay overlay;
	private FarmSetupOverlay setupOverlay;
	private RunTimerInfoBox infoBox;
	private final java.util.List<FarmReadyInfoBox> readyBoxes = new java.util.ArrayList<>();

	// run state — written on the client thread, read from EDT/overlay
	private volatile long runStartMs;
	private volatile String runName = "";
	private volatile List<Stop> stops = List.of();
	private final Set<String> visited = ConcurrentHashMap.newKeySet();

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
		ConfigManager configManager, ItemManager itemManager)
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
		eventBus.register(this);
		if (overlayManager != null)
		{
			overlay = new FarmingRunOverlay(this);
			overlayManager.add(overlay);
			setupOverlay = new FarmSetupOverlay(this, state, config, itemManager, client);
			overlayManager.add(setupOverlay);
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
		if (setupOverlay != null)
		{
			overlayManager.remove(setupOverlay);
			setupOverlay = null;
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

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (trackingDirty || ++refreshTick % REFRESH_TICKS == 0)
		{
			trackingDirty = false;
			refreshTracking();
		}
		if (!running() || client == null)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		if (markVisited(player.getWorldLocation()) && tab != null)
		{
			SwingUtilities.invokeLater(tab::rebuild);
		}
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

	/** Mark any stop within range as visited; ends the run when all done
	 *  and routes the path bridge to the next stop otherwise. */
	boolean markVisited(WorldPoint playerLocation)
	{
		boolean changed = false;
		for (Stop stop : stops)
		{
			WorldPoint point = stop.location.worldPoint();
			if (!visited.contains(stop.location.id)
				&& point.getPlane() == playerLocation.getPlane()
				&& point.distanceTo2D(playerLocation) <= VISIT_RADIUS)
			{
				visited.add(stop.location.id);
				changed = true;
			}
		}
		if (changed)
		{
			if (visited.size() == stops.size())
			{
				endRun(true);
			}
			else
			{
				routeToNext();
			}
		}
		return changed;
	}

	// ── run control + reads ───────────────────────────────────────────

	/** Start a run over the given pack locations (a template category or a
	 *  custom run's resolved ids), auto-picking each stop's teleport. */
	void startRun(String name, List<FarmRunsPack.Location> locations)
	{
		List<Stop> resolved = new java.util.ArrayList<>();
		for (FarmRunsPack.Location location : locations)
		{
			resolved.add(new Stop(location, pickTeleport(location)));
		}
		stops = List.copyOf(resolved);
		runName = name;
		visited.clear();
		runStartMs = System.currentTimeMillis();
		routeToNext();
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
		stops = List.of();
		// The overlay's right-click end and the auto-complete both land here
		// off the tab's own button; refresh the sidebar so it isn't stale.
		// (recordHerbRun already notifies for a completed run.)
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
