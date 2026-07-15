package com.ironhub.engine;

import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Orders the merged DAG into a feasible, explained plan (ENGINE-DESIGN
 * §6). Greedy value-density over a deterministic forward simulation:
 * every pick applies the action's effects to the projection, so quest xp
 * rewards, unlocked methods and travel discounts genuinely shrink later
 * steps. Pins jump the queue (with their prerequisites), snoozes sink,
 * bans/preferences flow into the cost model. Deterministic: same inputs,
 * same plan, byte for byte.
 */
public class Router
{
	/** NaN durations sort as this many hours (display stays "unknown"). */
	static final double UNKNOWN_HOURS_SURROGATE = 8.0;

	private final StateView base;
	private final EnginePacks packs;
	private final PlanConstraints constraints;
	private final Map<Skill, Long> bankedXp;

	public Router(StateView base, EnginePacks packs, PlanConstraints constraints,
		Map<Skill, Long> bankedXp)
	{
		this.base = base;
		this.packs = packs;
		this.constraints = constraints;
		this.bankedXp = new HashMap<>(bankedXp);
	}

	public Plan route(ActionDag dag)
	{
		Plan plan = new Plan();
		plan.degraded.addAll(dag.degraded);
		ProjectedState projection = new ProjectedState(base);
		Map<Skill, Long> bankRemaining = new HashMap<>(bankedXp);

		Set<String> pinnedClosure = pinnedClosure(dag);
		Map<String, Action> pending = new LinkedHashMap<>();
		dag.nodes().forEach(node -> pending.put(node.id, node));
		Set<String> done = new HashSet<>();
		// xp still arriving from pending quests, per skill: training a skill
		// before its quest rewards land forfeits those hours (score penalty)
		Map<Skill, Long> pendingQuestXp = new HashMap<>();
		for (Action node : dag.nodes())
		{
			if (node.kind == Action.Kind.QUEST && !node.startOnly)
			{
				QuestsPack.QuestEntry entry = packs.quest(node.questName);
				if (entry != null)
				{
					entry.xp.forEach((skillName, xp) ->
					{
						Skill skill = skillByName(skillName);
						if (skill != null)
						{
							pendingQuestXp.merge(skill, (long) xp, Long::sum);
						}
					});
				}
			}
		}

		while (!pending.isEmpty())
		{
			List<Action> ready = new ArrayList<>();
			for (Action node : pending.values())
			{
				if (done.containsAll(node.dependsOn))
				{
					ready.add(node);
				}
			}
			if (ready.isEmpty())
			{
				// should be impossible post breakCycles; degrade loudly
				plan.degraded.add("router stalled with " + pending.size() + " nodes");
				break;
			}
			Action pick = pickBest(ready, projection, bankRemaining, pinnedClosure, pendingQuestXp);
			pending.remove(pick.id);
			done.add(pick.id);
			if (pick.kind == Action.Kind.QUEST && !pick.startOnly)
			{
				QuestsPack.QuestEntry entry = packs.quest(pick.questName);
				if (entry != null)
				{
					entry.xp.forEach((skillName, xp) ->
					{
						Skill skill = skillByName(skillName);
						if (skill != null)
						{
							pendingQuestXp.merge(skill, (long) -xp, Long::sum);
						}
					});
				}
			}
			plan.steps.add(executeAndExplain(pick, projection, bankRemaining, dag));
		}

		for (Plan.Step step : plan.steps)
		{
			if (Double.isNaN(step.hours))
			{
				plan.unknownCount++;
			}
			else
			{
				plan.knownHours += step.hours;
			}
		}
		plan.fingerprint = fingerprint(plan);
		return plan;
	}

	// ── scoring ────────────────────────────────────────────────────────

	private Action pickBest(List<Action> ready, ProjectedState projection,
		Map<Skill, Long> bankRemaining, Set<String> pinnedClosure, Map<Skill, Long> pendingQuestXp)
	{
		Action best = null;
		double bestScore = -1;
		for (Action node : ready)
		{
			double score = score(node, projection, bankRemaining, pinnedClosure, pendingQuestXp);
			if (best == null || score > bestScore + 1e-9
				|| (Math.abs(score - bestScore) <= 1e-9 && tieBreak(node, best) < 0))
			{
				best = node;
				bestScore = score;
			}
		}
		return best;
	}

	private double score(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining, Set<String> pinnedClosure, Map<Skill, Long> pendingQuestXp)
	{
		double hours = duration(node, projection, bankRemaining, true);
		double surrogate = Double.isNaN(hours) ? UNKNOWN_HOURS_SURROGATE : Math.max(hours, 0.05);
		double value = 0.5
			+ 0.2 * (node.neededBy.size() - 1)
			+ discountHours(node, projection);
		if (node.kind == Action.Kind.TRAIN)
		{
			// forfeit: pending quest rewards would have paid part of this grind
			long rewards = pendingQuestXp.getOrDefault(node.trainSkill, 0L);
			MethodsPack.Method method = CostModel.currentMethod(node.trainSkill, projection,
				packs.methods, constraints.bannedMethods, constraints.preferredMethods);
			if (rewards > 0 && method != null && method.rate > 0)
			{
				double forfeit = Math.min(rewards / (double) method.rate, surrogate);
				value -= 0.5 * forfeit;
			}
		}
		double score = value / surrogate;
		if (pinnedClosure.contains(node.id))
		{
			score += 1_000;
		}
		if (constraints.snoozed.contains(node.id))
		{
			score *= 0.001;
		}
		return score;
	}

	/** Hours this action's effects shave off the rest of the plan. */
	private double discountHours(Action node, ProjectedState projection)
	{
		double saved = 0;
		if (node.kind == Action.Kind.QUEST)
		{
			QuestsPack.QuestEntry entry = packs.quest(node.questName);
			if (entry != null && !node.startOnly)
			{
				// xp rewards genuinely shrink TRAIN nodes still pending
				for (Map.Entry<String, Integer> reward : entry.xp.entrySet())
				{
					Skill skill = skillByName(reward.getKey());
					MethodsPack.Method method = skill == null ? null
						: CostModel.currentMethod(skill, projection, packs.methods,
							constraints.bannedMethods, constraints.preferredMethods);
					if (method != null && method.rate > 0)
					{
						saved += reward.getValue() / (double) method.rate;
					}
				}
			}
			// travel-effect activation: cheaper future quests
			ProjectedState after = projection.branch();
			applyEffects(node, after, new HashMap<>());
			double factorNow = CostModel.travelFactor(projection, packs.effects);
			double factorAfter = CostModel.travelFactor(after, packs.effects);
			if (factorAfter < factorNow - 1e-9)
			{
				saved += (factorNow - factorAfter) * 4.0; // ~4h of errands ahead, coarse
			}
		}
		return saved;
	}

	/** Route prior: earlier OQG position wins ties; then lexicographic id. */
	private int tieBreak(Action a, Action b)
	{
		int pa = priorOrder(a);
		int pb = priorOrder(b);
		if (pa != pb)
		{
			return Integer.compare(pa, pb);
		}
		return a.id.compareTo(b.id);
	}

	private int priorOrder(Action node)
	{
		if (node.kind != Action.Kind.QUEST)
		{
			return Integer.MAX_VALUE;
		}
		QuestsPack.QuestEntry entry = packs.quest(node.questName);
		return entry == null || entry.order < 0 ? Integer.MAX_VALUE : entry.order;
	}

	/** Pinned nodes plus everything they transitively depend on. */
	private Set<String> pinnedClosure(ActionDag dag)
	{
		Set<String> closure = new HashSet<>();
		for (String id : constraints.pinned)
		{
			addClosure(dag, id, closure);
		}
		return closure;
	}

	private void addClosure(ActionDag dag, String id, Set<String> closure)
	{
		Action node = dag.get(id);
		if (node == null || !closure.add(id))
		{
			return;
		}
		for (String dep : node.dependsOn)
		{
			addClosure(dag, dep, closure);
		}
	}

	// ── simulation + explanation ──────────────────────────────────────

	private Plan.Step executeAndExplain(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining, ActionDag dag)
	{
		double hours = duration(node, projection, bankRemaining, false);
		String methodName = null;
		List<Plan.Alternative> alternatives = List.of();
		if (node.kind == Action.Kind.TRAIN)
		{
			MethodsPack.Method method = CostModel.currentMethod(node.trainSkill, projection,
				packs.methods, constraints.bannedMethods, constraints.preferredMethods);
			methodName = method == null ? null : method.name;
			alternatives = alternatives(node, projection, bankRemaining, method);
		}
		String why = why(node, projection, hours);
		applyEffects(node, projection, bankRemaining);
		return new Plan.Step(node, hours, why, chapter(node), methodName, alternatives,
			constraints.pinned.contains(node.id), constraints.snoozed.contains(node.id));
	}

	double duration(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining, boolean scoring)
	{
		switch (node.kind)
		{
			case TRAIN:
				return CostModel.trainHours(node.trainSkill, node.trainToLevel, projection,
					packs.methods, bankRemaining.getOrDefault(node.trainSkill, 0L),
					constraints.bannedMethods, constraints.preferredMethods);
			case QUEST:
				QuestsPack.QuestEntry entry = packs.quest(node.questName);
				if (entry == null || entry.minutes <= 0)
				{
					return Double.NaN;
				}
				double factor = CostModel.travelFactor(projection, packs.effects);
				double full = CostModel.questHours(entry.minutes, factor);
				return node.startOnly ? Math.max(5 / 60.0, full * 0.25) : full;
			case KILL:
				// kills-per-hour has no honest source yet — unknown, never invented
				return Double.NaN;
			case OBTAIN:
			case MANUAL:
			default:
				return Double.NaN;
		}
	}

	private void applyEffects(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining)
	{
		switch (node.kind)
		{
			case TRAIN:
			{
				long before = projection.getXp(node.trainSkill);
				long banked = bankRemaining.getOrDefault(node.trainSkill, 0L);
				projection.reachLevel(node.trainSkill, node.trainToLevel);
				long gained = projection.getXp(node.trainSkill) - before;
				long activeXp = Math.max(0, gained - banked);
				bankRemaining.put(node.trainSkill, Math.max(0, banked - gained));
				CostModel.applyBonuses(node.trainSkill, activeXp, projection, packs.methods);
				break;
			}
			case QUEST:
			{
				QuestsPack.QuestEntry entry = packs.quest(node.questName);
				net.runelite.api.Quest quest = GoalExpander.questEnum(entry, node.questName);
				if (node.startOnly)
				{
					// projection has no IN_PROGRESS overlay; starting is modeled
					// by the queststart node itself being done
					break;
				}
				if (quest != null)
				{
					projection.completeQuest(quest, entry == null ? 0 : entry.qp);
				}
				if (entry != null)
				{
					for (Map.Entry<String, Integer> reward : entry.xp.entrySet())
					{
						Skill skill = skillByName(reward.getKey());
						if (skill != null)
						{
							projection.addXp(skill, reward.getValue());
						}
					}
				}
				break;
			}
			case KILL:
				projection.addKillCount(node.kcSource,
					Math.max(0, node.kcTarget - projection.getKillCount(node.kcSource)));
				break;
			case OBTAIN:
				if (node.itemId > 0)
				{
					projection.addItems(node.itemId, 1);
				}
				break;
			case MANUAL:
				if (node.unlockKey != null)
				{
					projection.addUnlock(node.unlockKey);
				}
				break;
		}
	}

	private List<Plan.Alternative> alternatives(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining, MethodsPack.Method chosen)
	{
		MethodsPack.SkillLadder ladder = packs.methods.ladder(node.trainSkill);
		if (ladder == null || chosen == null)
		{
			return List.of();
		}
		double chosenHours = duration(node, projection, bankRemaining, true);
		List<Plan.Alternative> out = new ArrayList<>();
		for (MethodsPack.Method method : ladder.methods)
		{
			if (method.id.equals(chosen.id) || "daily".equals(method.style)
				|| method.rate <= 0
				|| (method.req != null && !CostModel.parsed(method.req).isMet(projection)))
			{
				continue;
			}
			Map<String, String> forced = new HashMap<>(constraints.preferredMethods);
			forced.put(node.trainSkill.getName(), method.id);
			double hours = CostModel.trainHours(node.trainSkill, node.trainToLevel, projection,
				packs.methods, bankRemaining.getOrDefault(node.trainSkill, 0L),
				constraints.bannedMethods, forced);
			if (!Double.isNaN(hours) && !Double.isNaN(chosenHours))
			{
				out.add(new Plan.Alternative(method.id, method.name, hours - chosenHours, method.style));
			}
		}
		out.sort(Comparator.comparingDouble(a -> a.deltaHours));
		return out.size() > 2 ? out.subList(0, 2) : out;
	}

	private String why(Action node, ProjectedState projection, double hours)
	{
		String goals = String.join(", ", node.neededBy);
		switch (node.kind)
		{
			case QUEST:
			{
				QuestsPack.QuestEntry entry = packs.quest(node.questName);
				ProjectedState after = projection.branch();
				applyEffects(node, after, new HashMap<>());
				double factorNow = CostModel.travelFactor(projection, packs.effects);
				double factorAfter = CostModel.travelFactor(after, packs.effects);
				if (factorAfter < factorNow - 1e-9)
				{
					return "Unlocks faster travel — every later errand gets cheaper. Serves " + goals;
				}
				if (entry != null && !entry.xp.isEmpty() && !node.startOnly)
				{
					return "Quest xp rewards shortcut later training. Serves " + goals;
				}
				return (node.startOnly ? "Starting it is enough here. " : "") + "Serves " + goals;
			}
			case TRAIN:
				return "Gates " + goals;
			case KILL:
				return node.kcTarget + " kills needed for " + goals;
			case MANUAL:
				return "Can't detect this — tick it when done. Serves " + goals;
			case OBTAIN:
			default:
				return "Serves " + goals;
		}
	}

	private String chapter(Action node)
	{
		switch (node.kind)
		{
			case QUEST:
				return "Quests & unlocks";
			case TRAIN:
				return "Stat blocks";
			case KILL:
			case OBTAIN:
				return "Gear & bosses";
			case MANUAL:
			default:
				return "Checklist";
		}
	}

	private static String fingerprint(Plan plan)
	{
		StringBuilder sb = new StringBuilder();
		for (Plan.Step step : plan.steps)
		{
			sb.append(step.action.id).append('@');
			sb.append(Double.isNaN(step.hours) ? "?" : String.format(Locale.ROOT, "%.1f", step.hours));
			sb.append(';');
		}
		return Integer.toHexString(sb.toString().hashCode());
	}

	private static Skill skillByName(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return skill;
			}
		}
		return null;
	}
}
