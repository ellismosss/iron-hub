package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GoalPlannerTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final GoalsPack pack = new DataPack(new Gson()).load("goals", GoalsPack.class);

	private GoalsPack.Goal goal(String id)
	{
		return pack.getGoals().stream().filter(g -> g.getId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	public void stepsAutoCompleteFromLiveEvents()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal defender = goal("dragon_defender");

		assertEquals(0.0, GoalPlannerModule.progress(defender, state), 0.001);
		assertEquals("60 Attack", GoalPlannerModule.nextStep(defender, state).label);

		// level-up events flip detectable steps with no manual ticking
		StateFixture.stat(state, Skill.ATTACK, 60, 0);
		StateFixture.stat(state, Skill.DEFENCE, 60, 0);
		assertEquals(2 / 3.0, GoalPlannerModule.progress(defender, state), 0.001);
		assertEquals("Climb the Warriors' Guild cyclopes",
			GoalPlannerModule.nextStep(defender, state).label);

		// the item appearing in the bank completes the goal
		StateFixture.bank(state, Map.of(12954, 1));
		assertEquals(1.0, GoalPlannerModule.progress(defender, state), 0.001);
		assertEquals(null, GoalPlannerModule.nextStep(defender, state));
	}

	@Test
	public void manualStepsTickViaUnlockFlags()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal graceful = goal("graceful");

		List<GoalPlannerModule.CompiledStep> steps = GoalPlannerModule.compile(graceful, state);
		assertTrue(steps.get(0).manual);
		assertFalse(steps.get(0).met);

		state.setUnlocked(steps.get(0).unlockKey, true);
		assertTrue(GoalPlannerModule.compile(graceful, state).get(0).met);
	}

	@Test
	public void selectionAndActivePersist()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 11L);
		before.selectGoal("bowfa", true);
		before.selectGoal("graceful", true);
		assertEquals("bowfa", before.getActiveGoal()); // first selected auto-pins

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 11L);
		assertEquals(2, after.getSelectedGoals().size());
		assertEquals("bowfa", after.getActiveGoal());

		after.selectGoal("bowfa", false); // unselecting the active goal unpins it
		assertEquals("", after.getActiveGoal());
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.selectGoal("dragon_defender", true);
		state.selectGoal("bowfa", true);
		StateFixture.stat(state, Skill.ATTACK, 60, 0);

		GoalPlannerModule module = new GoalPlannerModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/goals-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
