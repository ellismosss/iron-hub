package com.ironhub.requirements;

import com.ironhub.state.StateView;
import java.util.List;

/**
 * One node in the shared requirement graph (DESIGN.md §2.2). Quests,
 * diaries, gear, QoL items, clue steps and goals all express prerequisites
 * in this model; the Goal Planner and What Now engine consume it.
 */
public interface Requirement
{
	/** Whether the account currently satisfies this requirement. */
	boolean isMet(StateView state);

	/**
	 * Like {@link #isMet}, but boostable skill leaves ({@code skillb:})
	 * may add the given per-skill temporary boosts. Equip requirements
	 * ({@code skill:}) ignore boosts — OSRS checks base levels for gear.
	 */
	default boolean isMetWithBoosts(StateView state, java.util.Map<net.runelite.api.Skill, Integer> boosts)
	{
		return isMet(state);
	}

	/** The skill a boost could satisfy, for boostable skill leaves only. */
	default net.runelite.api.Skill boostableSkill()
	{
		return null;
	}

	/** The required item's id, for item leaves only — the join key
	 *  "needs:" surfaces use to answer where-from (item-sources pack). */
	default Integer itemId()
	{
		return null;
	}

	/** Short human line, e.g. "70 Agility", "Song of the Elves". */
	String describe();

	/**
	 * Distance to met, 0 when satisfied (2026-07-20 intelligence arc —
	 * three tabs had privately reinvented "how close am I"). Units are
	 * effort-ish: a missing skill level costs 1, quest points 1 each, an
	 * unstarted quest ~25 (in progress ~12), anything opaque a flat 25.
	 * Composites: allOf sums, anyOf takes the cheapest path. A ranking
	 * aid, never a promise — display copy still comes from missing().
	 */
	default double gap(StateView state)
	{
		return isMet(state) ? 0 : 25;
	}

	/**
	 * The unmet leaf requirements blocking this one — what a locked row
	 * shows on its "needs:" line and what plans are built from.
	 */
	default List<Requirement> missing(StateView state)
	{
		return isMet(state) ? List.of() : List.of(this);
	}
}
