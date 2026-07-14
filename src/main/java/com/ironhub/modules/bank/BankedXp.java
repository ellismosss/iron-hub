package com.ironhub.modules.bank;

import com.ironhub.data.BankedXpPack;
import com.ironhub.state.AccountState;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Banked-XP computation: for each item the best (highest-XP) method
 * counts once — bury vs gilded altar never double-counts the same bones.
 * Pure logic over the bank snapshot; ~O(entries).
 */
public final class BankedXp
{
	private BankedXp()
	{
	}

	public static class Result
	{
		public double xp;
		public final Set<String> methods = new TreeSet<>();
	}

	/** Per-skill banked XP, largest first; skills with 0 XP omitted. */
	public static Map<Skill, Result> compute(AccountState state, BankedXpPack pack)
	{
		// best method per (skill, item)
		Map<Skill, Map<Integer, BankedXpPack.Entry>> best = new HashMap<>();
		for (BankedXpPack.Entry entry : pack.getEntries())
		{
			Skill skill = Skill.valueOf(entry.getSkill().toUpperCase(Locale.ROOT));
			best.computeIfAbsent(skill, s -> new HashMap<>())
				.merge(entry.getItemId(), entry,
					(a, b) -> a.getXpEach() >= b.getXpEach() ? a : b);
		}

		Map<Skill, Result> totals = new HashMap<>();
		best.forEach((skill, byItem) -> byItem.forEach((itemId, entry) ->
		{
			int count = state.getBankSnapshot().getOrDefault(itemId, 0);
			if (count > 0)
			{
				Result result = totals.computeIfAbsent(skill, s -> new Result());
				result.xp += count * entry.getXpEach();
				result.methods.add(entry.getMethod());
			}
		}));

		return totals.entrySet().stream()
			.sorted(Comparator.comparingDouble(e -> -e.getValue().xp))
			.collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
	}
}
