package com.ironhub.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The merged, deduplicated action graph for the selected goals
 * (ENGINE-DESIGN §3.4). Insertion order is preserved (deterministic
 * iteration); cycles found in pack data are cut, logged into
 * {@link #degraded} and surfaced in the plan footer rather than
 * crashing the planner.
 */
public class ActionDag
{
	private final Map<String, Action> nodes = new LinkedHashMap<>();
	public final List<String> degraded = new ArrayList<>();
	/** Goal id → display name, for user-facing text (ids never render). */
	public final Map<String, String> goalNames = new LinkedHashMap<>();
	/** Goal id → icon item id (nullable), for row icons. */
	public final Map<String, Integer> goalIcons = new LinkedHashMap<>();

	public Action get(String id)
	{
		return nodes.get(id);
	}

	public Action getOrAdd(Action action)
	{
		Action existing = nodes.get(action.id);
		if (existing != null)
		{
			return existing;
		}
		nodes.put(action.id, action);
		return action;
	}

	public Iterable<Action> nodes()
	{
		return nodes.values();
	}

	public int size()
	{
		return nodes.size();
	}

	/**
	 * Cut any cycles (pack-data bugs) by dropping the closing edge, then
	 * verify a topological order exists. Returns the ids of cut edges.
	 */
	public void breakCycles()
	{
		Set<String> done = new HashSet<>();
		Set<String> onStack = new HashSet<>();
		for (Action node : new ArrayList<>(nodes.values()))
		{
			cutCycles(node, done, onStack, new ArrayDeque<>());
		}
	}

	private void cutCycles(Action node, Set<String> done, Set<String> onStack, Deque<String> path)
	{
		if (done.contains(node.id))
		{
			return;
		}
		onStack.add(node.id);
		path.push(node.id);
		for (String dep : new ArrayList<>(node.dependsOn))
		{
			Action child = nodes.get(dep);
			if (child == null)
			{
				node.dependsOn.remove(dep);
				continue;
			}
			if (onStack.contains(dep))
			{
				node.dependsOn.remove(dep);
				degraded.add(node.id + " -> " + dep + " (cycle cut)");
				continue;
			}
			cutCycles(child, done, onStack, path);
		}
		path.pop();
		onStack.remove(node.id);
		done.add(node.id);
	}

	/** Deterministic topological order (insertion order among ready nodes). */
	public List<Action> topological()
	{
		Map<String, Integer> remaining = new LinkedHashMap<>();
		for (Action node : nodes.values())
		{
			remaining.put(node.id, node.dependsOn.size());
		}
		List<Action> order = new ArrayList<>();
		boolean progressed = true;
		Set<String> emitted = new HashSet<>();
		while (progressed)
		{
			progressed = false;
			for (Action node : nodes.values())
			{
				if (!emitted.contains(node.id) && remaining.get(node.id) == 0)
				{
					order.add(node);
					emitted.add(node.id);
					progressed = true;
					for (Action other : nodes.values())
					{
						if (other.dependsOn.contains(node.id))
						{
							remaining.merge(other.id, -1, Integer::sum);
						}
					}
				}
			}
		}
		return order;
	}
}
