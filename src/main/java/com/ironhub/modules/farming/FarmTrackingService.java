package com.ironhub.modules.farming;

import com.ironhub.modules.farming.rl.CropState;
import com.ironhub.modules.farming.rl.FarmingContractManager;
import com.ironhub.modules.farming.rl.FarmingPatch;
import com.ironhub.modules.farming.rl.FarmingTracker;
import com.ironhub.modules.farming.rl.FarmingWorld;
import com.ironhub.modules.farming.rl.PatchPrediction;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.modules.farming.rl.hunter.BirdHouseTracker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;

/**
 * The farming module's read seam over the vendored Time Tracking engine
 * (rl/): patch predictions, per-category summaries, bird houses and the
 * farming contract — all computed from the data the core Time Tracking
 * plugin (enabled by default) persists under its "timetracking" config
 * group. Iron Hub never writes that group; when the core plugin is off
 * and no data exists, everything reads UNKNOWN and the UI says so.
 */
public class FarmTrackingService
{
	/** Display order for the patch overview: every category a standard
	 *  account can farm, most-run types first. */
	static final List<Tab> CATEGORIES = List.of(
		Tab.HERB, Tab.TREE, Tab.FRUIT_TREE, Tab.FLOWER, Tab.ALLOTMENT, Tab.HOPS,
		Tab.BUSH, Tab.SEAWEED, Tab.GRAPE, Tab.CACTUS, Tab.MUSHROOM, Tab.BELLADONNA,
		Tab.CALQUAT, Tab.CELASTRUS, Tab.HARDWOOD, Tab.REDWOOD, Tab.CRYSTAL,
		Tab.ANIMA, Tab.HESPORI, Tab.BIG_COMPOST);

	private final ConfigManager configManager;
	private final FarmingWorld farmingWorld = new FarmingWorld();
	private final FarmingTracker tracker;
	private final BirdHouseTracker birdHouseTracker;
	private final FarmingContractManager contractManager;

	public FarmTrackingService(Client client, ItemManager itemManager,
		ConfigManager configManager, TimeTrackingConfig timeTrackingConfig, Notifier notifier)
	{
		this.configManager = configManager;
		this.tracker = new FarmingTracker(configManager, farmingWorld);
		this.birdHouseTracker = new BirdHouseTracker(client, itemManager, configManager,
			timeTrackingConfig, notifier);
		this.contractManager = new FarmingContractManager(client, itemManager, configManager,
			timeTrackingConfig, farmingWorld, tracker);
	}

	/** Re-read everything from the core plugin's stored data. */
	public void refresh()
	{
		tracker.setPreferSoonest(Boolean.parseBoolean(configManager.getConfiguration(
			TimeTrackingConfig.CONFIG_GROUP, TimeTrackingConfig.PREFER_SOONEST)));
		tracker.loadCompletionTimes();
		birdHouseTracker.loadFromConfig();
		contractManager.loadContractFromConfig();
	}

	FarmingWorld world()
	{
		return farmingWorld;
	}

	FarmingTracker tracker()
	{
		return tracker;
	}

	// ── patch categories ──────────────────────────────────────────────

	SummaryState summary(Tab tab)
	{
		return tracker.getSummary(tab);
	}

	boolean harvestable(Tab tab)
	{
		return tracker.getHarvestable(tab);
	}

	/** Unix seconds when the category finishes; 0 = done, -1 = no data. */
	long completionTime(Tab tab)
	{
		return tracker.getCompletionTime(tab);
	}

	/** Predict one patch by its region + transmit varbit, or null. */
	PatchPrediction predict(int regionId, int varbitId)
	{
		for (Map.Entry<Tab, Set<FarmingPatch>> entry : tracker.getTabData())
		{
			for (FarmingPatch patch : entry.getValue())
			{
				if (patch.getRegion().getRegionID() == regionId && patch.getVarbit() == varbitId)
				{
					return tracker.predictPatch(patch);
				}
			}
		}
		return null;
	}

	/** Patches harvestable right now across the whole farming world. */
	int readyPatchCount()
	{
		long now = Instant.now().getEpochSecond();
		int ready = 0;
		for (Map.Entry<Tab, Set<FarmingPatch>> entry : tracker.getTabData())
		{
			for (FarmingPatch patch : entry.getValue())
			{
				if (isReady(tracker.predictPatch(patch), now))
				{
					ready++;
				}
			}
		}
		return ready;
	}

	/** A single patch's "harvest me now" reading. */
	static boolean isReady(PatchPrediction prediction, long nowSeconds)
	{
		if (prediction == null || prediction.getProduce() == Produce.WEEDS
			|| prediction.getProduce() == Produce.SCARECROW
			|| prediction.getProduce().getItemID() < 0)
		{
			return false;
		}
		CropState state = prediction.getCropState();
		if (state == CropState.HARVESTABLE)
		{
			return true;
		}
		return state == CropState.GROWING
			&& (prediction.getStage() == prediction.getStages() - 1
				|| (prediction.getDoneEstimate() > 0 && prediction.getDoneEstimate() <= nowSeconds));
	}

	// ── bird houses ───────────────────────────────────────────────────

	SummaryState birdHouseSummary()
	{
		return birdHouseTracker.getSummary();
	}

	/** Unix seconds when all bird houses are done; 0 = done, -1 = no data. */
	long birdHouseCompletionTime()
	{
		return birdHouseTracker.getCompletionTime();
	}

	// ── farming contract ──────────────────────────────────────────────

	FarmingContractManager contract()
	{
		return contractManager;
	}

	/** A contract is waiting on the player (plant it or turn it in) —
	 *  the reminder plugin's tri-state, gated on a contract existing. */
	boolean contractReady()
	{
		if (!contractManager.hasContract())
		{
			return false;
		}
		boolean inProgress = contractManager.getSummary() == SummaryState.IN_PROGRESS;
		boolean occupiedWrongCrop = contractManager.getSummary() == SummaryState.OCCUPIED
			&& contractManager.getContractCropState() == null;
		return !inProgress && !occupiedWrongCrop;
	}

	// ── data presence ─────────────────────────────────────────────────

	/** True when the core Time Tracking plugin has recorded anything for
	 *  this profile — the difference between "all empty" and "no tracker". */
	boolean hasAnyData()
	{
		if (birdHouseSummary() != SummaryState.UNKNOWN)
		{
			return true;
		}
		for (Tab tab : CATEGORIES)
		{
			if (summary(tab) != SummaryState.UNKNOWN)
			{
				return true;
			}
		}
		return false;
	}

	/** True when the user has explicitly disabled the core Time Tracking
	 *  plugin (our data source dries up until it's re-enabled). */
	boolean coreTrackingDisabled()
	{
		return "false".equals(configManager.getConfiguration("runelite", "timetrackingplugin"));
	}
}
