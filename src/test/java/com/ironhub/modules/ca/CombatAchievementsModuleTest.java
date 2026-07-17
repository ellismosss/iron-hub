package com.ironhub.modules.ca;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CombatAchievementsModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private CombatAchievementsModule module(AccountState state, IronHubConfig config)
	{
		return new CombatAchievementsModule(state, config, null,
			new EventBus(), new DataPack(new Gson()), null);
	}

	@Test
	public void nextTierFollowsThresholds()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// thresholds unknown (all 0) — no tier claimed as next
		assertNull(CombatAchievementsModule.nextTier(state));

		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_EASY, 33);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_MEDIUM, 115);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_HARD, 304);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 0);
		assertEquals("Easy", CombatAchievementsModule.nextTier(state).display);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 214);
		assertEquals("Hard", CombatAchievementsModule.nextTier(state).display);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 304);
		assertNull(CombatAchievementsModule.nextTier(state)); // past all known thresholds
	}

	@Test
	public void fixedTierGoalOverridesAuto()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_EASY, 33);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_GRANDMASTER, 2005);
		StateFixture.varbit(state, VarbitID.CA_POINTS, 10);

		CombatAchievementsModule auto = module(state, new IronHubConfig()
		{
		});
		assertEquals(CaTier.EASY, auto.goalTier());
		assertEquals(33, auto.goalThreshold());

		CombatAchievementsModule fixed = module(state, new IronHubConfig()
		{
			@Override
			public CaTierGoal caTierGoal()
			{
				return CaTierGoal.GRANDMASTER;
			}
		});
		assertEquals(CaTier.GRANDMASTER, fixed.goalTier());
		assertEquals(2005, fixed.goalThreshold());
	}

	@Test
	public void completedBitDecodesTheVarpBitfield()
	{
		int[] varps = new int[20];
		varps[0] = 0b101;          // tasks 0 and 2
		varps[3] = 1 << 31;        // task 3*32+31 = 127
		assertTrue(CaCatalog.completedBit(0, varps));
		assertFalse(CaCatalog.completedBit(1, varps));
		assertTrue(CaCatalog.completedBit(2, varps));
		assertTrue(CaCatalog.completedBit(127, varps));
		assertFalse(CaCatalog.completedBit(128, varps));
		assertFalse(CaCatalog.completedBit(-1, varps));
		assertFalse(CaCatalog.completedBit(20 * 32, varps)); // out of field
	}

	@Test
	public void completionPackCoversTheCatalogRange()
	{
		com.ironhub.data.CaCompletionPack pack = new DataPack(new Gson())
			.load("ca-completion", com.ironhub.data.CaCompletionPack.class);
		Map<Integer, Double> byId = pack.byId();
		assertTrue("pack unexpectedly small: " + byId.size(), byId.size() >= 500);
		// every bundled id must fit the 20-varp completion bitfield
		assertTrue(byId.keySet().stream().allMatch(id -> id >= 0 && id < 20 * 32));
		assertEquals("noxious foe", com.ironhub.data.CaCompletionPack.normalize("Noxious   Foe!"));
	}

	@Test
	public void caGoalsPersistAndCompileInThePlanner()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 7L);
		before.addCaGoal(340, "Noxious Foe", "Kill an Aberrant Spectre.", "Easy");
		before.addCaGoal(12, "Removed", "x", "Easy");
		before.removeCaGoal(12);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 7L);
		assertEquals(java.util.Set.of("ca:340"), after.getSelectedGoals());
		assertEquals(java.util.Set.of("340"), after.getCaGoals().keySet());

		// the seed compiles into a planner goal: one step, unlock-flag proof
		com.ironhub.data.GoalsPack.Goal goal = com.ironhub.modules.goals.GoalPlannerModule
			.toCaGoal("340", after.getCaGoals().get("340"));
		assertEquals("ca:340", goal.getId());
		assertEquals("Noxious Foe", goal.getName());
		assertEquals(1, goal.getSteps().size());
		assertFalse(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, after));

		// the CA module marking the unlock (task done in-game) achieves it
		after.setUnlocked("catask_340", true);
		assertTrue(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, after));
	}

	@Test
	public void bossStatsAggregateCompletion()
	{
		List<CaTask> tasks = List.of(
			new CaTask(0, "A", "", CaTier.EASY, "Kill Count", "Zulrah", true),
			new CaTask(1, "B", "", CaTier.HARD, "Perfection", "Zulrah", false),
			new CaTask(2, "C", "", CaTier.EASY, "Kill Count", "Hespori", true),
			new CaTask(3, "D", "", CaTier.EASY, "Stamina", "", true));
		Map<String, int[]> stats = CombatAchievementsTab.bossStats(tasks);
		assertEquals(2, stats.size()); // the boss-less task is excluded
		assertEquals(1, stats.get("Zulrah")[0]);
		assertEquals(2, stats.get("Zulrah")[1]);
		assertEquals(1, stats.get("Hespori")[0]);
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varbit(state, VarbitID.CA_POINTS, 214);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_HARD, 304);

		CombatAchievementsModule module = module(state, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);

		// no client in tests: reflection stands in for loadCatalog so the
		// render shows real rows, an expanded card and the boss grid
		List<CaTask> tasks = List.of(
			new CaTask(0, "Noxious Foe", "Kill an Aberrant Spectre.",
				CaTier.EASY, "Kill Count", "Aberrant Spectre", true),
			new CaTask(1, "Zulrah Speed-Trialist", "Kill Zulrah in less than 1 minute 20 seconds.",
				CaTier.HARD, "Speed", "Zulrah", false),
			new CaTask(2, "Perfect Zulrah", "Kill Zulrah whilst taking no damage from the following: "
				+ "Snakelings, Venom Clouds, Zulrah's Green or Crimson phase.",
				CaTier.ELITE, "Perfection", "Zulrah", false),
			new CaTask(3, "Hespori Speed-Chaser", "Kill the Hespori in less than 48 seconds.",
				CaTier.MEDIUM, "Speed", "Hespori", true));
		tasks.get(1).communityPct = 12.6;
		java.lang.reflect.Field field = CombatAchievementsModule.class.getDeclaredField("tasks");
		field.setAccessible(true);
		field.set(module, tasks);
		CombatAchievementsTab caTab = (CombatAchievementsTab) tab;
		caTab.onTasksUpdated();
		caTab.expandForTest(2);

		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 150);
		write(image, "ca-tab.png");

		caTab.expandFiltersForTest();
		write(SwingRender.render((JPanel) tab), "ca-tab-filters.png");

		caTab.showBossesForTest();
		java.awt.image.BufferedImage bosses = SwingRender.render((JPanel) tab);
		assertTrue(bosses.getHeight() > 150);
		write(bosses, "ca-tab-bosses.png");

		caTab.drillForTest("Zulrah");
		write(SwingRender.render((JPanel) tab), "ca-tab-boss-drilldown.png");
		module.shutDown();
	}

	private static void write(java.awt.image.BufferedImage image, String name)
	{
		try
		{
			java.io.File out = new java.io.File("build/reports/" + name);
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
	}
}
