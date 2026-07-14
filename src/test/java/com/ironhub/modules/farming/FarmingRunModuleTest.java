package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FarmingRunModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private FarmingRunModule module(AccountState state)
	{
		FarmingRunModule module = new FarmingRunModule(state, null, new EventBus(),
			null, null, null, config, null, new DataPack(new Gson()), null);
		module.startUp();
		return module;
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
		FarmingRunModule module = module(state);
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
	public void readyPatchesNotifyOnceUntilRearmed()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state);

		state.recordHerbPatch("falador", "HARVESTABLE", "Ranarr", 0);
		assertEquals(List.of("Falador"), module.newlyReadyPatches());
		assertTrue(module.newlyReadyPatches().isEmpty()); // once only

		// harvested and replanted: re-arms, growing does not notify
		state.recordHerbPatch("falador", "GROWING", "Ranarr", 0);
		assertTrue(module.newlyReadyPatches().isEmpty());
		state.recordHerbPatch("falador", "HARVESTABLE", "Ranarr", 0);
		assertEquals(List.of("Falador"), module.newlyReadyPatches());
		module.shutDown();
	}

	@Test
	public void abandonedRunsAreNotRecorded()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state);
		module.startRun();
		module.endRun(false);
		assertTrue(state.getHerbRunsMs().isEmpty());
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state);
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
