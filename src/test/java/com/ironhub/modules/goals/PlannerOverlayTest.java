package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.UiTokens;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlannerOverlayTest
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

	private static Dimension render(PlannerOverlay overlay, BufferedImage image)
	{
		Graphics2D g = image.createGraphics();
		g.setFont(FontManager.getRunescapeSmallFont());
		Dimension size = overlay.render(g);
		g.dispose();
		return size;
	}

	@Test
	public void hiddenWithoutAPlanAndWhenToggledOff() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		GoalPlannerModule module = module(state);
		BufferedImage image = new BufferedImage(300, 240, BufferedImage.TYPE_INT_ARGB);

		// no goals selected → empty plan → no overlay
		PlannerOverlay overlay = new PlannerOverlay(module, state, new IronHubConfig()
		{
		});
		for (int i = 0; i < 50 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
		assertNull(render(overlay, image));

		// toggled off → hidden even with a plan
		state.selectGoal("barrows_gloves", true);
		waitForSteps(module);
		PlannerOverlay off = new PlannerOverlay(module, state, new IronHubConfig()
		{
			@Override
			public boolean plannerOverlay()
			{
				return false;
			}
		});
		assertNull(render(off, image));
		module.shutDown();
	}

	@Test
	public void rendersTheHeadWithinTheOverlayBudget() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 22L);
		StateFixture.stat(state, Skill.AGILITY, 56, Experience.getXpForLevel(56));
		StateFixture.stat(state, Skill.CONSTRUCTION, 55, Experience.getXpForLevel(55));
		state.selectGoal("bowfa", true);
		state.selectGoal("barrows_gloves", true);
		GoalPlannerModule module = module(state);
		waitForSteps(module);

		PlannerOverlay overlay = new PlannerOverlay(module, state, new IronHubConfig()
		{
		});
		BufferedImage image = new BufferedImage(300, 240, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new java.awt.Color(0x35, 0x2F, 0x24)); // game-canvas stand-in
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.translate(10, 10);
		g.setFont(FontManager.getRunescapeSmallFont());
		Dimension size = overlay.render(g);
		g.dispose();
		assertNotNull("overlay hidden despite a plan head", size);
		assertTrue("overlay too wide: " + size.width,
			size.width <= UiTokens.OVERLAY_MAX_WIDTH);
		assertTrue("overlay too tall: " + size.height,
			size.height <= UiTokens.OVERLAY_MAX_HEIGHT);

		File reports = new File("build/reports");
		reports.mkdirs();
		javax.imageio.ImageIO.write(image, "png", new File(reports, "planner-overlay.png"));
		module.shutDown();
	}

	@Test
	public void rightClickSnoozeSinksTheHead() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 23L);
		state.selectGoal("barrows_gloves", true);
		GoalPlannerModule module = module(state);
		waitForSteps(module);

		PlannerOverlay overlay = new PlannerOverlay(module, state, new IronHubConfig()
		{
		});
		assertEquals(1, overlay.getMenuEntries().size()); // Snooze always offered
		String headId = module.currentPlan().head().action.id;
		overlay.snoozeHead();
		assertTrue(state.isPlannerSnoozed(headId));
		module.shutDown();
	}

	@Test
	public void removingAGoalNeverFlashesDone() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 24L);
		state.addCustomGoal("custom:skill:agility:5", "Agility 5", "skill:Agility:5");
		GoalPlannerModule module = module(state);
		waitForSteps(module);

		PlannerOverlay overlay = new PlannerOverlay(module, state, new IronHubConfig()
		{
		});
		BufferedImage image = new BufferedImage(300, 240, BufferedImage.TYPE_INT_ARGB);
		assertNotNull(render(overlay, image)); // head registered

		GoalPlannerModule.removeGoal(state, "custom:skill:agility:5");
		waitForEmptyPlan(module);
		// head vanished but was NOT completed — no flash, overlay hides
		assertNull(render(overlay, image));
		module.shutDown();
	}

	@Test
	public void completingTheHeadFlashesDone() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 25L);
		state.addCustomGoal("custom:skill:agility:5", "Agility 5", "skill:Agility:5");
		GoalPlannerModule module = module(state);
		waitForSteps(module);

		PlannerOverlay overlay = new PlannerOverlay(module, state, new IronHubConfig()
		{
		});
		BufferedImage image = new BufferedImage(300, 240, BufferedImage.TYPE_INT_ARGB);
		assertNotNull(render(overlay, image)); // head registered

		StateFixture.stat(state, Skill.AGILITY, 5, Experience.getXpForLevel(5));
		waitForEmptyPlan(module);
		// the head really completed — the green Done flash renders alone
		assertNotNull(render(overlay, image));
		module.shutDown();
	}

	private static void waitForEmptyPlan(GoalPlannerModule module) throws InterruptedException
	{
		for (int i = 0; i < 50; i++)
		{
			com.ironhub.engine.Plan plan = module.currentPlan();
			if (plan != null && plan.steps.isEmpty())
			{
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("plan never emptied");
	}

	@Test
	public void liveMathIsHonest()
	{
		// TRAIN with a rate: banked-aware xp over rate
		com.ironhub.engine.Action train = new com.ironhub.engine.Action(
			"train:Agility:70", com.ironhub.engine.Action.Kind.TRAIN, "Agility to 70");
		train.trainSkill = Skill.AGILITY;
		train.trainToLevel = 70;
		com.ironhub.engine.Plan.Step step = new com.ironhub.engine.Plan.Step(
			train, 4.0, "", "", "Rooftops", "m", "active", 50_000, 56, 100_000,
			java.util.List.of(), java.util.List.of(), false, false);
		assertEquals(2.0, PlannerOverlay.etaHours(step), 0.001);

		// no rate → the routed hours pass through, NaN stays honest
		com.ironhub.engine.Plan.Step unknown = new com.ironhub.engine.Plan.Step(
			train, Double.NaN, "", "", null, null, null, 0, 56, 100_000,
			java.util.List.of(), java.util.List.of(), false, false);
		assertTrue(Double.isNaN(PlannerOverlay.etaHours(unknown)));

		// progress bar: forward-only, clamped
		assertEquals(0.0, PlannerOverlay.stepFraction(100_000, 100_000), 0.001);
		assertEquals(0.25, PlannerOverlay.stepFraction(100_000, 75_000), 0.001);
		assertEquals(1.0, PlannerOverlay.stepFraction(100_000, 0), 0.001);
		assertEquals(1.0, PlannerOverlay.stepFraction(0, 0), 0.001);
		assertEquals(0.0, PlannerOverlay.stepFraction(100_000, 150_000), 0.001);
	}

	private static void waitForSteps(GoalPlannerModule module) throws InterruptedException
	{
		for (int i = 0; i < 50; i++)
		{
			com.ironhub.engine.Plan plan = module.currentPlan();
			if (plan != null && plan.head() != null)
			{
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("plan never produced a head");
	}
}
