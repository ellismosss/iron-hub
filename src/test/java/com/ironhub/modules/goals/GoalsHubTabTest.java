package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2 G7: the single Goals surface (GoalsHubTab) — the render Luke
 * verdicts before the old planner views are deleted. Both themes + the
 * completed archive, plus the client-mount label-height pin every skinned
 * surface obeys.
 */
public class GoalsHubTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private GoalPlannerModule module(AccountState state)
	{
		GoalPlannerModule module = new GoalPlannerModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, new SkillIconManager(), null);
		module.startUp();
		return module;
	}

	private AccountState seeded(long profile)
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, profile);
		StateFixture.stat(state, Skill.AGILITY, 52, net.runelite.api.Experience.getXpForLevel(52));
		StateFixture.stat(state, Skill.THIEVING, 40, net.runelite.api.Experience.getXpForLevel(40));
		// a few routes across categories
		state.selectGoal("barrows_gloves", true);
		state.setGoalPriority("barrows_gloves", "high"); // a coloured left edge
		state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
			"custom:quest:dragon-slayer-i", "Dragon Slayer I", "quest:Dragon Slayer I"));
		state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
			"custom:skill:agility:70", "Agility 70", "skill:Agility:70"));
		state.setGoalPinned("custom:skill:agility:70", true);
		// a clog Route whose obtain step carries a real drop rate (1/400 Tempoross)
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clog(25580, "Tackle box",
			"Fishing from the reward pool (tempoross)", java.util.List.of("skill:Fishing:35")));
		// a Supplies Route: it decomposes to its raw component materials
		state.addGoalSeed(com.ironhub.state.GoalSeeds.supply(21183, "Bracelet of slaughter", 100));
		// an item with SEVERAL obtainment methods (craft it, or hunt dragon
		// implings) — the choose-a-method affordance + per-method rows
		state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
			"custom:item:amulet-of-glory", "Amulet of glory", "item:1704:1:Amulet of glory"));
		state.setGoalPriority("bowfa", "someday"); // dimmed + grey edge (if selected)
		// one archived (dated) + one detected, so the hero + archive fill
		PersistedState.GoalRecord dated = new PersistedState.GoalRecord();
		dated.goalId = "fire_cape";
		dated.name = "Fire cape";
		dated.family = "pack";
		dated.completedAt = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
			.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
		dated.estimatedHours = 8;
		dated.hoursAtCompletion = 6.5;
		state.recordGoalCompletion(dated);
		PersistedState.GoalRecord detected = new PersistedState.GoalRecord();
		detected.goalId = "graceful";
		detected.name = "Graceful outfit";
		detected.family = "pack";
		state.recordGoalCompletion(detected);
		return state;
	}

	@Test
	public void hubRendersEveryThemeWithRoutesAndArchive() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			AccountState state = seeded(1L);
			GoalPlannerModule module = module(state);
			waitForPlan(module);

			GoalsHubTab[] holder = new GoalsHubTab[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
				holder[0] = new GoalsHubTab(module, state, packOf(module), gearOf(module),
					null, new SkillIconManager(), theme);
				holder[0].expandRoute("custom:skill:agility:70");
			});
			javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
			BufferedImage main = renderOnEdt(holder[0]);
			assertEquals(UiTokens.PANEL_WIDTH, main.getWidth());
			assertTrue("the hub should be tall", main.getHeight() > 300);
			write(main, "goals-hub-tab-" + theme.name().toLowerCase());

			// a clog Route expanded: its obtain Task shows the 1/400 drop rate
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("clog:25580"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(renderOnEdt(holder[0]), "goals-hub-drops-" + theme.name().toLowerCase());

			// an item with several obtainment methods: one row per method,
			// the chooser affordance, and materials with their sprites
			javax.swing.SwingUtilities.invokeAndWait(
				() -> holder[0].expandRoute("custom:item:amulet-of-glory"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(renderOnEdt(holder[0]), "goals-hub-methods-" + theme.name().toLowerCase());

			// a quest Route expanded: the target quest's own Task reads "Complete the quest"
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("custom:quest:dragon-slayer-i"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(renderOnEdt(holder[0]), "goals-hub-quest-" + theme.name().toLowerCase());

			// a Supplies Route expanded: its Task notes the resource required
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("supply:21183"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(renderOnEdt(holder[0]), "goals-hub-supply-" + theme.name().toLowerCase());

			// the completed archive (depth 2)
			javax.swing.SwingUtilities.invokeAndWait(holder[0]::openArchive);
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(renderOnEdt(holder[0]), "goals-hub-archive-" + theme.name().toLowerCase());
			module.shutDown();
		}
	}

	/** A supply Route's "Open wiki" links to the stocked ITEM, never the
	 *  "Stock N × …" goal name (Luke's report). */
	@Test
	public void farmingStepsCountMeasuredRuns() throws Exception
	{
		AccountState state = seeded(7L);
		// three completed runs averaging 12k Farming xp — the record log the
		// farming tab already earns its runs-to-level line from
		for (int xp : new int[]{10_000, 12_000, 14_000})
		{
			com.ironhub.state.PersistedState.FarmRunRecord record =
				new com.ironhub.state.PersistedState.FarmRunRecord();
			record.endMs = 1_000;
			record.name = "Herb run";
			record.xpByBucket.put("herb", xp);
			state.recordFarmRun(record);
		}
		GoalPlannerModule module = module(state);
		waitForPlan(module);
		GoalsHubTab[] holder = new GoalsHubTab[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			holder[0] = new GoalsHubTab(module, state, packOf(module), gearOf(module),
				null, new SkillIconManager(), OsrsTheme.STONE));
		assertEquals(12_000, holder[0].avgFarmingXpPerRun(), 1e-9);
		module.shutDown();
	}

	@Test
	public void supplyGoalWikiLinksToTheStockedItem() throws Exception
	{
		AccountState state = seeded(3L);
		GoalPlannerModule module = module(state);
		waitForPlan(module);
		GoalsHubTab[] holder = new GoalsHubTab[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			holder[0] = new GoalsHubTab(module, state, packOf(module), gearOf(module),
				null, new SkillIconManager(), OsrsTheme.STONE));
		com.ironhub.data.GoalsPack.Goal supply = null;
		for (com.ironhub.data.GoalsPack.Goal g
			: GoalPlannerModule.allGoals(packOf(module), gearOf(module), state))
		{
			if ("supply:21183".equals(g.getId()))
			{
				supply = g;
			}
		}
		assertTrue("supply goal present", supply != null);
		String wiki = holder[0].goalWiki(supply);
		assertEquals("https://oldschool.runescape.wiki/w/Bracelet_of_slaughter", wiki);
		module.shutDown();
	}

	/** The single-pass client-mount label-height pin (skinned-surface rule). */
	@Test
	public void everyLabelKeepsItsHeightUnderTheClientMount() throws Exception
	{
		AccountState state = seeded(2L);
		GoalPlannerModule module = module(state);
		waitForPlan(module);
		GoalsHubTab[] holder = new GoalsHubTab[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			holder[0] = new GoalsHubTab(module, state, packOf(module), gearOf(module),
				null, new SkillIconManager(), OsrsTheme.MYSTIC));
		// force a direct rebuild against the settled plan (the ctor may have run
		// before the background replan finished; RebuildGate defers while hidden)
		javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("custom:skill:agility:70"));

		// layout + collect ON the EDT, polling for settled content: the
		// SUGGESTER finishes after the plan and its queued listener rebuild
		// can removeAll() mid-layout on the test thread under suite load
		// (the mutate-then-render race, 6th appearance, 2026-07-21)
		List<OsrsLabel> labels = new ArrayList<>();
		for (int attempt = 0; attempt < 50 && labels.size() <= 10; attempt++)
		{
			if (attempt > 0)
			{
				Thread.sleep(100);
			}
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
				labels.clear();
				com.ironhub.ui.components.HubScrollPane pane =
					new com.ironhub.ui.components.HubScrollPane(holder[0]);
				pane.setSize(UiTokens.PANEL_WIDTH, 1200);
				layoutOnce(pane);
				collect(holder[0], labels);
			});
		}
		assertTrue("no labels found", labels.size() > 10);
		for (OsrsLabel label : labels)
		{
			assertTrue("label cut below preferred height: " + label.getBounds(),
				label.getHeight() >= label.getPreferredSize().height);
		}
		module.shutDown();
	}

	private static com.ironhub.data.GoalsPack packOf(GoalPlannerModule module)
	{
		return new DataPack(new Gson()).load("goals", com.ironhub.data.GoalsPack.class);
	}

	private static com.ironhub.data.GearProgressionPack gearOf(GoalPlannerModule module)
	{
		return new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		File out = new File("build/reports/" + name + ".png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	private static void waitForPlan(GoalPlannerModule module) throws InterruptedException
	{
		// generous under full-suite load, and LOUD on failure — a silent
		// give-up used to surface as a baffling "hub should be tall"
		for (int i = 0; i < 200 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
		assertTrue("the planner never published a plan", module.currentPlan() != null);
	}

	/** Render ON the EDT: the suggester finishing late fires a tab rebuild
	 *  via invokeLater, and a test-thread render races that removeAll (the
	 *  mutate-then-render race, 7th appearance, 2026-07-23 — only under
	 *  full-suite load). EDT rendering serialises with listener rebuilds
	 *  by construction. */
	private static BufferedImage renderOnEdt(GoalsHubTab tab) throws Exception
	{
		BufferedImage[] out = new BufferedImage[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> out[0] = SwingRender.render(tab));
		return out[0];
	}

	private static void layoutOnce(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutOnce(child);
			}
		}
	}

	private static void collect(Container root, List<OsrsLabel> out)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof OsrsLabel)
			{
				out.add((OsrsLabel) child);
			}
			if (child instanceof Container)
			{
				collect((Container) child, out);
			}
		}
	}
}
