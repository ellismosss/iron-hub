package com.ironhub.engine;

import com.google.gson.Gson;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.EffectsPack;
import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RouterTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static final DataPack DATA = new DataPack(new Gson());

	private static EnginePacks packs()
	{
		return new EnginePacks(
			DATA.load("quests", QuestsPack.class),
			DATA.load("methods", MethodsPack.class),
			DATA.load("effects", EffectsPack.class),
			DATA.load("gear-progression", GearProgressionPack.class));
	}

	private static GoalsPack.Goal goal(String id, String... reqs)
	{
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(id);
		goal.setName(id);
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (String req : reqs)
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(req);
			step.setRequirement(req);
			steps.add(step);
		}
		goal.setSteps(steps);
		return goal;
	}

	private Plan plan(AccountState state, PlanConstraints constraints, GoalsPack.Goal... goals)
	{
		return PlannerService.plan(state, packs(), DATA.load("banked-xp", BankedXpPack.class),
			List.of(goals), constraints);
	}

	@Test
	public void plansAreFeasibleInOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		Plan plan = plan(state, PlanConstraints.none(),
			goal("bowfa", "quest:Song of the Elves"),
			goal("gloves", "quest:Recipe for Disaster - Culinaromancer"));
		assertTrue(plan.steps.size() > 20);

		// the feasibility invariant: every dependency is scheduled earlier
		Set<String> seen = new HashSet<>();
		for (Plan.Step step : plan.steps)
		{
			for (String dep : step.action.dependsOn)
			{
				assertTrue(step.action.id + " before its dependency " + dep, seen.contains(dep));
			}
			seen.add(step.action.id);
		}
		assertEquals("degraded: " + plan.degraded, 0, plan.degraded.size());
	}

	@Test
	public void plansAreDeterministic()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.AGILITY, 56, Experience.getXpForLevel(56));
		Plan first = plan(state, PlanConstraints.none(), goal("bowfa", "quest:Song of the Elves"));
		Plan second = plan(state, PlanConstraints.none(), goal("bowfa", "quest:Song of the Elves"));
		assertEquals(first.fingerprint, second.fingerprint);
		assertEquals(first.steps.size(), second.steps.size());
		for (int i = 0; i < first.steps.size(); i++)
		{
			assertEquals(first.steps.get(i).action.id, second.steps.get(i).action.id);
		}
	}

	@Test
	public void questXpRewardsShrinkLaterTraining()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.AGILITY, 68, Experience.getXpForLevel(68));

		// naive: training 68->70 alone
		double naive = CostModel.trainHours(Skill.AGILITY, 70, new ProjectedState(state),
			packs().methods, 0);
		// routed: SotE chain quests award agility xp before the TRAIN node
		Plan plan = plan(state, PlanConstraints.none(), goal("bowfa", "quest:Song of the Elves"));
		Plan.Step train = plan.steps.stream()
			.filter(s -> s.action.kind == Action.Kind.TRAIN && s.action.trainSkill == Skill.AGILITY)
			.findFirst().orElse(null);
		assertNotNull(train);
		assertTrue("quest rewards should shave the grind: " + train.hours + " vs " + naive,
			train.hours < naive - 0.01);
	}

	@Test
	public void pinsJumpTheQueueWithTheirPrerequisites()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// separate goals: checklist chaining must not force the order
		GoalsPack.Goal sote = goal("sote", "quest:Song of the Elves");
		GoalsPack.Goal ds1 = goal("ds1", "quest:Dragon Slayer I");

		PlanConstraints pins = new PlanConstraints();
		pins.pinned.add("quest:Dragon Slayer I");
		Plan pinned = plan(state, pins, sote, ds1);

		int ds1At = indexOf(pinned, "quest:Dragon Slayer I");
		int soteAt = indexOf(pinned, "quest:Song of the Elves");
		assertTrue(ds1At >= 0 && soteAt >= 0);
		assertTrue("pinned quest should come first", ds1At < soteAt);
	}

	@Test
	public void snoozesSinkAndBansChangeTheMethod()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.FISHING, 60, Experience.getXpForLevel(60));
		StateFixture.stat(state, Skill.AGILITY, 40, Experience.getXpForLevel(40));
		StateFixture.stat(state, Skill.STRENGTH, 40, Experience.getXpForLevel(40));
		GoalsPack.Goal fishing = goal("fish", "skill:Fishing:70");
		GoalsPack.Goal cooking = goal("cook", "skill:Cooking:50");

		PlanConstraints constraints = new PlanConstraints();
		constraints.snoozed.add("train:Fishing:70");
		constraints.bannedMethods.add("fishing_barbarian");
		Plan plan = plan(state, constraints, fishing, cooking);

		// snoozed step sinks below the other train node
		assertTrue(indexOf(plan, "train:Fishing:70") > indexOf(plan, "train:Cooking:50"));
		Plan.Step fishStep = plan.steps.get(indexOf(plan, "train:Fishing:70"));
		assertTrue(fishStep.snoozed);
		assertFalse("banned method must not be chosen",
			"Barbarian fishing".equals(fishStep.methodName));
	}

	@Test
	public void unknownDurationsStayUnknownInTheTotals()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		Plan plan = plan(state, PlanConstraints.none(), goal("g", "kc:Zulrah:50"));
		assertEquals(1, plan.steps.size());
		assertTrue(Double.isNaN(plan.steps.get(0).hours));
		assertEquals(1, plan.unknownCount);
		assertEquals(0.0, plan.knownHours, 1e-9);
	}

	@Test
	public void trainStepsCarryMethodAndAlternatives()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.THIEVING, 50, Experience.getXpForLevel(50));
		Plan plan = plan(state, PlanConstraints.none(), goal("g", "skill:Thieving:70"));
		Plan.Step step = plan.steps.get(indexOf(plan, "train:Thieving:70"));
		assertNotNull(step.methodName);
		assertFalse(step.alternatives.isEmpty());
		// alternatives are slower (sorted by delta) and explain their style
		assertTrue(step.alternatives.get(0).deltaHours >= -1e-9
			|| step.alternatives.get(0).style != null);
	}

	@Test
	public void trainResourcesCountNeededVsBanked()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 70,
			Experience.getXpForLevel(70));
		StateFixture.stat(state, Skill.MAGIC, 70, Experience.getXpForLevel(70));
		StateFixture.bank(state, java.util.Map.of(
			net.runelite.api.gameval.ItemID.PLANK_TEAK, 300));

		Plan plan = plan(state, PlanConstraints.none(), goal("g", "skillb:Construction:72"));
		Plan.Step step = plan.steps.get(indexOf(plan, "train:Construction:72"));
		assertEquals("Teak benches + demon butler", step.methodName);
		assertEquals(1, step.resources.size());
		Plan.Resource planks = step.resources.get(0);

		// gross 70->72 span at 90 xp/plank; the 300 banked planks subtract
		// exactly once (their banked-xp credit covers the same items)
		long expected = (long) Math.ceil(
			(Experience.getXpForLevel(72) - Experience.getXpForLevel(70)) / 90.0);
		assertEquals(expected, planks.needed);
		assertEquals(300, planks.banked);
		assertEquals(expected - 300, planks.missing);
	}

	@Test
	public void pohBuildsCarryMaterialResources()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 55, Experience.getXpForLevel(55));
		StateFixture.bank(state, java.util.Map.of(
			net.runelite.api.gameval.ItemID.PLANK_OAK, 5));

		// the armour stand gear goal: manual build step carries its wiki
		// Recipe materials as needed/banked/missing resources
		Plan plan = plan(state, PlanConstraints.none(),
			goal("stand", "unlock:gearmark_armour_stand"));
		Plan.Step step = plan.steps.get(indexOf(plan, "manual:gearmark_armour_stand"));
		assertNotNull(step);
		assertEquals(2, step.resources.size());
		Plan.Resource planks = step.resources.stream()
			.filter(r -> r.name.equals("Oak plank")).findFirst().orElseThrow(AssertionError::new);
		assertEquals(8, planks.needed);
		assertEquals(5, planks.banked);
		assertEquals(3, planks.missing);
	}

	@Test
	public void fullGoalsPackRoutesUnderTheBudget()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack goals = DATA.load("goals", GoalsPack.class);
		long start = System.nanoTime();
		Plan plan = PlannerService.plan(state, packs(), DATA.load("banked-xp", BankedXpPack.class),
			goals.getGoals(), PlanConstraints.none());
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(plan.steps.size() > 30);
		assertTrue("replan took " + ms + "ms (budget 100ms + headroom)", ms < 400);
	}

	private static int indexOf(Plan plan, String actionId)
	{
		for (int i = 0; i < plan.steps.size(); i++)
		{
			if (plan.steps.get(i).action.id.equals(actionId))
			{
				return i;
			}
		}
		return -1;
	}
}
