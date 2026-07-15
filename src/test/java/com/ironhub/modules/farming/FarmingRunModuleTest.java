package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.CropState;
import com.ironhub.modules.farming.rl.PatchImplementation;
import com.ironhub.modules.farming.rl.PatchState;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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
	public void proximityMarksStopsAndCompletesTheRun()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		module.startTemplate("Herb run");
		assertTrue(module.running());
		assertEquals("Herb run", module.runName());
		assertEquals(10, module.stops().size());
		assertEquals("herb/farming-guild", module.nextStop().location.id);

		// walk to the Falador patch (within radius)
		assertTrue(module.markVisited(new WorldPoint(3060, 3310, 0)));
		assertTrue(module.isVisited("herb/falador"));
		assertEquals(1, module.visitedCount());
		assertEquals("herb/farming-guild", module.nextStop().location.id);

		// far away and wrong plane: nothing marked
		assertFalse(module.markVisited(new WorldPoint(3200, 3200, 0)));
		assertFalse(module.markVisited(new WorldPoint(2670, 3374, 1)));

		// visit the rest — run auto-completes and records a duration
		for (FarmingRunModule.Stop stop : module.stops())
		{
			module.markVisited(stop.location.worldPoint());
		}
		assertFalse(module.running());
		assertEquals(1, state.getHerbRunsMs().size());
		module.shutDown();
	}

	@Test
	public void customRunsPersistAndResolve()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		state.saveFarmRun("Quick herbs", List.of("herb/falador", "herb/catherby", "no/such-place"));

		module.startCustom("Quick herbs");
		assertTrue(module.running());
		assertEquals(2, module.stops().size()); // the unknown id is skipped
		assertEquals("herb/falador", module.stops().get(0).location.id);
		module.endRun(false);

		state.deleteFarmRun("Quick herbs");
		assertTrue(state.getFarmRuns().isEmpty());
		module.shutDown();
	}

	@Test
	public void teleportsArePickedFromWhatYouOwn()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		FarmRunsPack.Location falador = module.pack().location("herb/falador");

		// nothing owned: fall back to an access-based option (house portal)
		assertEquals("house", module.pickTeleport(falador).supplier);

		// an Explorer's ring in the BANK is enough to plan around
		StateFixture.bank(state, Map.of(13126, 1));
		assertEquals("Explorers_ring", module.pickTeleport(falador).id);

		// with only a charged glory banked, variation mapping matches the
		// pack's Glory(1) requirement and the glory option is picked
		StateFixture.bank(state, Map.of(1712, 1));
		assertEquals("Amulet_of_Glory", module.pickTeleport(falador).id);

		// carrying nothing: the ring teleport reads as missing until worn
		StateFixture.bank(state, Map.of(13126, 1));
		FarmingRunModule.Stop stop = new FarmingRunModule.Stop(falador, module.pickTeleport(falador));
		assertEquals(1, module.missingItems(stop).size());
		StateFixture.equipmentSlots(state, new int[]{13126});
		assertTrue(module.missingItems(stop).isEmpty());
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
		module.startTemplate("Herb run");
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

		// mid-run: the stop checklist replaces the run picker, and the
		// overlay renders within its 250x200 budget
		StateFixture.bank(state, Map.of(13126, 1)); // Explorer's ring planned
		module.startTemplate("Herb run");
		module.markVisited(module.pack().location("herb/farming-guild").worldPoint());
		SwingUtilities.invokeAndWait(((FarmingTab) tab)::rebuild); // Swing is single-threaded
		java.awt.image.BufferedImage active = SwingRender.render((JPanel) tab);
		javax.imageio.ImageIO.write(active, "png", new java.io.File("build/reports/farming-run-active.png"));

		FarmingRunOverlay overlay = new FarmingRunOverlay(module);
		java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
			300, 260, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = canvas.createGraphics();
		g.setColor(new java.awt.Color(58, 66, 48));
		g.fillRect(0, 0, 300, 260);
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		java.awt.Dimension size = overlay.render(g);
		g.dispose();
		assertNotNull(size);
		assertTrue("overlay width " + size.width, size.width <= 250);
		assertTrue("overlay height " + size.height, size.height <= 200);
		javax.imageio.ImageIO.write(canvas, "png",
			new java.io.File("build/reports/farming-run-overlay.png"));
		module.shutDown();
	}
}
