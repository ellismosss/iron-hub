package com.ironhub.engine;

import com.ironhub.data.ClogPack;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drop/kill rates for the cost model (Goals v2 G3), derived from the bundled
 * collection-log pack (clog.json — iron completions/hr + per-drop mean
 * attempts). Turns a KILL source or an OBTAIN item id into expected hours +
 * a P90 "unlucky" spread, using the shared geometric core in {@link CostModel}.
 *
 * <p>Honesty boundaries are the pack's: a purchase slot ({@code attempts == 0})
 * or a locked activity ({@code perHour <= 0}) yields no rate, and an item or
 * source with no clog row returns NaN — never an invented number.
 */
public class RateSource
{
	/** Best (fastest expected) drop row for an item, canonicalized. */
	private final Map<Integer, ClogPack.Activity> activityByItem = new HashMap<>();
	private final Map<Integer, ClogPack.Item> dropByItem = new HashMap<>();
	/** Normalized activity name → activity, for kc: source matching. */
	private final Map<String, ClogPack.Activity> activityByName = new HashMap<>();
	private final ClogPack clog;

	public RateSource(ClogPack clog)
	{
		this.clog = clog;
		if (clog == null || clog.activities == null)
		{
			return;
		}
		for (ClogPack.Activity activity : clog.activities)
		{
			if (activity.perHour <= 0)
			{
				continue; // locked/untimed — no honest rate
			}
			activityByName.putIfAbsent(normalize(activity.name), activity);
			if (activity.items == null)
			{
				continue;
			}
			for (ClogPack.Item item : activity.items)
			{
				if (item.attempts <= 0)
				{
					continue; // purchase / zero-attempt slot — never rate-ranked
				}
				int id = clog.canonical(item.itemId);
				ClogPack.Activity best = activityByItem.get(id);
				// keep the fastest-expected source for this item
				if (best == null || CostModel.dropHours(item.attempts, activity.perHour, activity.extraTimeFirst)
					< CostModel.dropHours(dropByItem.get(id).attempts, best.perHour, best.extraTimeFirst))
				{
					activityByItem.put(id, activity);
					dropByItem.put(id, item);
				}
			}
		}
	}

	/** Expected hours to obtain an item as a drop, or NaN when unsourced. */
	public double obtainHours(int itemId)
	{
		if (clog == null)
		{
			return Double.NaN;
		}
		int id = clog.canonical(itemId);
		ClogPack.Activity activity = activityByItem.get(id);
		if (activity == null)
		{
			return Double.NaN;
		}
		return CostModel.dropHours(dropByItem.get(id).attempts, activity.perHour, activity.extraTimeFirst);
	}

	/** P90 "unlucky" hours to obtain an item as a drop, or NaN. */
	public double obtainSpreadHours(int itemId)
	{
		if (clog == null)
		{
			return Double.NaN;
		}
		int id = clog.canonical(itemId);
		ClogPack.Activity activity = activityByItem.get(id);
		if (activity == null)
		{
			return Double.NaN;
		}
		return CostModel.dropSpreadHours(dropByItem.get(id).attempts, activity.perHour, activity.extraTimeFirst);
	}

	/**
	 * Expected hours for a kill-count target against a named source (a boss
	 * or minigame that is also a clog activity). Matches the activity by
	 * name; a miss returns NaN — kills/hr is never invented.
	 */
	public double killHours(String source, int count)
	{
		if (source == null)
		{
			return Double.NaN;
		}
		ClogPack.Activity activity = activityByName.get(normalize(source));
		if (activity == null)
		{
			return Double.NaN;
		}
		return CostModel.completionsToHours(count, activity.perHour, activity.extraTimeFirst);
	}

	/** Whether any clog activity drops this item (has a sourced rate). */
	public boolean hasDropRate(int itemId)
	{
		return clog != null && activityByItem.containsKey(clog.canonical(itemId));
	}

	/**
	 * A short "1/N · Source" display label for an item's best drop, or null
	 * when unsourced. {@code attempts} is the mean completions to the drop
	 * (1/p), so it reads back as the "1 in N" odds players know.
	 */
	/** The activity a drop comes from ("Cerberus"), or null — the plan-facts
	 *  join uses it to answer "does the plan want kills here" (2026-07-20
	 *  intelligence arc). */
	public String activityName(int itemId)
	{
		if (clog == null)
		{
			return null;
		}
		ClogPack.Activity activity = activityByItem.get(clog.canonical(itemId));
		return activity == null ? null : activity.name;
	}

	public String dropLabel(int itemId)
	{
		if (clog == null)
		{
			return null;
		}
		int id = clog.canonical(itemId);
		ClogPack.Activity activity = activityByItem.get(id);
		ClogPack.Item item = dropByItem.get(id);
		if (activity == null || item == null)
		{
			return null;
		}
		return "1/" + Math.round(item.attempts) + " · from " + sourceLabel(activity.name);
	}

	/** Log Adviser gerund verbs that open its activity names ("killing
	 *  araxxor", "sorting through small salvage") — stripped for display. */
	private static final Set<String> ACTIVITY_VERBS = Set.of(
		"killing", "looting", "defeating", "harvesting", "hunting", "catching",
		"pickpocketing", "stealing", "mining", "fishing", "woodcutting",
		"collecting", "sorting", "opening", "earning", "completing", "getting",
		"finishing", "delving", "searching", "pulling", "rummaging", "finding",
		"spending", "exploring", "running", "giving", "farming", "thieving");
	private static final Set<String> ACTIVITY_PREPOSITIONS =
		Set.of("through", "from", "in", "at");

	/**
	 * The plain SOURCE behind a Log Adviser activity name, for drop-rate copy
	 * (Luke: "1/75 · from Royal titans", never "Looting eldric, the ice king
	 * (royal titans)"). A trailing all-letters parenthetical is the real
	 * activity ("(royal titans)", "(corrupted gauntlet)"); otherwise the
	 * leading gerund verb (+ its preposition) drops; modifier parens like
	 * "(on task)" or "(150)" stay with the name they qualify.
	 */
	static String sourceLabel(String activityName)
	{
		String name = activityName.trim();
		java.util.regex.Matcher parens =
			java.util.regex.Pattern.compile("\\(([^)]+)\\)\\s*$").matcher(name);
		if (parens.find())
		{
			String inner = parens.group(1).trim();
			if (inner.matches("[a-z' -]+") && !inner.contains("task"))
			{
				return capitalize(inner);
			}
		}
		String[] words = name.split("\\s+", 2);
		if (words.length == 2 && ACTIVITY_VERBS.contains(words[0].toLowerCase(Locale.ROOT)))
		{
			String rest = words[1];
			String[] next = rest.split("\\s+", 2);
			if (next.length == 2 && ACTIVITY_PREPOSITIONS.contains(next[0].toLowerCase(Locale.ROOT)))
			{
				rest = next[1];
			}
			return capitalize(rest);
		}
		return capitalize(name);
	}

	private static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String normalize(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
