package com.ironhub.engine;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Goals → merged action DAG (ENGINE-DESIGN §3.3–3.4). Every selected
 * goal's unmet steps expand recursively through the requirement graph and
 * the quest/gear packs; identical demands merge (one TRAIN node per
 * skill at the max asked level, one node per quest), tagged with every
 * goal that needs them. Unparseable steps become MANUAL nodes with
 * tick-box keys — surfaced, never dropped.
 */
@Slf4j
public class GoalExpander
{
	private final StateView state;
	private final EnginePacks packs;
	private final ActionDag dag = new ActionDag();

	private GoalExpander(StateView state, EnginePacks packs)
	{
		this.state = state;
		this.packs = packs;
	}

	public static ActionDag expand(List<GoalsPack.Goal> goals, StateView state, EnginePacks packs)
	{
		GoalExpander expander = new GoalExpander(state, packs);
		for (GoalsPack.Goal goal : goals)
		{
			expander.expandGoal(goal);
		}
		expander.chainTrainLevels();
		expander.dag.breakCycles();
		return expander.dag;
	}

	private void expandGoal(GoalsPack.Goal goal)
	{
		Set<String> previous = Set.of();
		for (int i = 0; i < goal.getSteps().size(); i++)
		{
			GoalsPack.Step step = goal.getSteps().get(i);
			String req = step.getRequirement();
			boolean manual = Requirements.isManual(Requirements.parse(req));
			boolean met = manual
				? state.isUnlocked("goalstep:" + goal.getId() + ":" + i)
				: Requirements.parse(req).isMet(state);
			if (met)
			{
				continue;
			}
			Set<String> nodes = manual
				? Set.of(manualNode("goalstep:" + goal.getId() + ":" + i, step.getLabel(), goal.getId()).id)
				: expandRequirement(req, goal.getId(), step.getLabel());
			// checklist semantics: each step waits for the previous unmet one
			for (String id : nodes)
			{
				Action node = dag.get(id);
				for (String prev : previous)
				{
					if (!prev.equals(id))
					{
						node.dependsOn.add(prev);
					}
				}
			}
			if (!nodes.isEmpty())
			{
				previous = nodes;
			}
		}
	}

	/** Expand one requirement string; returns the ids of its direct nodes. */
	private Set<String> expandRequirement(String req, String goalId, String label)
	{
		Set<String> out = new LinkedHashSet<>();
		String lower = req.toLowerCase(Locale.ROOT);
		if (lower.startsWith("any:"))
		{
			expandCheapestPath(req, goalId, label, out);
			return out;
		}
		String[] parts = req.split(":");
		switch (parts[0].toLowerCase(Locale.ROOT))
		{
			case "skill":
			case "skillb":
				addTrain(Skill.valueOf(parts[1].toUpperCase(Locale.ROOT)),
					Integer.parseInt(parts[2]), goalId, out);
				break;
			case "quest":
				addQuest(req.substring("quest:".length()), false, goalId, out);
				break;
			case "queststarted":
				addQuest(req.substring("queststarted:".length()), true, goalId, out);
				break;
			case "item":
			case "itemx":
				addObtain(Integer.parseInt(parts[1]), label, goalId, out);
				break;
			case "kc":
				addKill(parts[1], Integer.parseInt(parts[2]), goalId, out);
				break;
			case "qp":
				addQuestPointFill(Integer.parseInt(parts[1]), goalId, out);
				break;
			case "unlock":
				out.add(manualNode(parts[1], label != null ? label : parts[1], goalId).id);
				break;
			default:
				out.add(manualNode("manualreq:" + req, label != null ? label : req, goalId).id);
		}
		return out;
	}

	private void addTrain(Skill skill, int level, String goalId, Set<String> out)
	{
		if (state.getRealLevel(skill) >= level)
		{
			return;
		}
		// one node per demanded level: a quest needing 25 Ranged must not
		// wait on another goal's 60 — levels chain in a post-pass instead
		String id = "train:" + skill.getName() + ":" + level;
		Action node = dag.get(id);
		if (node == null)
		{
			node = dag.getOrAdd(new Action(id, Action.Kind.TRAIN,
				skill.getName() + " to " + level));
			node.trainSkill = skill;
			node.trainToLevel = level;
		}
		node.neededBy.add(goalId);
		out.add(id);
	}

	/** Chain each skill's train nodes: reaching 60 implies passing 25. */
	private void chainTrainLevels()
	{
		java.util.Map<Skill, java.util.List<Action>> bySkill = new java.util.EnumMap<>(Skill.class);
		for (Action node : dag.nodes())
		{
			if (node.kind == Action.Kind.TRAIN)
			{
				bySkill.computeIfAbsent(node.trainSkill, s -> new ArrayList<>()).add(node);
			}
		}
		for (java.util.List<Action> nodes : bySkill.values())
		{
			nodes.sort(Comparator.comparingInt(n -> n.trainToLevel));
			for (int i = 1; i < nodes.size(); i++)
			{
				nodes.get(i).dependsOn.add(nodes.get(i - 1).id);
			}
		}
	}

	private void addQuest(String name, boolean startOnly, String goalId, Set<String> out)
	{
		QuestsPack.QuestEntry entry = packs.quest(name);
		net.runelite.api.QuestState questState = questState(entry, name);
		if (questState == net.runelite.api.QuestState.FINISHED
			|| (startOnly && questState != net.runelite.api.QuestState.NOT_STARTED))
		{
			return;
		}
		String id = (startOnly ? "queststart:" : "quest:") + name;
		if (dag.get(id) != null)
		{
			dag.get(id).neededBy.add(goalId);
			out.add(id);
			return;
		}
		Action node = dag.getOrAdd(new Action(id, Action.Kind.QUEST,
			(startOnly ? "Start " : "") + name));
		node.questName = name;
		node.startOnly = startOnly;
		node.neededBy.add(goalId);
		out.add(id);
		if (entry != null)
		{
			for (String req : entry.reqs)
			{
				for (String dep : expandRequirement(req, goalId, null))
				{
					node.dependsOn.add(dep);
				}
			}
		}
		// completing a quest implies having started it
		Action start = dag.get("queststart:" + name);
		if (!startOnly && start != null)
		{
			node.dependsOn.add(start.id);
		}
		else if (startOnly)
		{
			Action full = dag.get("quest:" + name);
			if (full != null)
			{
				full.dependsOn.add(id);
			}
		}
	}

	private void addObtain(int itemId, String label, String goalId, Set<String> out)
	{
		GearProgressionPack.Item gearItem = packs.gearItem(itemId);
		String id = gearItem != null ? "obtain:" + gearItem.slug() : "obtain:item" + itemId;
		Action existing = dag.get(id);
		if (existing != null)
		{
			existing.neededBy.add(goalId);
			out.add(id);
			return;
		}
		Action node = dag.getOrAdd(new Action(id, Action.Kind.OBTAIN,
			gearItem != null ? gearItem.getName() : (label != null ? label : "Obtain item " + itemId)));
		node.itemId = itemId;
		node.neededBy.add(goalId);
		out.add(id);
		if (gearItem != null && gearItem.getRequirements() != null)
		{
			for (String req : gearItem.getRequirements())
			{
				for (String dep : expandRequirement(req, goalId, null))
				{
					node.dependsOn.add(dep);
				}
			}
		}
	}

	private void addKill(String source, int count, String goalId, Set<String> out)
	{
		if (state.getKillCount(source) >= count)
		{
			return;
		}
		String id = "kill:" + source;
		Action node = dag.get(id);
		if (node == null)
		{
			node = dag.getOrAdd(new Action(id, Action.Kind.KILL, source + " kills"));
			node.kcSource = source;
		}
		node.kcTarget = Math.max(node.kcTarget, count);
		node.neededBy.add(goalId);
		out.add(id);
	}

	/**
	 * qp:N gates are met by doing quests; fill deterministically from the
	 * community route order (cheapest trustworthy source of "which quests
	 * next") until the projection reaches the target.
	 */
	private void addQuestPointFill(int target, String goalId, Set<String> out)
	{
		int projected = state.getQuestPoints();
		for (Action node : dag.nodes())
		{
			if (node.kind == Action.Kind.QUEST && !node.startOnly)
			{
				QuestsPack.QuestEntry entry = packs.quest(node.questName);
				if (entry != null && questState(entry, node.questName) != net.runelite.api.QuestState.FINISHED)
				{
					projected += entry.qp;
				}
			}
		}
		if (projected >= target)
		{
			return;
		}
		List<QuestsPack.QuestEntry> route = new ArrayList<>(packs.quests.quests);
		route.sort(Comparator.comparingInt(q -> q.order < 0 ? Integer.MAX_VALUE : q.order));
		for (QuestsPack.QuestEntry entry : route)
		{
			if (projected >= target)
			{
				break;
			}
			if (entry.qp <= 0
				|| questState(entry, entry.name) == net.runelite.api.QuestState.FINISHED
				|| dag.get("quest:" + entry.name) != null)
			{
				continue;
			}
			addQuest(entry.name, false, goalId, out);
			projected += entry.qp;
		}
	}

	private Action manualNode(String unlockKey, String label, String goalId)
	{
		String id = "manual:" + unlockKey;
		Action node = dag.get(id);
		if (node == null)
		{
			node = dag.getOrAdd(new Action(id, Action.Kind.MANUAL, label));
			node.unlockKey = unlockKey;
			node.manualText = label;
		}
		node.neededBy.add(goalId);
		return node;
	}

	/** Pick the cheapest satisfiable any: path (deterministic tiebreak). */
	private void expandCheapestPath(String req, String goalId, String label, Set<String> out)
	{
		String[] paths = req.substring("any:".length()).split("\\|");
		String best = null;
		double bestCost = Double.MAX_VALUE;
		for (String path : paths)
		{
			double cost = 0;
			for (String leaf : path.split("&"))
			{
				cost += quickCost(leaf);
			}
			if (cost < bestCost - 1e-9)
			{
				bestCost = cost;
				best = path;
			}
		}
		if (best == null)
		{
			best = paths[0];
		}
		for (String leaf : best.split("&"))
		{
			out.addAll(expandRequirement(leaf, goalId, label));
		}
	}

	/** Coarse hours for path choice only (the router re-costs properly). */
	private double quickCost(String leaf)
	{
		com.ironhub.requirements.Requirement parsed = Requirements.parse(leaf);
		if (parsed.isMet(state))
		{
			return 0;
		}
		String[] parts = leaf.split(":");
		switch (parts[0].toLowerCase(Locale.ROOT))
		{
			case "skill":
			case "skillb":
				double hours = CostModel.trainHours(Skill.valueOf(parts[1].toUpperCase(Locale.ROOT)),
					Integer.parseInt(parts[2]), state, packs.methods, 0);
				return Double.isNaN(hours) ? 50 : hours;
			case "quest":
			case "queststarted":
				QuestsPack.QuestEntry entry = packs.quest(leaf.substring(leaf.indexOf(':') + 1));
				return entry == null || entry.minutes <= 0 ? 2 : entry.minutes / 60.0;
			case "kc":
				return 3;
			default:
				return 5; // items/unlocks/manual: flat pessimism, deterministic
		}
	}

	private net.runelite.api.QuestState questState(QuestsPack.QuestEntry entry, String name)
	{
		net.runelite.api.Quest quest = questEnum(entry, name);
		return quest == null ? net.runelite.api.QuestState.NOT_STARTED : state.getQuestState(quest);
	}

	static net.runelite.api.Quest questEnum(QuestsPack.QuestEntry entry, String name)
	{
		String wanted = (entry != null && entry.enumName != null ? entry.enumName : name)
			.toLowerCase(Locale.ROOT);
		for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
		{
			if (quest.getName().toLowerCase(Locale.ROOT).equals(wanted))
			{
				return quest;
			}
		}
		return null;
	}
}
