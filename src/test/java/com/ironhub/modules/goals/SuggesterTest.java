package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.BoostsPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.DiariesPack;
import com.ironhub.data.EffectsPack;
import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.engine.EnginePacks;
import com.ironhub.engine.Plan;
import com.ironhub.engine.PlanConstraints;
import com.ironhub.engine.PlannerService;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2 G6: the stepping-stone suggester — reproducible for a fixed
 * fixture, benefit-first, and honest (no savings claim backed by an
 * unknown cost; only pack-sourced life-easier copy).
 */
public class SuggesterTest
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
			DATA.load("boosts", BoostsPack.class),
			DATA.load("diaries", DiariesPack.class));
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

	private List<Suggester.Suggestion> suggest(AccountState state, List<GoalsPack.Goal> base)
	{
		return suggest(state, base, java.util.Set.of());
	}

	private List<Suggester.Suggestion> suggest(AccountState state, List<GoalsPack.Goal> base,
		java.util.Set<String> dismissed)
	{
		EnginePacks p = packs();
		BankedXpPack bx = DATA.load("banked-xp", BankedXpPack.class);
		Plan basePlan = PlannerService.plan(state, p, bx, base, PlanConstraints.none());
		return Suggester.compute(base, state, p, bx, PlanConstraints.none(), basePlan, dismissed);
	}

	/** Accepting an offer (custom:effect:<id> now a goal) or dismissing its
	 *  key removes it from the next compute; another candidate fills in. */
	@Test
	public void acceptedAndDismissedOffersLeaveTheList()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		List<GoalsPack.Goal> base = List.of(goal("g", "quest:Song of the Elves"));
		List<Suggester.Suggestion> before = suggest(state, base);
		Suggester.Suggestion fairy = before.stream()
			.filter(x -> "Fairy rings".equals(x.name)).findFirst().orElse(null);
		assertTrue(fairy != null);

		// dismissed: gone by key
		List<Suggester.Suggestion> afterDismiss = suggest(state, base, java.util.Set.of(fairy.key()));
		assertTrue(afterDismiss.stream().noneMatch(x -> "Fairy rings".equals(x.name)));

		// accepted: tracking the unlock as a goal retires the offer even
		// though the effect itself is still unmet (Luke's Prifddinas report)
		String effectId = fairy.goalId.substring("suggest:effect:".length());
		List<GoalsPack.Goal> withAccepted = new ArrayList<>(base);
		withAccepted.add(goal("custom:effect:" + effectId, "unlock:" + effectId));
		List<Suggester.Suggestion> afterAccept = suggest(state, withAccepted);
		assertTrue(afterAccept.stream().noneMatch(x -> "Fairy rings".equals(x.name)));
	}

	/** Two Routes sharing a big requirement chain get a merge offer. */
	@Test
	public void mergeOfferForOverlappingRoutes()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		List<Suggester.Suggestion> s = suggest(state,
			List.of(goal("bowfa", "quest:Song of the Elves"), goal("qc", "quest:Dragon Slayer II")));
		Suggester.Suggestion merge = s.stream().filter(x -> x.kind.equals("merge"))
			.findFirst().orElse(null);
		assertTrue("overlapping routes should offer a merge", merge != null);
		assertEquals(java.util.List.of("bowfa", "qc"), merge.mergeGoalIds);
		assertTrue("merge copy names the shared count", merge.benefit.contains("tasks — combine"));
	}

	/** A life-easier card carries the effect's PACK note verbatim — never
	 *  an invented benefit — and makes no hours claim. */
	@Test
	public void lifeEasierUsesPackCopyNeverInvented()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		List<Suggester.Suggestion> s = suggest(state,
			List.of(goal("g", "quest:Song of the Elves")));
		Suggester.Suggestion fairy = s.stream()
			.filter(x -> "Fairy rings".equals(x.name)).findFirst().orElse(null);
		assertTrue("the classic force-multiplier should surface", fairy != null);
		assertEquals("life-easier", fairy.kind);
		assertEquals("near-universal travel network; the single best discount-per-hour unlock",
			fairy.benefit);
		assertTrue("life-easier makes no hours claim", Double.isNaN(fairy.netHours));
	}

	/** The honesty invariant: a time-saver's benefit is backed by a real
	 *  negative net; a life-easier never claims hours and never invents copy. */
	@Test
	public void everySuggestionIsHonest()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		List<Suggester.Suggestion> s = suggest(state,
			List.of(goal("a", "quest:Song of the Elves"), goal("b", "quest:Dragon Slayer II")));
		for (Suggester.Suggestion x : s)
		{
			assertTrue(x.benefit != null && !x.benefit.isEmpty());
			if ("time-saver".equals(x.kind))
			{
				assertTrue("a time-saver must actually save known hours", x.netHours < 0);
			}
			else if ("life-easier".equals(x.kind))
			{
				assertTrue("life-easier never claims hours", Double.isNaN(x.netHours));
			}
		}
	}

	/** An effect the player already has is never suggested. */
	@Test
	public void alreadyOwnedEffectsAreNotSuggested()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// fairy rings active (Fairytale II started)
		StateFixture.quest(state, net.runelite.api.Quest.FAIRYTALE_II__CURE_A_QUEEN,
			net.runelite.api.QuestState.IN_PROGRESS);
		List<Suggester.Suggestion> s = suggest(state,
			List.of(goal("g", "quest:Song of the Elves")));
		assertFalse("an active effect must not be re-suggested",
			s.stream().anyMatch(x -> x.goalId != null && x.goalId.contains("fairy_rings")));
	}

	/** Same fixture → identical suggestions (reproducible, cap ≤ 5). */
	@Test
	public void reproducibleAndCapped()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		List<GoalsPack.Goal> base = List.of(goal("a", "quest:Song of the Elves"),
			goal("b", "quest:Dragon Slayer II"));
		List<Suggester.Suggestion> first = suggest(state, base);
		List<Suggester.Suggestion> second = suggest(state, base);
		assertEquals(first.size(), second.size());
		assertTrue("never more than the cap", first.size() <= Suggester.MAX_SUGGESTIONS);
		for (int i = 0; i < first.size(); i++)
		{
			assertEquals(first.get(i).kind, second.get(i).kind);
			assertEquals(first.get(i).name, second.get(i).name);
			assertEquals(first.get(i).benefit, second.get(i).benefit);
		}
	}

	/** No routes → no suggestions (nothing to advance). */
	@Test
	public void noGoalsNoSuggestions()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertTrue(suggest(state, List.of()).isEmpty());
	}
}
