package com.ironhub.requirements;

import com.ironhub.data.BoostsPack;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Evaluates which temporary skill boosts the account can actually use
 * right now (boosts have their own obtainment gates: owning the pie or
 * saw, quest unlocks) and how much headroom that gives per skill.
 * Visible boosts don't stack with each other — the best one counts —
 * while invisible boosts (crystal saw) stack on top.
 */
public final class Boosts
{
	private Boosts()
	{
	}

	/** Max usable boost per skill given the account's available sources. */
	public static Map<Skill, Integer> available(BoostsPack pack, StateView state)
	{
		Map<Skill, Integer> visible = new EnumMap<>(Skill.class);
		Map<Skill, Integer> invisible = new EnumMap<>(Skill.class);
		for (BoostsPack.Boost boost : pack.getBoosts())
		{
			if (!Requirements.parse(boost.getGate()).isMet(state))
			{
				continue;
			}
			for (Skill skill : skills(boost))
			{
				Map<Skill, Integer> pool = boost.isInvisible() ? invisible : visible;
				pool.merge(skill, boost.getBoost(),
					boost.isInvisible() ? Integer::sum : Integer::max);
			}
		}
		Map<Skill, Integer> total = new EnumMap<>(Skill.class);
		visible.forEach((skill, value) -> total.merge(skill, value, Integer::sum));
		invisible.forEach((skill, value) -> total.merge(skill, value, Integer::sum));
		return total;
	}

	/** Names of the available sources that boost a skill (tooltips). */
	public static List<String> describe(BoostsPack pack, StateView state, Skill skill)
	{
		List<String> names = new ArrayList<>();
		for (BoostsPack.Boost boost : pack.getBoosts())
		{
			if (skills(boost).contains(skill) && Requirements.parse(boost.getGate()).isMet(state))
			{
				names.add(boost.getName());
			}
		}
		return names;
	}

	private static List<Skill> skills(BoostsPack.Boost boost)
	{
		List<Skill> skills = new ArrayList<>();
		for (String name : boost.getSkills())
		{
			skills.add(Skill.valueOf(name.toUpperCase(Locale.ROOT)));
		}
		return skills;
	}
}
