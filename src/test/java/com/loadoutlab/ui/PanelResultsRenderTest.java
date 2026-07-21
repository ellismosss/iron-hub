package com.loadoutlab.ui;

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
import org.junit.Test;

/**
 * The 2026-07-21 DPS-calc refresh, proven end-to-end: a PLURAL slayer
 * assignment auto-selects its monster and RUNS the calc (it used to only
 * fill the search box), and real optimizer results render the stat tile +
 * three-mode buttons (PNG: build/reports/dps-calc-results.png).
 */
public class PanelResultsRenderTest
{
	private static LoadoutData data;

	@BeforeClass
	public static void load()
	{
		data = new DataService().load();
	}

	@Test
	public void pluralTaskAutoRunsAndStatsTileRenders() throws Exception
	{
		AtomicReference<MonsterStats> computed = new AtomicReference<>();
		LoadoutLabPanel[] panel = new LoadoutLabPanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = LinkInTest.panel(data, computed));

		// "Dust devils" is the ASSIGNMENT name; the corpus is singular — the
		// auto-follow must still land on the monster and fire the compute
		boolean[] selected = new boolean[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			selected[0] = panel[0].selectExternal("Dust devils", null));
		Assert.assertTrue("plural task name must select its monster", selected[0]);
		Assert.assertNotNull("selecting must RUN the calc, not just fill the box", computed.get());
		Assert.assertEquals("Dust devil", computed.get().getName());

		// real optimizer results for the tile
		Map<Integer, Integer> owned = new HashMap<>();
		owned.put(4151, 1);  // abyssal whip
		owned.put(1215, 1);  // dragon dagger
		owned.put(10551, 1); // fighter torso
		OptimizerService service = new OptimizerService(data);
		try
		{
			CountDownLatch done = new CountDownLatch(1);
			AtomicReference<Map<CombatStyle, OptimizerService.StyleResult>> out = new AtomicReference<>();
			service.bestPerStyle(computed.get(), PlayerLevels.MAXED, PlayerLevels.MAXED,
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
				panel[0].showResults(computed.get(), out.get());
				image[0] = com.ironhub.ui.SwingRender.render(panel[0]);
			});
			Assert.assertTrue(image[0].getHeight() > 300);
			java.io.File file = new java.io.File("build/reports/dps-calc-results.png");
			file.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image[0], "png", file);
		}
		finally
		{
			service.shutdown();
		}
	}
}
