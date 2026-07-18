package com.ironhub.engine;

import com.ironhub.data.ClogPack;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

	private static String normalize(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
