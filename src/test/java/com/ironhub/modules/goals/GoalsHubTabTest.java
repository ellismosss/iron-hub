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
			BufferedImage main = SwingRender.render(holder[0]);
			assertEquals(UiTokens.PANEL_WIDTH, main.getWidth());
			assertTrue("the hub should be tall", main.getHeight() > 300);
			write(main, "goals-hub-tab-" + theme.name().toLowerCase());

			// a clog Route expanded: its obtain Task shows the 1/400 drop rate
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("clog:25580"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(SwingRender.render(holder[0]), "goals-hub-drops-" + theme.name().toLowerCase());

			// a quest Route expanded: the target quest's own Task reads "Complete the quest"
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].expandRoute("custom:quest:dragon-slayer-i"));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(SwingRender.render(holder[0]), "goals-hub-quest-" + theme.name().toLowerCase());

			// the completed archive (depth 2)
			javax.swing.SwingUtilities.invokeAndWait(holder[0]::openArchive);
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(SwingRender.render(holder[0]), "goals-hub-archive-" + theme.name().toLowerCase());
			module.shutDown();
		}
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
		javax.swing.SwingUtilities.invokeAndWait(() -> { });

		com.ironhub.ui.components.HubScrollPane pane = new com.ironhub.ui.components.HubScrollPane(holder[0]);
		pane.setSize(UiTokens.PANEL_WIDTH, 1200);
		layoutOnce(pane);
		List<OsrsLabel> labels = new ArrayList<>();
		collect(holder[0], labels);
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
		for (int i = 0; i < 50 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
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
