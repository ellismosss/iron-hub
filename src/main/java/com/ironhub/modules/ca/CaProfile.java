package com.ironhub.modules.ca;

import com.ironhub.data.CaProfilePack;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The interface's "Combat Profile" panel, computed exactly as the game
 * computes it ([proc,ca_overview_create_personal]): seven rows, four of
 * them sums over hardcoded player vars and three of them the highest kill
 * count in a category, named from the boss catalog.
 *
 * <p>Pure and static so the arithmetic is testable without a client.
 */
final class CaProfile
{
	static final class Row
	{
		final String label;
		final String value;

		Row(String label, String value)
		{
			this.label = label;
			this.value = value;
		}
	}

	private CaProfile()
	{
	}

	static List<Row> rows(AccountState state, CaProfilePack pack, List<CaBoss> bosses)
	{
		List<Row> rows = new ArrayList<>();
		if (pack == null)
		{
			return rows;
		}
		rows.add(new Row("Tasks Completed", count(sumVarbits(state, pack.tasksCompletedVarbits))));
		rows.add(new Row("Boss Kill Count", count(sumVarps(state, pack.bossKillCountVarps))));
		rows.add(new Row("Skilling Boss Kill Count",
			count(sumVarps(state, pack.skillingKillCountVarps))));
		rows.add(new Row("Raid Completions", count(sumVarps(state, pack.raidCompletionVarps))));
		rows.add(new Row("Top Boss", top(state, pack, bosses, CaBoss.CATEGORY_BOSS)));
		rows.add(new Row("Top Skilling Boss", top(state, pack, bosses, CaBoss.CATEGORY_SKILLING)));
		rows.add(new Row("Top Raid", top(state, pack, bosses, CaBoss.CATEGORY_RAID)));
		return rows;
	}

	/** A boss's kill count, or -1 when the dump maps no var for it (a boss
	 *  added since the pin) — never a zero we cannot vouch for. */
	static int killCount(AccountState state, CaProfilePack pack, int bossIndex)
	{
		List<Integer> varps = pack == null ? List.of() : pack.killVarpsFor(bossIndex);
		if (varps.isEmpty())
		{
			return -1;
		}
		int total = 0;
		for (int varp : varps)
		{
			total += state.getVarp(varp);
		}
		return total;
	}

	/** "Vorkath (1,234)", or the game's own "Nothing!" when nothing counts. */
	private static String top(AccountState state, CaProfilePack pack, List<CaBoss> bosses,
		int category)
	{
		CaBoss best = null;
		int bestCount = 0;
		for (CaBoss boss : bosses)
		{
			if (boss.category != category || boss.index == pack.excludedTopIndex)
			{
				continue;
			}
			int kills = killCount(state, pack, boss.index);
			if (kills > bestCount)
			{
				best = boss;
				bestCount = kills;
			}
		}
		return best == null ? "Nothing!" : best.name + " (" + count(bestCount) + ")";
	}

	private static int sumVarps(AccountState state, List<Integer> varps)
	{
		int total = 0;
		for (int varp : varps)
		{
			total += state.getVarp(varp);
		}
		return total;
	}

	private static int sumVarbits(AccountState state, List<Integer> varbits)
	{
		int total = 0;
		for (int varbit : varbits)
		{
			total += state.getVarbit(varbit);
		}
		return total;
	}

	static String count(int value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}
}
