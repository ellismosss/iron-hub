package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlannerTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private GoalPlannerModule module(AccountState state)
	{
		GoalPlannerModule module = new GoalPlannerModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null);
		module.startUp();
		return module;
	}

	@Test
	public void constraintsPersistAcrossProfiles()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 9L);
		before.togglePlannerPin("quest:Dragon Slayer I");
		before.togglePlannerSnooze("train:Fishing:70");
		before.togglePlannerBan("fishing_barbarian");
		before.setPlannerPreferred("Agility", "agility_sepulchre");

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 9L);
		com.ironhub.engine.PlanConstraints constraints = after.plannerConstraints();
		assertTrue(constraints.pinned.contains("quest:Dragon Slayer I"));
		assertTrue(constraints.snoozed.contains("train:Fishing:70"));
		assertTrue(constraints.bannedMethods.contains("fishing_barbarian"));
		assertEquals("agility_sepulchre", constraints.preferredMethods.get("Agility"));

		// pin and snooze are mutually exclusive on the same action
		after.togglePlannerSnooze("quest:Dragon Slayer I");
		assertFalse(after.plannerConstraints().pinned.contains("quest:Dragon Slayer I"));
		assertTrue(after.plannerConstraints().snoozed.contains("quest:Dragon Slayer I"));
	}

	@Test
	public void mergePreviewCountsSharedWork()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 3L);
		GoalPlannerModule module = module(state);
		state.selectGoal("bowfa", true); // SotE chain enters the base plan

		com.ironhub.data.GoalsPack.Goal candidate = new com.ironhub.data.GoalsPack.Goal();
		candidate.setId("custom70agility");
		candidate.setName("Agility 70");
		com.ironhub.data.GoalsPack.Step step = new com.ironhub.data.GoalsPack.Step();
		step.setLabel("Agility to 70");
		step.setRequirement("skillb:Agility:70");
		candidate.setSteps(java.util.List.of(step));

		GoalPlannerModule.MergePreview preview = module.previewMerge(candidate);
		assertEquals(1, preview.steps);
		// bowfa already demands 70 Agility — the candidate is fully shared
		assertEquals(1, preview.shared);
		assertTrue(preview.addedHours < 0.5);
	}

	@Test
	public void tabRendersAllThreeViewsHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		StateFixture.stat(state, Skill.AGILITY, 56, Experience.getXpForLevel(56));
		StateFixture.stat(state, Skill.THIEVING, 44, Experience.getXpForLevel(44));
		state.selectGoal("bowfa", true);
		state.selectGoal("barrows_gloves", true);

		GoalPlannerModule module = module(state);
		JComponent tab = module.buildTab();
		assertNotNull(tab);

		// wait for the debounced replan to land, then push it into the tab
		for (int i = 0; i < 50 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
		final com.ironhub.engine.Plan plan = module.currentPlan();
		assertNotNull("no plan computed", plan);
		assertTrue(plan.steps.size() > 10);
		PlannerTab plannerTab = (PlannerTab) tab;
		javax.swing.SwingUtilities.invokeAndWait(() -> plannerTab.onPlanUpdated(plan));

		File reports = new File("build/reports");
		reports.mkdirs();
		// expand the first TRAIN step so the render shows the explain card
		plan.steps.stream()
			.filter(st -> st.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
			.findFirst()
			.ifPresent(st -> {
				try
				{
					javax.swing.SwingUtilities.invokeAndWait(
						() -> ((PlannerTab) tab).expandForTest(st.action.id));
				}
				catch (Exception ignored)
				{
				}
			});
		String[] names = {"planner-today.png", "planner-route.png", "planner-goals.png"};
		for (int view = 0; view < 3; view++)
		{
			final int v = view;
			javax.swing.SwingUtilities.invokeAndWait(() -> plannerTab.showViewForTest(v));
			BufferedImage image = SwingRender.render((JPanel) tab);
			assertTrue("view " + view + " too small: " + image.getHeight(),
				image.getHeight() > 120);
			javax.imageio.ImageIO.write(image, "png", new File(reports, names[view]));
		}
		module.shutDown();
	}

	@Test
	public void whatNowTopsFromThePlanHead() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 6L);
		state.selectGoal("bowfa", true);
		GoalPlannerModule module = module(state);
		for (int i = 0; i < 50 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
		assertNotNull(module.currentPlan());
		DataPack data = new DataPack(new Gson());
		com.ironhub.modules.suggest.WhatNowModule.Packs packs =
			new com.ironhub.modules.suggest.WhatNowModule.Packs(
				data.load("dailies", com.ironhub.data.DailiesPack.class),
				data.load("herb-patches", com.ironhub.data.HerbPatchesPack.class),
				data.load("banked-xp", com.ironhub.data.BankedXpPack.class),
				data.load("goals", com.ironhub.data.GoalsPack.class),
				data.load("gear-progression", com.ironhub.data.GearProgressionPack.class));
		java.util.List<com.ironhub.modules.suggest.WhatNowModule.Suggestion> suggestions =
			com.ironhub.modules.suggest.WhatNowModule.suggest(state, packs, 60);
		assertFalse(suggestions.isEmpty());
		assertTrue("plan head should top the list: " + suggestions.get(0).title,
			suggestions.get(0).title.startsWith("Plan: "));
		module.shutDown();
	}
}
