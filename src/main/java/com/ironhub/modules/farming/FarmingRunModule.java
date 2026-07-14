package com.ironhub.modules.farming;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.awt.image.BufferedImage;
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
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Farming runs (DESIGN.md §3.8, frames 2e/3b): run timer with per-patch
 * checklist, patch-visit detection by proximity, and a persisted run
 * history (avg/best/count). Crop-stage varbit tracking (ready/diseased)
 * arrives incrementally per the risk register.
 */
@Slf4j
@Singleton
public class FarmingRunModule implements IronHubModule
{
	/** A patch counts as visited within this many tiles. */
	static final int VISIT_RADIUS = 12;

	private final AccountState state;
	private final Client client;
	private final EventBus eventBus;
	private final OverlayManager overlayManager;   // null in unit tests
	private final InfoBoxManager infoBoxManager;   // null in unit tests
	private final Provider<? extends Plugin> plugin;
	private final IronHubConfig config;
	private final ShortestPathBridge pathBridge;
	private final DataPack dataPack;

	private HerbPatchesPack pack;
	private FarmingTab tab;
	private FarmingRunOverlay overlay;
	private RunTimerInfoBox infoBox;

	// run state — written on the client thread, read from EDT/overlay
	private volatile long runStartMs;
	private final Set<String> visited = ConcurrentHashMap.newKeySet();

	@Inject
	public FarmingRunModule(AccountState state, Client client, EventBus eventBus,
		OverlayManager overlayManager, InfoBoxManager infoBoxManager,
		Provider<com.ironhub.IronHubPlugin> plugin, IronHubConfig config,
		ShortestPathBridge pathBridge, DataPack dataPack)
	{
		this.state = state;
		this.client = client;
		this.eventBus = eventBus;
		this.overlayManager = overlayManager;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
		this.config = config;
		this.pathBridge = pathBridge;
		this.dataPack = dataPack;
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
		pack = dataPack.load("herb-patches", HerbPatchesPack.class);
		eventBus.register(this);
		if (overlayManager != null)
		{
			overlay = new FarmingRunOverlay(this);
			overlayManager.add(overlay);
		}
		if (infoBoxManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new RunTimerInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(infoBox);
		}
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		runStartMs = 0;
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

	@Subscribe
	public void onGameTick(GameTick event)
	{
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

	/** Mark any patch within range as visited; ends the run when all done. */
	boolean markVisited(WorldPoint playerLocation)
	{
		boolean changed = false;
		for (HerbPatchesPack.Patch patch : patches())
		{
			if (!visited.contains(patch.getId())
				&& patch.getLocation().getPlane() == playerLocation.getPlane()
				&& patch.getLocation().distanceTo2D(playerLocation) <= VISIT_RADIUS)
			{
				visited.add(patch.getId());
				changed = true;
			}
		}
		if (changed && visited.size() == patches().size())
		{
			endRun(true);
		}
		return changed;
	}

	// ── run control + reads ───────────────────────────────────────────

	void startRun()
	{
		visited.clear();
		runStartMs = System.currentTimeMillis();
	}

	void endRun(boolean complete)
	{
		if (running() && complete)
		{
			state.recordHerbRun(System.currentTimeMillis() - runStartMs);
		}
		runStartMs = 0;
	}

	boolean running()
	{
		return runStartMs > 0;
	}

	long elapsedMs()
	{
		return running() ? System.currentTimeMillis() - runStartMs : 0;
	}

	boolean isVisited(String patchId)
	{
		return visited.contains(patchId);
	}

	int visitedCount()
	{
		return visited.size();
	}

	/** First unvisited patch in route order, or null. */
	HerbPatchesPack.Patch nextPatch()
	{
		return patches().stream().filter(p -> !visited.contains(p.getId())).findFirst().orElse(null);
	}

	List<HerbPatchesPack.Patch> patches()
	{
		return pack.getPatches();
	}

	AccountState state()
	{
		return state;
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
