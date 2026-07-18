package com.ironhub.state;

import com.ironhub.requirements.Requirements;
import java.util.List;

/**
 * The goal-provider factory (Goals v2 G1): one method per goal family,
 * building the finished {@link PersistedState.GoalSeed} a module persists
 * when its "+" affordance creates a Route. The transforms here are the
 * former GoalPlannerModule to&lt;X&gt;Goal builders, run once at add time
 * (and at legacy migration) instead of on every render — labels, proofs
 * and ids are byte-identical to what those builders produced.
 */
public final class GoalSeeds
{
	private GoalSeeds()
	{
	}

	/** A Combat Achievement task: one step, proven by the
	 *  {@code catask_<id>} unlock the CA module marks. */
	public static PersistedState.GoalSeed ca(int taskId, String name, String description, String tier)
	{
		String proof = "unlock:catask_" + taskId;
		PersistedState.GoalSeed seed = base("ca", "ca:" + taskId, name);
		seed.steps.add(step(description + " (" + tier + " combat task)", proof));
		seed.achieved.add(proof);
		return seed;
	}

	/** An achievement diary task: one step, proven by the
	 *  {@code diarytask_<slug>} unlock the diaries module marks. */
	public static PersistedState.GoalSeed diary(String slug, String task, String region, String tier)
	{
		String proof = "unlock:diarytask_" + slug;
		PersistedState.GoalSeed seed = base("diary", "diary:" + slug, task);
		seed.steps.add(step(task + " (" + region + " " + tier + " diary)", proof));
		seed.achieved.add(proof);
		return seed;
	}

	/** A clue step: its item requirements become planner steps, proven by
	 *  the {@code cluestep_<id>} unlock the clues module marks. */
	public static PersistedState.GoalSeed clue(String id, String text, String tier, List<String> reqs)
	{
		PersistedState.GoalSeed seed = base("clue", "clue:" + id, tier + " clue step: " + text);
		for (String raw : reqs)
		{
			seed.steps.add(step(Requirements.parse(raw).describe(), raw));
		}
		seed.achieved.add("unlock:cluestep_" + id);
		return seed;
	}

	/** A collection-log slot: the source activity's requirements become
	 *  steps, then obtaining the slot — proven by the {@code clogitem_<id>}
	 *  unlock the collection-log module marks. */
	public static PersistedState.GoalSeed clog(int itemId, String name, String activity, List<String> reqs)
	{
		String proof = "unlock:clogitem_" + itemId;
		PersistedState.GoalSeed seed = base("clog", "clog:" + itemId, name);
		seed.iconItemId = itemId;
		for (String raw : reqs)
		{
			seed.steps.add(step(Requirements.parse(raw).describe(), raw));
		}
		seed.steps.add(step("Obtain " + name + " (" + activity + ")", proof));
		seed.achieved.add(proof);
		return seed;
	}

	/** A user-typed goal ("Agility 70") or a module's requirement-backed
	 *  goal (quests): one detectable step, achieved when it holds. */
	public static PersistedState.GoalSeed custom(String goalId, String name, String req)
	{
		PersistedState.GoalSeed seed = base("custom", goalId, name);
		seed.steps.add(step(name, req));
		seed.achieved.add(req);
		return seed;
	}

	/** A useful PoH build: the tier's Construction/gate reqs become steps,
	 *  then building it — proven by the {@code pohtier_<id>} unlock the PoH
	 *  module marks when the tier is detected built (built state lives in
	 *  its own set, invisible to the requirement graph). */
	public static PersistedState.GoalSeed poh(String tierId, String tierName, int icon, List<String> reqs)
	{
		String proof = "unlock:" + pohProofKey(tierId);
		PersistedState.GoalSeed seed = base("poh", "poh:" + tierId, tierName);
		seed.iconItemId = icon;
		for (String raw : reqs)
		{
			seed.steps.add(step(Requirements.parse(raw).describe(), raw));
		}
		seed.steps.add(step("Build " + tierName, proof));
		seed.achieved.add(proof);
		return seed;
	}

	/** A QoL unlock: its prerequisites become steps (prose reqs render as
	 *  manual ticks, graph reqs auto-complete), then obtaining it — achieved
	 *  by OWNING the item (the module's own OWNED signal; variation-aware). */
	public static PersistedState.GoalSeed qol(String id, String name, List<Integer> itemIds, List<String> reqs)
	{
		String ownProof = ownershipProof(itemIds);
		PersistedState.GoalSeed seed = base("qol", "qol:" + id, name);
		if (!itemIds.isEmpty())
		{
			seed.iconItemId = itemIds.get(0);
		}
		for (String raw : reqs)
		{
			seed.steps.add(step(Requirements.parse(raw).describe(), raw));
		}
		seed.steps.add(step("Obtain " + name, ownProof));
		seed.achieved.add(ownProof);
		return seed;
	}

	/** A one-shot supply goal ("stock N × item"): achieved when bank+carried
	 *  ≥ N (variation-aware via {@code item:}), re-addable after completion. */
	public static PersistedState.GoalSeed supply(int itemId, String name, int qty)
	{
		String req = "item:" + itemId + ":" + qty + ":" + name;
		PersistedState.GoalSeed seed = base("supply", "supply:" + itemId, "Stock " + qty + " × " + name);
		seed.iconItemId = itemId;
		seed.steps.add(step("Stock " + qty + " × " + name, req));
		seed.achieved.add(req);
		return seed;
	}

	/** The PoH tier's proof-unlock key. The requirement graph's {@code
	 *  unlock:} parse splits on {@code :}, and tier ids carry one
	 *  ({@code space:slug}), so the key must sanitize it out — CLAUDE.md's
	 *  "unlock: keys contain no colons" rule. */
	public static String pohProofKey(String tierId)
	{
		return "pohtier_" + tierId.replace(':', '_');
	}

	/** Own any of the item ids: a single {@code item:} leaf, or an {@code
	 *  any:} of alternatives (both variation-aware). */
	private static String ownershipProof(List<Integer> itemIds)
	{
		if (itemIds.size() == 1)
		{
			return "item:" + itemIds.get(0);
		}
		StringBuilder any = new StringBuilder("any:");
		for (int i = 0; i < itemIds.size(); i++)
		{
			any.append(i == 0 ? "" : "|").append("item:").append(itemIds.get(i));
		}
		return any.toString();
	}

	private static PersistedState.GoalSeed base(String family, String id, String name)
	{
		PersistedState.GoalSeed seed = new PersistedState.GoalSeed();
		seed.family = family;
		seed.id = id;
		seed.name = name;
		return seed;
	}

	private static PersistedState.SeedStep step(String label, String requirement)
	{
		PersistedState.SeedStep step = new PersistedState.SeedStep();
		step.label = label;
		step.requirement = requirement;
		return step;
	}
}
