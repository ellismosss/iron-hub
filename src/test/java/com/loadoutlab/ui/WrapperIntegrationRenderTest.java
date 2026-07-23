package com.loadoutlab.ui;

import com.ironhub.modules.loadoutlab.LoadoutLabModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.loadoutlab.LoadoutLabPlugin;
import com.loadoutlab.data.DataService;
import com.loadoutlab.data.LoadoutData;
import com.loadoutlab.data.MonsterStats;
import com.loadoutlab.engine.CombatStyle;
import com.loadoutlab.engine.OptimizationRequest;
import com.loadoutlab.engine.OwnedItems;
import com.loadoutlab.engine.PlayerLevels;
import com.loadoutlab.engine.RequirementProfile;
import com.loadoutlab.optimizer.OptimizerService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * The 2026-07-21 DPS/Loadout integration, end-to-end: the wrapper's shared
 * gear viewer + stat tile follow the calc's published results (source
 * chips, style buttons with the best dps green), and the live view's tile
 * evaluates worn gear against the LAST monster attacked. PNGs:
 * build/reports/loadout-integrated-{dps,live}.png.
 */
public class WrapperIntegrationRenderTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static LoadoutData data;

	@BeforeClass
	public static void load()
	{
		data = new DataService().load();
	}

	@Test
	public void sharedViewerFollowsTheCalcAndRevertsToLive() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		int[] worn = new int[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx() + 1];
		java.util.Arrays.fill(worn, -1);
		worn[net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx()] = 4151; // whip
		worn[net.runelite.api.EquipmentInventorySlot.BODY.getSlotIdx()] = 10551;  // torso
		StateFixture.equipmentSlots(state, worn);
		for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
		{
			StateFixture.stat(state, skill, 90, 6_000_000);
		}

		AtomicReference<MonsterStats> computed = new AtomicReference<>();
		LoadoutLabPanel[] panel = new LoadoutLabPanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = LinkInTest.panel(data, computed));
		LoadoutLabPlugin lab = Mockito.mock(LoadoutLabPlugin.class);
		Mockito.when(lab.getPanel()).thenReturn(panel[0]);

		LoadoutLabModule module = new LoadoutLabModule(lab,
			new net.runelite.client.eventbus.EventBus(), new com.ironhub.IronHubConfig()
			{
			},
			state, null, null, null, new com.google.gson.Gson(), null, null, null, null);
		javax.swing.JComponent[] tab = new javax.swing.JComponent[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> tab[0] = module.buildTab());

		// the calc runs for the dust devil; publishing results must flip the
		// shared viewer to the DPS view with style buttons + suggestion tile
		javax.swing.SwingUtilities.invokeAndWait(() ->
			panel[0].selectExternal("Dust devils", null));
		MonsterStats monster = computed.get();
		Assert.assertNotNull(monster);
		state.setCombatTarget(monster.getName(), monster.getId()); // last attacked

		Map<Integer, Integer> owned = new HashMap<>();
		owned.put(4151, 1);
		owned.put(10551, 1);
		owned.put(861, 1); // magic shortbow — a ranged set exists too
		OptimizerService service = new OptimizerService(data);
		try
		{
			CountDownLatch done = new CountDownLatch(1);
			AtomicReference<Map<CombatStyle, OptimizerService.StyleResult>> out = new AtomicReference<>();
			service.bestPerStyle(monster, PlayerLevels.MAXED, PlayerLevels.MAXED,
				com.loadoutlab.engine.PrayerUnlocks.ALL, RequirementProfile.MAXED,
				new OwnedItems(owned, true), 1, false, false, "",
				java.util.Collections.emptySet(), -1, OptimizationRequest.DEFAULT_RISK_BUDGET_GP,
				false, java.util.Collections.emptySet(), 0,
				OptimizerService.OptimizeMode.MAX_DPS, results ->
				{
					out.set(results);
					done.countDown();
				});
			Assert.assertTrue(done.await(120, TimeUnit.SECONDS));

			java.awt.image.BufferedImage[] image = new java.awt.image.BufferedImage[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
				panel[0].showResults(monster, out.get());
				// results no longer hijack the view (round 4) — enter the
				// Recommended view the way the chip would
				module.setViewSourceForTest(2);
				image[0] = com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab[0]);
			});
			Assert.assertTrue(image[0].getHeight() > 400);
			write(image[0], "loadout-integrated-dps.png");

			// the Wiki gear fold (KB-runtime slice 3): the selected monster is
			// the dust devil, whose "Slayer task/Dust devils" wiki gear table
			// renders under the monster card — ownership-tinted rows, "+"
			// goal affordances on unowned top picks; expanding must grow the
			// tab by the slot rows
			int collapsedHeight = image[0].getHeight();
			javax.swing.SwingUtilities.invokeAndWait(() ->
				module.setWikiGearExpandedForTest(true));
			java.awt.image.BufferedImage[] wiki = new java.awt.image.BufferedImage[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
				wiki[0] = com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab[0]));
			Assert.assertTrue("expanding the Wiki gear fold must add slot rows",
				wiki[0].getHeight() > collapsedHeight + 100);
			write(wiki[0], "loadout-wiki-gear.png");
			// collapse again so the later reference renders stay as they were
			javax.swing.SwingUtilities.invokeAndWait(() ->
				module.setWikiGearExpandedForTest(false));

			// the × on the monster clears the calc — the viewer reverts to live,
			// whose tile evaluates the worn gear vs the last monster attacked
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
				panel[0].selectExternal("Goblin", null); // select -> clear via new select is fine
				panel[0].showResults(computed.get(), out.get()); // stale: ignored (different monster)
			});
			javax.swing.SwingUtilities.invokeAndWait(() -> module.setViewSourceForTest(0));
			java.awt.image.BufferedImage[] live = new java.awt.image.BufferedImage[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
				live[0] = com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab[0]));
			Assert.assertTrue(live[0].getHeight() > 300);
			write(live[0], "loadout-integrated-live.png");

			// the Slayer chip (2026-07-21): the task's saved setup renders in
			// the SAME viewer, diffed vs current — the facemask is not carried
			// so it must arrive orange, and the setup's inventory shows in the
			// shared Inventory fold
			com.ironhub.state.PersistedState.SavedSetup slayerSetup =
				new com.ironhub.state.PersistedState.SavedSetup();
			slayerSetup.equipment.put("HEAD", 4164);   // facemask — not carried
			slayerSetup.equipment.put("WEAPON", 4151); // whip — already worn
			slayerSetup.inventory = new int[28];
			java.util.Arrays.fill(slayerSetup.inventory, -1);
			slayerSetup.inventoryQty = new int[28];
			slayerSetup.inventory[0] = 560;            // death runes
			slayerSetup.inventoryQty[0] = 200;
			state.setSlayerTask("Dust devils");
			state.saveSetup("Dust devils", slayerSetup);
			javax.swing.SwingUtilities.invokeAndWait(() -> module.setViewSourceForTest(1));
			java.awt.image.BufferedImage[] slayer = new java.awt.image.BufferedImage[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
				slayer[0] = com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab[0]));
			Assert.assertTrue(slayer[0].getHeight() > 300);
			write(slayer[0], "loadout-integrated-slayer.png");
		}
		finally
		{
			service.shutdown();
		}
	}

	private static void write(java.awt.image.BufferedImage image, String name) throws Exception
	{
		java.io.File out = new java.io.File("build/reports/" + name);
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}
}
