package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.modules.farming.rl.CropState;
import com.ironhub.modules.farming.rl.PatchImplementation;
import com.ironhub.modules.farming.rl.PatchState;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.time.Instant;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FarmingRunModuleTest
{
	private static final int FALADOR_REGION = 12083;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private FarmingRunModule module(AccountState state, ConfigManager configManager, Notifier notifier)
	{
		FarmingRunModule module = new FarmingRunModule(state, null, new EventBus(),
			null, null, null, config, null, new DataPack(new Gson()), notifier,
			configManager, null);
		module.startUp();
		return module;
	}

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
		throw new IllegalStateException("no herb varbit value");
	}

	@Test
	public void formatting()
	{
		assertEquals("5:40", FarmingRunModule.formatDuration(340_000));
		assertEquals("0:05", FarmingRunModule.formatDuration(5_400));
		assertEquals("no runs logged yet", FarmingRunModule.statsLine(List.of()));
		assertEquals("avg 5:17 · best 4:55 · 2 runs logged",
			FarmingRunModule.statsLine(List.of(340_000L, 295_000L)));
	}

	@Test
	public void proximityMarksPatchesAndCompletesTheRun()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		module.startRun();
		assertTrue(module.running());

		// walk to Falador patch (within radius)
		assertTrue(module.markVisited(new WorldPoint(3060, 3313, 0)));
		assertTrue(module.isVisited("falador"));
		assertEquals(1, module.visitedCount());
		assertEquals("ardougne", module.nextPatch().getId());

		// far away and wrong plane: nothing marked
		assertFalse(module.markVisited(new WorldPoint(3200, 3200, 0)));
		assertFalse(module.markVisited(new WorldPoint(2670, 3374, 1)));

		// visit the rest — run auto-completes and records a duration
		for (HerbPatchesPack.Patch patch : module.patches())
		{
			module.markVisited(patch.getLocation());
		}
		assertFalse(module.running());
		assertEquals(1, state.getHerbRunsMs().size());
		module.shutDown();
	}

	@Test
	public void readinessNotifiesOncePerTransitionAndNeverOnLogin()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		Notifier notifier = Mockito.mock(Notifier.class);

		// stored state is ALREADY harvestable before the module starts —
		// the first refresh must seed silently, never replay old readiness
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		FarmingRunModule module = module(state, configManager, notifier);
		module.refreshTracking();
		Mockito.verifyNoInteractions(notifier);
		assertEquals(1, FarmingRunModule.sharedReadyPatches());
		assertEquals(1, module.readyCount());

		// harvested + replanted: re-arms
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), Instant.now().getEpochSecond());
		module.refreshTracking();
		Mockito.verifyNoInteractions(notifier);

		// grows back to harvestable: exactly one notification
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		module.refreshTracking();
		module.refreshTracking();
		Mockito.verify(notifier, Mockito.times(1)).notify("Herb Patches ready to harvest.");
		module.shutDown();
		assertEquals(0, FarmingRunModule.sharedReadyPatches());
	}

	@Test
	public void abandonedRunsAreNotRecorded()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		module.startRun();
		module.endRun(false);
		assertTrue(state.getHerbRunsMs().isEmpty());
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		long now = Instant.now().getEpochSecond();
		// one ready herb patch, one growing, and seeded bird houses
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), now);
		TimetrackingFixture.patch(configManager, 10548, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), now);
		int seeded = 0;
		while (com.ironhub.modules.farming.rl.hunter.BirdHouseState.fromVarpValue(seeded)
			!= com.ironhub.modules.farming.rl.hunter.BirdHouseState.SEEDED)
		{
			seeded++;
		}
		TimetrackingFixture.birdHouse(configManager,
			com.ironhub.modules.farming.rl.hunter.BirdHouseSpace.MEADOW_NORTH.getVarp(),
			seeded, now - 10 * 60);

		FarmingRunModule module = module(state, configManager, null);
		module.refreshTracking();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/farming-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
