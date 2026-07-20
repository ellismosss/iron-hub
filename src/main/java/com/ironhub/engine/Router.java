package com.ironhub.engine;

import com.ironhub.data.GearProgressionPack;
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
	private Map<String, String> goalNames = Map.of();

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
		plan.goalNames.putAll(dag.goalNames);
		plan.goalIcons.putAll(dag.goalIcons);
		this.goalNames = dag.goalNames;
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
		// priority tiers (G5): weight by the MAX tier among served goals, so
		// a someday goal's UNIQUE steps sink but shared steps keep full value
		value *= tierMultiplier(node);
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
		// the player's manual task order nudges among near-equal feasible
		// picks, never overrides value/pins (honoured "where the order allows")
		score += 0.3 * routeOrderBonus(node);
		if (pinnedClosure.contains(node.id))
		{
			// pinned goals/steps outrank tiers; among pinned goals, pin order
			score += 1_000 + goalPinBonus(node);
		}
		if (constraints.snoozed.contains(node.id))
		{
			score *= 0.001;
		}
		return score;
	}

	/** Value multiplier from the highest-priority goal a step serves. */
	private double tierMultiplier(Action node)
	{
		double mult = -1;
		for (String goalId : node.neededBy)
		{
			String tier = constraints.goalPriority.getOrDefault(goalId, "normal");
			// high/medium/low (Luke's G7 colours); "someday" is the pre-rename
			// low alias, kept so old profiles + tests read the same 0.05 sink
			double m = "high".equals(tier) ? 2.0
				: "medium".equals(tier) ? 1.3
				: ("low".equals(tier) || "someday".equals(tier)) ? 0.05
				: 1.0;
			mult = Math.max(mult, m);
		}
		return mult < 0 ? 1.0 : mult; // no served goals (shouldn't happen) → neutral
	}

	/** A pinned goal's steps rank by pin order: earlier-pinned goals win. */
	private double goalPinBonus(Action node)
	{
		int best = Integer.MAX_VALUE;
		for (String goalId : node.neededBy)
		{
			int rank = constraints.pinnedGoals.indexOf(goalId);
			if (rank >= 0)
			{
				best = Math.min(best, rank);
			}
		}
		return best == Integer.MAX_VALUE ? 0 : constraints.pinnedGoals.size() - best;
	}

	/** How early this node sits in any of its routes' manual task orders
	 *  (0 when unordered) — a small forward pull, deterministic. */
	private double routeOrderBonus(Action node)
	{
		double bonus = 0;
		for (String goalId : node.neededBy)
		{
			List<String> order = constraints.routeTaskOrder.get(goalId);
			if (order == null)
			{
				continue;
			}
			int idx = order.indexOf(node.id);
			if (idx >= 0 && order.size() > 1)
			{
				bonus = Math.max(bonus, (order.size() - 1 - idx) / (double) (order.size() - 1));
			}
		}
		return bonus;
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
			// travel-effect activation: cheaper future quests — but only
			// quests an effect actually references can move the factor, so
			// skip the projection branch + double travelFactor for the rest
			// (2026-07-20 audit: this ran for EVERY ready quest node on
			// EVERY pick iteration)
			if (canMoveTravelFactor(node.questName, entry))
			{
				ProjectedState after = projection.branch();
				applyEffects(node, after, new HashMap<>());
				double factorNow = CostModel.travelFactor(projection, packs.effects);
				double factorAfter = CostModel.travelFactor(after, packs.effects);
				if (factorAfter < factorNow - 1e-9)
				{
					saved += (factorNow - factorAfter) * 4.0; // ~4h of errands ahead, coarse
				}
			}
		}
		return saved;
	}

	/** Quest names referenced by any travel effect's active requirement,
	 *  plus whether any effect gates on quest points (a qp: effect can be
	 *  tipped by any qp-granting quest). Built once per Router. */
	private java.util.Set<String> travelEffectQuests;
	private boolean travelEffectsUseQp;

	private boolean canMoveTravelFactor(String questName, QuestsPack.QuestEntry entry)
	{
		if (travelEffectQuests == null)
		{
			java.util.Set<String> quests = new java.util.HashSet<>();
			boolean qp = false;
			for (com.ironhub.data.EffectsPack.Effect effect : packs.effects.effects)
			{
				if (effect.active == null)
				{
					continue;
				}
				// active reqs are quest:/queststarted:/qp: leaves (possibly
				// &-joined inside any:); a name scan is exact enough here
				String lower = effect.active.toLowerCase(java.util.Locale.ROOT);
				qp |= lower.contains("qp:");
				for (QuestsPack.QuestEntry candidate : packs.quests.quests)
				{
					if (lower.contains(candidate.name.toLowerCase(java.util.Locale.ROOT)))
					{
						quests.add(candidate.name);
					}
				}
			}
			travelEffectsUseQp = qp;
			travelEffectQuests = quests;
		}
		return travelEffectQuests.contains(questName)
			|| (travelEffectsUseQp && entry.qp > 0);
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
		// goal pins (G5): every node a pinned goal needs, plus its
		// prerequisites, jumps the queue like a step pin
		if (!constraints.pinnedGoals.isEmpty())
		{
			for (Action node : dag.nodes())
			{
				if (node.neededBy.stream().anyMatch(constraints.pinnedGoals::contains))
				{
					addClosure(dag, node.id, closure);
				}
			}
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
		String methodId = null;
		String methodStyle = node.kind == Action.Kind.QUEST || node.kind == Action.Kind.KILL
			? "active" : null;
		int methodRate = 0;
		int trainFromLevel = 0;
		long trainXpRemaining = 0;
		List<Plan.Resource> resources = List.of();
		List<Plan.Alternative> alternatives = List.of();
		if (node.kind == Action.Kind.TRAIN)
		{
			MethodsPack.Method method = CostModel.currentMethod(node.trainSkill, projection,
				packs.methods, constraints.bannedMethods, constraints.preferredMethods);
			methodName = method == null ? null : method.name;
			methodId = method == null ? null : method.id;
			methodStyle = method == null ? null : method.style;
			methodRate = method == null ? 0 : method.rate;
			trainFromLevel = projection.getRealLevel(node.trainSkill);
			long targetXp = net.runelite.api.Experience.getXpForLevel(Math.min(99, node.trainToLevel));
			long startXp = Math.min(targetXp, projection.getXp(node.trainSkill)
				+ Math.max(0, bankRemaining.getOrDefault(node.trainSkill, 0L)));
			trainXpRemaining = Math.max(0, targetXp - startXp);
			// resource counts use the GROSS span: banked planks/bones already
			// count as banked xp, so crediting them again would double-count
			long grossRemaining = Math.max(0, targetXp - projection.getXp(node.trainSkill));
			resources = resources(method, grossRemaining);
			alternatives = alternatives(node, projection, bankRemaining, method, grossRemaining);
		}
		else if (node.materials != null && !node.materials.isEmpty())
		{
			List<Plan.Resource> out = new ArrayList<>();
			for (com.ironhub.data.GearProgressionPack.Material material : node.materials)
			{
				out.add(new Plan.Resource(material.getItemId(), material.getName(),
					material.getQty(), base.canonicalStock(material.getItemId())));
			}
			resources = out;
		}
		String why = why(node, projection, hours);
		applyEffects(node, projection, bankRemaining);
		return new Plan.Step(node, hours, spread(node), why, chapter(node), methodName, methodId, methodStyle,
			methodRate, trainFromLevel, trainXpRemaining, resources,
			alternatives, constraints.pinned.contains(node.id), constraints.snoozed.contains(node.id));
	}

	/** The P90 "unlucky" spread for a drop-gated step (KILL / clog OBTAIN),
	 *  NaN for deterministic or unsourced steps. */
	private double spread(Action node)
	{
		if (packs.rates == null)
		{
			return Double.NaN;
		}
		if (node.kind == Action.Kind.OBTAIN
			&& (node.obtainHours == null || node.obtainHours <= 0))
		{
			return packs.rates.obtainSpreadHours(node.itemId);
		}
		return Double.NaN;
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
				// a clog activity matching the source gives kills/hr; else unknown
				return packs.rates == null ? Double.NaN
					: packs.rates.killHours(node.kcSource, node.kcTarget);
			case OBTAIN:
				// curated gear hours win where they exist; clog drop rates fill
				// the gap (clog slots, bare item: leaves) — else unknown
				if (node.obtainHours != null && node.obtainHours > 0)
				{
					return node.obtainHours;
				}
				return packs.rates == null ? Double.NaN : packs.rates.obtainHours(node.itemId);
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

	/**
	 * Materials the chosen method consumes to cover the remaining xp, vs
	 * what the bank/inventory/equipment already hold (canonical counts,
	 * live snapshot — the same stock the banked-xp module reads). Needs
	 * are computed from the gross xp span: the owned stock is subtracted
	 * exactly once, here — banked-xp credit covers the same items.
	 */
	private List<Plan.Resource> resources(MethodsPack.Method method, long xpRemaining)
	{
		if (method == null || method.xpEach <= 0 || method.inputs == null
			|| method.inputs.isEmpty() || xpRemaining <= 0)
		{
			return List.of();
		}
		long actions = (long) Math.ceil(xpRemaining / method.xpEach);
		List<Plan.Resource> out = new ArrayList<>();
		for (MethodsPack.Input input : method.inputs)
		{
			out.add(new Plan.Resource(input.itemId, input.name,
				actions * input.qty, base.canonicalStock(input.itemId)));
		}
		return out;
	}

	private List<Plan.Alternative> alternatives(Action node, ProjectedState projection,
		Map<Skill, Long> bankRemaining, MethodsPack.Method chosen, long grossRemaining)
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
				out.add(new Plan.Alternative(method.id, method.name, hours,
					hours - chosenHours, method.style, method.rate,
					resources(method, grossRemaining)));
			}
		}
		out.sort(Comparator.comparingDouble(a -> a.deltaHours));
		return out.size() > 2 ? out.subList(0, 2) : out;
	}

	private String why(Action node, ProjectedState projection, double hours)
	{
		String goals = displayGoals(node);
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
				return "Up to " + node.kcTarget + " kills for " + goals
					+ " — the drop usually lands sooner";
			case MANUAL:
				if (node.unlockKey == null)
				{
					return "Detected automatically when you claim it. Serves " + goals;
				}
				if (node.unlockKey.startsWith("gearmark_"))
				{
					return "POH furniture can't be detected — right-click to mark built"
						+ " (also on the gear chart). Serves " + goals;
				}
				if (node.unlockKey.startsWith("catask_"))
				{
					return "Completes automatically when the task is done in-game"
						+ " (Combat achievements tab). Serves " + goals;
				}
				if (node.unlockKey.startsWith("diarytask_"))
				{
					return "Completes automatically when the task is done in-game"
						+ " (Diaries tab). Serves " + goals;
				}
				return "Can't detect this — right-click to mark done. Serves " + goals;
			case OBTAIN:
			{
				GearProgressionPack.Item gearItem = node.itemId > 0
					? packs.gearItem(node.itemId) : null;
				if (gearItem != null && gearItem.getSourceNote() != null)
				{
					return gearItem.getSourceNote() + ". Serves " + goals;
				}
				return "Serves " + goals;
			}
			default:
				return "Serves " + goals;
		}
	}

	/** Human goal names, never raw ids like "gear:marble_portal_nexus". */
	private String displayGoals(Action node)
	{
		List<String> names = new ArrayList<>();
		for (String id : node.neededBy)
		{
			names.add(goalNames.getOrDefault(id, id));
		}
		return String.join(", ", names);
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
