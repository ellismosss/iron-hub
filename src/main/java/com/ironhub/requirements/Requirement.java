package com.ironhub.requirements;

import com.ironhub.state.AccountState;
import java.util.List;

/**
 * One node in the shared requirement graph (DESIGN.md §2.2). Quests,
 * diaries, gear, QoL items, clue steps and goals all express prerequisites
 * in this model; the Goal Planner and What Now engine consume it.
 */
public interface Requirement
{
	/** Whether the account currently satisfies this requirement. */
	boolean isMet(AccountState state);

	/**
	 * Like {@link #isMet}, but boostable skill leaves ({@code skillb:})
	 * may add the given per-skill temporary boosts. Equip requirements
	 * ({@code skill:}) ignore boosts — OSRS checks base levels for gear.
	 */
	default boolean isMetWithBoosts(AccountState state, java.util.Map<net.runelite.api.Skill, Integer> boosts)
	{
		return isMet(state);
	}

	/** The skill a boost could satisfy, for boostable skill leaves only. */
	default net.runelite.api.Skill boostableSkill()
	{
		return null;
	}

	/** Short human line, e.g. "70 Agility", "Song of the Elves". */
	String describe();

	/**
	 * The unmet leaf requirements blocking this one — what a locked row
	 * shows on its "needs:" line and what plans are built from.
	 */
	default List<Requirement> missing(AccountState state)
	{
		return isMet(state) ? List.of() : List.of(this);
	}
}
