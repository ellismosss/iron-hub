package com.ironhub.engine;

import com.ironhub.data.BankedXpPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.modules.bank.BankedXp;
import com.ironhub.state.AccountState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * The engine's front door: goals + live state + packs + constraints →
 * plan. Pure and synchronous — callers own threading/debounce (the
 * planner module runs this off the client thread on state changes).
 */
public final class PlannerService
{
	private PlannerService()
	{
	}

	public static Plan plan(AccountState state, EnginePacks packs, BankedXpPack bankedPack,
		List<GoalsPack.Goal> unmetGoals, PlanConstraints constraints)
	{
		Map<Skill, Long> banked = new HashMap<>();
		// UIM has no bank, so "banked xp" is fiction — never discount its plan
		// with materials it can't store (G8, iron-first honesty)
		if (bankedPack != null && !state.isUltimateIronman())
		{
			BankedXp.compute(state, bankedPack)
				.forEach((skill, result) -> banked.put(skill, (long) result.xp));
		}
		ActionDag dag = GoalExpander.expand(unmetGoals, state, packs,
			constraints.itemSourcePrefs);
		return new Router(state, packs, constraints, banked).route(dag);
	}
}
