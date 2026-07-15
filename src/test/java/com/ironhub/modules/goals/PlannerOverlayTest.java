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
		render(overlay, image); // register the head, first gauge sight

		// simulate live xp drops so the session block (gained, xp/hr,
		// actions left, moving bar) is part of the reviewed render
		com.ironhub.engine.Plan.Step head = module.currentPlan().head();
		if (head.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
		{
			Skill skill = head.action.trainSkill;
			long xp = state.getXp(skill);
			int level = state.getRealLevel(skill);
			for (int drop = 1; drop <= 3; drop++)
			{
				Thread.sleep(350);
				StateFixture.stat(state, skill, level, (int) xp + drop * 90);
				render(overlay, image);
			}
		}

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
		com.ironhub.engine.Action train = new com.ironhub.engine.Action(
			"train:Agility:70", com.ironhub.engine.Action.Kind.TRAIN, "Agility to 70");
		train.trainSkill = Skill.AGILITY;
		train.trainToLevel = 70;
		com.ironhub.engine.Plan.Step step = new com.ironhub.engine.Plan.Step(
			train, 4.0, "", "", "Rooftops", "m", "active", 50_000, 56, 100_000,
			java.util.List.of(), java.util.List.of(), false, false);

		// measured pace wins while xp flows; falls back to the pack rate
		assertEquals(0.5, PlannerOverlay.ttlHours(step, 200_000), 0.001);
		assertEquals(2.0, PlannerOverlay.ttlHours(step, Double.NaN), 0.001);

		// no rate at all → the routed hours pass through, NaN stays honest
		com.ironhub.engine.Plan.Step unknown = new com.ironhub.engine.Plan.Step(
			train, Double.NaN, "", "", null, null, null, 0, 56, 100_000,
			java.util.List.of(), java.util.List.of(), false, false);
		assertTrue(Double.isNaN(PlannerOverlay.ttlHours(unknown, Double.NaN)));

		// bar: live-xp progress from the session anchor, forward-only, clamped
		assertEquals(0.0, PlannerOverlay.stepFraction(100_000, 100_000, 200_000), 0.001);
		assertEquals(0.5, PlannerOverlay.stepFraction(100_000, 150_000, 200_000), 0.001);
		assertEquals(1.0, PlannerOverlay.stepFraction(100_000, 250_000, 200_000), 0.001);
		assertEquals(0.0, PlannerOverlay.stepFraction(100_000, 50_000, 200_000), 0.001);
		assertEquals(1.0, PlannerOverlay.stepFraction(200_000, 100_000, 200_000), 0.001);
	}

	@Test
	public void theBarSurvivesBanking()
	{
		// banking replans and shifts banked-xp credit, but live xp is
		// untouched — the anchor is per step id and first-write-wins
		PlannerOverlay overlay = new PlannerOverlay(null, null, null);
		assertEquals(1_000_000, overlay.anchorFor("train:Construction:72", 1_000_000));
		assertEquals(1_000_000, overlay.anchorFor("train:Construction:72", 1_004_500));
		// same live xp → same fraction no matter what the plan recomputed
		double before = PlannerOverlay.stepFraction(1_000_000, 1_050_000, 1_100_000);
		double after = PlannerOverlay.stepFraction(
			overlay.anchorFor("train:Construction:72", 1_050_000), 1_050_000, 1_100_000);
		assertEquals(before, after, 0.0001);
	}

	@Test
	public void xpGaugeMeasuresTheSession()
	{
		PlannerOverlay.XpGauge gauge = new PlannerOverlay.XpGauge();
		long t0 = 1_000_000;
		gauge.observe(Skill.AGILITY, 1_000, t0);           // first sight
		gauge.observe(Skill.AGILITY, 1_000, t0 + 1_000);   // idle
		assertEquals(0, gauge.gained());

		gauge.observe(Skill.AGILITY, 1_050, t0 + 2_000);   // first drop
		assertEquals(50, gauge.gained());
		assertTrue("one drop is not a rate", Double.isNaN(gauge.xpPerHour()));

		gauge.observe(Skill.AGILITY, 1_100, t0 + 62_000);  // +50 after 60s
		assertEquals(100, gauge.gained());
		assertEquals(3_000.0, gauge.xpPerHour(), 1);
		assertEquals(50, gauge.medianDrop());

		// a 10-minute break counts as only the 3-minute cap
		gauge.observe(Skill.AGILITY, 1_150, t0 + 662_000);
		assertEquals(1_500.0, gauge.xpPerHour(), 1);

		// switching skills starts a fresh session
		gauge.observe(Skill.FLETCHING, 9_000, t0 + 700_000);
		assertEquals(0, gauge.gained());
		assertTrue(Double.isNaN(gauge.xpPerHour()));
	}

	@Test
	public void dropsIdentifyTheRealMethod()
	{
		com.ironhub.data.MethodsPack pack = new DataPack(new Gson())
			.load("methods", com.ironhub.data.MethodsPack.class);

		// teak bench drop = 6 planks x 90 xp — a clean multiple names it
		assertEquals("construction_teak_benches",
			PlannerOverlay.matchMethod(pack, Skill.CONSTRUCTION, 540).id);
		assertEquals("construction_oak_larders",
			PlannerOverlay.matchMethod(pack, Skill.CONSTRUCTION, 480).id);
		assertEquals("prayer_chaos_altar",
			PlannerOverlay.matchMethod(pack, Skill.PRAYER, 252).id);
		// no xpEach data in the ladder → no invented name
		assertNull(PlannerOverlay.matchMethod(pack, Skill.FLETCHING, 15));
		assertNull(PlannerOverlay.matchMethod(pack, Skill.CONSTRUCTION, 0));
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
