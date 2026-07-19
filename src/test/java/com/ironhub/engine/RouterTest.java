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
			DATA.load("gear-progression", GearProgressionPack.class),
			DATA.load("boosts", com.ironhub.data.BoostsPack.class),
			DATA.load("diaries", com.ironhub.data.DiariesPack.class));
	}

	private static EnginePacks packsWith(com.ironhub.data.ClogPack clog)
	{
		return new EnginePacks(
			DATA.load("quests", QuestsPack.class),
			DATA.load("methods", MethodsPack.class),
			DATA.load("effects", EffectsPack.class),
			DATA.load("gear-progression", GearProgressionPack.class),
			DATA.load("boosts", com.ironhub.data.BoostsPack.class),
			DATA.load("diaries", com.ironhub.data.DiariesPack.class),
			clog);
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
		// no clog pack → no rate source → a kc: step is honestly unknown
		AccountState state = StateFixture.state(temp.getRoot());
		Plan plan = plan(state, PlanConstraints.none(), goal("g", "kc:Zulrah:50"));
		assertEquals(1, plan.steps.size());
		assertTrue(Double.isNaN(plan.steps.get(0).hours));
		assertEquals(1, plan.unknownCount);
		assertEquals(0.0, plan.knownHours, 1e-9);
	}

	/** With a clog pack loaded, a drop-gated OBTAIN step gets real expected
	 *  hours + a P90 spread from the bundled rates instead of "?" (G3). */
	@Test
	public void clogRatesCostDropStepsWithSpread()
	{
		com.ironhub.data.ClogPack.Item drop = new com.ironhub.data.ClogPack.Item();
		drop.itemId = 90_000_001; // not in the gear pack → clog rate fills it
		drop.name = "Test drop";
		drop.attempts = 50;
		com.ironhub.data.ClogPack.Activity activity = new com.ironhub.data.ClogPack.Activity();
		activity.name = "Test boss";
		activity.perHour = 10;
		activity.reqs = List.of();
		activity.items = new ArrayList<>(List.of(drop));
		com.ironhub.data.ClogPack clog = new com.ironhub.data.ClogPack();
		clog.activities = new ArrayList<>(List.of(activity));
		clog.aliases = List.of();
		clog.slots = List.of();
		clog.chatNames = List.of();

		AccountState state = StateFixture.state(temp.getRoot());
		Plan plan = PlannerService.plan(state, packsWith(clog),
			DATA.load("banked-xp", BankedXpPack.class),
			List.of(goal("g", "item:90000001")), PlanConstraints.none());

		Plan.Step obtain = plan.steps.get(0);
		assertEquals(5.0, obtain.hours, 1e-6);          // 50 / 10
		assertEquals(11.4, obtain.spreadHours, 1e-6);   // P90 114 / 10
		assertEquals(0, plan.unknownCount);
		assertTrue(plan.knownHours > 0);
	}

	/** A kc: step matched to a clog activity by name gets kills/hr hours. */
	@Test
	public void clogRatesCostKillSteps()
	{
		com.ironhub.data.ClogPack.Activity zulrah = new com.ironhub.data.ClogPack.Activity();
		zulrah.name = "Zulrah";
		zulrah.perHour = 25;
		zulrah.reqs = List.of();
		zulrah.items = new ArrayList<>();
		com.ironhub.data.ClogPack clog = new com.ironhub.data.ClogPack();
		clog.activities = new ArrayList<>(List.of(zulrah));
		clog.aliases = List.of();
		clog.slots = List.of();
		clog.chatNames = List.of();

		AccountState state = StateFixture.state(temp.getRoot());
		Plan plan = PlannerService.plan(state, packsWith(clog),
			DATA.load("banked-xp", BankedXpPack.class),
			List.of(goal("g", "kc:Zulrah:50")), PlanConstraints.none());
		assertEquals(2.0, plan.steps.get(0).hours, 1e-6); // 50 / 25
		assertEquals(0, plan.unknownCount);
	}

	/** Priority tiers (G5): a High goal's steps front-load, a Someday goal's
	 *  UNIQUE steps sink to the back. */
	@Test
	public void priorityTiersWeightTheOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		PlanConstraints c = PlanConstraints.none();
		c.goalPriority.put("hi", "high");
		c.goalPriority.put("lo", "someday");
		Plan plan = plan(state, c,
			goal("hi", "skill:Agility:10"), goal("lo", "skill:Woodcutting:10"));
		assertTrue("the someday goal's step must sink behind the high goal's",
			indexOf(plan, "train:Agility:10") < indexOf(plan, "train:Woodcutting:10"));
	}

	/** G8 iron-first honesty: a UIM has no bank, so banked materials must not
	 *  discount its plan — the same Prayer goal costs MORE for a UIM than for a
	 *  normal iron holding the same banked bones. */
	@Test
	public void ultimateIronmanGetsNoBankedXpDiscount()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.PRAYER, 40, Experience.getXpForLevel(40));
		StateFixture.bank(state, java.util.Map.of(526, 8000)); // 8k bones = 36k banked Prayer xp

		double normalHours = plan(state, PlanConstraints.none(),
			goal("pray", "skill:Prayer:50")).knownHours;

		StateFixture.varbit(state, net.runelite.api.Varbits.ACCOUNT_TYPE, 2); // ULTIMATE_IRONMAN
		assertTrue("detected as UIM", state.isUltimateIronman());
		double uimHours = plan(state, PlanConstraints.none(),
			goal("pray", "skill:Prayer:50")).knownHours;

		assertTrue("banked bones must not discount a UIM plan (" + uimHours + " vs " + normalHours + ")",
			uimHours > normalHours);
	}

	/** A step shared with a Someday goal keeps FULL value (max tier wins), so
	 *  the dedupe keeps the someday goal in the graph — its ×N badge stands. */
	@Test
	public void somedayGoalsStayInTheMergedGraph()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		PlanConstraints c = PlanConstraints.none();
		c.goalPriority.put("b", "someday");
		Plan plan = plan(state, c,
			goal("a", "skill:Agility:20"), goal("b", "skill:Agility:20"));
		Plan.Step shared = plan.steps.get(indexOf(plan, "train:Agility:20"));
		assertTrue("the shared step must still serve both goals",
			shared.action.neededBy.containsAll(java.util.List.of("a", "b")));
	}

	/** Goal pins (G5): a pinned goal's steps jump the queue; among pinned
	 *  goals, pin order decides. */
	@Test
	public void pinnedGoalsRouteFirstInPinOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// pin p1 (Woodcutting) before p2 (Agility): Woodcutting must lead even
		// though "Agility" sorts first without pins
		PlanConstraints c = PlanConstraints.none();
		c.pinnedGoals.add("p1");
		c.pinnedGoals.add("p2");
		Plan plan = plan(state, c,
			goal("p1", "skill:Woodcutting:10"), goal("p2", "skill:Agility:10"),
			goal("free", "skill:Thieving:10"));
		assertTrue("pinned goals lead", indexOf(plan, "train:Woodcutting:10")
			< indexOf(plan, "train:Thieving:10"));
		assertTrue("earlier-pinned goal wins the tie",
			indexOf(plan, "train:Woodcutting:10") < indexOf(plan, "train:Agility:10"));

		// reverse the pin order → Agility now leads
		PlanConstraints r = PlanConstraints.none();
		r.pinnedGoals.add("p2");
		r.pinnedGoals.add("p1");
		Plan flipped = plan(state, r,
			goal("p1", "skill:Woodcutting:10"), goal("p2", "skill:Agility:10"));
		assertTrue("pin order flips the lead",
			indexOf(flipped, "train:Agility:10") < indexOf(flipped, "train:Woodcutting:10"));
	}

	/** Tiers + pins stay deterministic — same inputs, byte-identical plan. */
	@Test
	public void priorityPlansAreDeterministic()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		PlanConstraints c = PlanConstraints.none();
		c.goalPriority.put("a", "high");
		c.goalPriority.put("b", "someday");
		c.pinnedGoals.add("a");
		GoalsPack.Goal a = goal("a", "skill:Agility:20");
		GoalsPack.Goal b = goal("b", "skill:Thieving:20");
		assertEquals(plan(state, c, a, b).fingerprint, plan(state, c, a, b).fingerprint);
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
	public void qolObtainStepsCarrySourcedHours()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 50, Experience.getXpForLevel(50));
		// plank sack: 350 carpenter points, wiki-sourced ~3.5h expectation
		Plan plan = plan(state, PlanConstraints.none(),
			goal("sack", "item:" + net.runelite.api.gameval.ItemID.PLANK_SACK));
		Plan.Step step = plan.steps.get(indexOf(plan, "obtain:plank_sack"));
		assertNotNull(step);
		assertEquals(3.5, step.hours, 0.01);
		assertTrue(step.why.contains("carpenter points"));
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
