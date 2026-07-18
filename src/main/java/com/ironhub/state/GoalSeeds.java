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
