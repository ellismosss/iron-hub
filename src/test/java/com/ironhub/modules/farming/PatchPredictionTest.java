package com.ironhub.modules.farming;

import com.ironhub.modules.farming.rl.CropState;
import com.ironhub.modules.farming.rl.PatchImplementation;
import com.ironhub.modules.farming.rl.PatchPrediction;
import com.ironhub.modules.farming.rl.PatchState;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.modules.farming.rl.hunter.BirdHouseSpace;
import java.time.Instant;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.SummaryState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Predictions over the vendored engine against data stored exactly the way
 * the core Time Tracking plugin persists it — plus the display mapping
 * (PatchView) and the ready semantics the infoboxes key on.
 */
public class PatchPredictionTest
{
	private static final int FALADOR_REGION = 12083;

	/** Sweep the vendored decoder for a value in the wanted state — the
	 *  tests never hardcode varbit magic. */
	private static int herbValue(Produce produce, CropState cropState, int stage)
	{
		for (int value = 0; value < 256; value++)
		{
			PatchState state = PatchImplementation.HERB.forVarbitValue(value);
			if (state != null && state.getProduce() == produce
				&& state.getCropState() == cropState && state.getStage() == stage)
			{
				return value;
			}
		}
		throw new IllegalStateException("no herb varbit value for " + produce + "/" + cropState + "/" + stage);
	}

	@Test
	public void storedHarvestableRanarrReadsReady()
	{
		ConfigManager configManager = TimetrackingFixture.configManager();
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		FarmTrackingService service = TimetrackingFixture.service(configManager);
		service.refresh();

		assertEquals(SummaryState.COMPLETED, service.summary(com.ironhub.modules.farming.rl.Tab.HERB));
		assertTrue(service.harvestable(com.ironhub.modules.farming.rl.Tab.HERB));
		assertEquals(1, service.readyPatchCount());

		PatchPrediction prediction = service.predict(FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D);
		assertEquals(Produce.RANARR, prediction.getProduce());
		assertEquals(FarmingRunModule.PatchView.READY,
			FarmingRunModule.viewOf(prediction, Instant.now().getEpochSecond()));
	}

	@Test
	public void aGrowingCropPredictsForwardAcrossFarmingTicks()
	{
		// Ranarr planted at stage 0, observed 3 herb ticks (3 x 20 min) ago:
		// the prediction must advance the stage without any new observation.
		ConfigManager configManager = TimetrackingFixture.configManager();
		long observed = Instant.now().getEpochSecond() - 3 * 20 * 60;
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), observed);
		FarmTrackingService service = TimetrackingFixture.service(configManager);
		service.refresh();

		PatchPrediction prediction = service.predict(FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D);
		assertEquals(CropState.GROWING, prediction.getCropState());
		assertTrue("stage should advance ~3 ticks, got " + prediction.getStage(),
			prediction.getStage() >= 2);
		assertTrue(prediction.getDoneEstimate() > observed);

		// still growing: not ready, summary in progress with a finite ETA
		if (prediction.getStage() < prediction.getStages() - 1)
		{
			assertFalse(FarmTrackingService.isReady(prediction, Instant.now().getEpochSecond()));
			assertEquals(SummaryState.IN_PROGRESS,
				service.summary(com.ironhub.modules.farming.rl.Tab.HERB));
		}
	}

	@Test
	public void weedsReadEmptyAndNeverReady()
	{
		ConfigManager configManager = TimetrackingFixture.configManager();
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			0, Instant.now().getEpochSecond()); // value 0 = full weeds
		FarmTrackingService service = TimetrackingFixture.service(configManager);
		service.refresh();

		PatchPrediction prediction = service.predict(FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D);
		assertEquals(Produce.WEEDS, prediction.getProduce());
		assertFalse(FarmTrackingService.isReady(prediction, Instant.now().getEpochSecond()));
		assertEquals(FarmingRunModule.PatchView.EMPTY,
			FarmingRunModule.viewOf(prediction, Instant.now().getEpochSecond()));
		assertEquals(SummaryState.EMPTY, service.summary(com.ironhub.modules.farming.rl.Tab.HERB));
		assertEquals(0, service.readyPatchCount());
	}

	@Test
	public void unknownPatchesStayHonestlyUnknown()
	{
		FarmTrackingService service = TimetrackingFixture.service(TimetrackingFixture.configManager());
		service.refresh();
		assertNull(service.predict(FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D));
		assertEquals(SummaryState.UNKNOWN, service.summary(com.ironhub.modules.farming.rl.Tab.HERB));
		assertFalse(service.hasAnyData());
		assertEquals(FarmingRunModule.PatchView.UNKNOWN, FarmingRunModule.viewOf(null, 0));
	}

	@Test
	public void birdHousesTrackTheirFiftyMinuteWindow()
	{
		int varp = BirdHouseSpace.MEADOW_NORTH.getVarp();
		long now = Instant.now().getEpochSecond();

		// seeded 10 minutes ago: still in progress, completing at +50 min
		ConfigManager fresh = TimetrackingFixture.configManager();
		TimetrackingFixture.birdHouse(fresh, varp, seededBirdHouseVarp(), now - 10 * 60);
		FarmTrackingService inProgress = TimetrackingFixture.service(fresh);
		inProgress.refresh();
		assertEquals(SummaryState.IN_PROGRESS, inProgress.birdHouseSummary());
		assertEquals(now - 10 * 60 + 50 * 60, inProgress.birdHouseCompletionTime());

		// seeded an hour ago: done
		ConfigManager old = TimetrackingFixture.configManager();
		TimetrackingFixture.birdHouse(old, varp, seededBirdHouseVarp(), now - 60 * 60);
		FarmTrackingService done = TimetrackingFixture.service(old);
		done.refresh();
		assertEquals(SummaryState.COMPLETED, done.birdHouseSummary());
	}

	/** A varp value that decodes as a SEEDED bird house — derived from the
	 *  vendored state table, never hardcoded. */
	private static int seededBirdHouseVarp()
	{
		for (int value = 0; value < 4096; value++)
		{
			if (com.ironhub.modules.farming.rl.hunter.BirdHouseState.fromVarpValue(value)
				== com.ironhub.modules.farming.rl.hunter.BirdHouseState.SEEDED)
			{
				return value;
			}
		}
		throw new IllegalStateException("no SEEDED bird house varp value");
	}

	@Test
	public void displayMappingCoversEveryCropState()
	{
		long now = Instant.now().getEpochSecond();
		assertEquals(FarmingRunModule.PatchView.READY, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.HARVESTABLE, 0, 4, 5), now));
		assertEquals(FarmingRunModule.PatchView.DISEASED, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.DISEASED, 0, 2, 5), now));
		assertEquals(FarmingRunModule.PatchView.DEAD, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.DEAD, 0, 2, 5), now));
		assertEquals(FarmingRunModule.PatchView.GROWING, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.GROWING, now + 1200, 2, 5), now));
		assertEquals(FarmingRunModule.PatchView.PREDICTED_READY, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.GROWING, now - 60, 3, 5), now));
		assertEquals(FarmingRunModule.PatchView.PREDICTED_READY, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.RANARR, CropState.GROWING, now + 1200, 4, 5), now));
		assertEquals(FarmingRunModule.PatchView.EMPTY, FarmingRunModule.viewOf(
			new PatchPrediction(Produce.WEEDS, CropState.GROWING, 0, 0, 3), now));
	}

	@Test
	public void overviewStatusCopyIsHonest()
	{
		long now = Instant.now().getEpochSecond();
		assertEquals("Ready", FarmingTab.statusText(SummaryState.COMPLETED, true, 0, now));
		assertEquals("Empty", FarmingTab.statusText(SummaryState.EMPTY, false, -1, now));
		assertEquals("2h", FarmingTab.statusText(SummaryState.IN_PROGRESS, false, now + 7200, now));
	}
}
