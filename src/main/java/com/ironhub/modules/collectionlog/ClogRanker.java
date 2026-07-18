package com.ironhub.modules.collectionlog;

import com.ironhub.data.ClogPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Time-To-Next-Slot ranking, ported from Log Adviser's AdviserEngine
 * (github.com/SFranciscoSouza/LogAdviser, BSD-2-Clause), which mirrors the
 * community spreadsheet's "Time to next log slot" column: an activity's
 * still-missing items are grouped into four rate-mode buckets — Neither
 * (any of them can drop: harmonic combined rate), Independent-only,
 * Exact-only, and Exact+Independent (each timed by its smallest-attempts
 * item) — and the best bucket's expected hours win. Iron rates always
 * (this plugin only targets irons); gating runs through the shared
 * requirement graph instead of Log Adviser's own PlayerProgress.
 *
 * Pure functions over the pack + obtained/skipped sets: no caches, no
 * listeners — a full rank of the 256-activity catalog is microseconds.
 */
final class ClogRanker
{
	/** One rankable activity: the expected hours to its next slot, and the
	 *  slot that estimate is actually for (the winning bucket's driver). */
	static final class Ranked
	{
		final ClogPack.Activity activity;
		final double hours;
		final ClogPack.Item display;
		final int slotsLeft;
		final int slotsTotal;
		final boolean locked;
		final String missing; // unmet requirement labels; null when unlocked

		Ranked(ClogPack.Activity activity, double hours, ClogPack.Item display,
			int slotsLeft, int slotsTotal, String missing)
		{
			this.activity = activity;
			this.hours = hours;
			this.display = display;
			this.slotsLeft = slotsLeft;
			this.slotsTotal = slotsTotal;
			this.locked = missing != null;
			this.missing = missing;
		}
	}

	private ClogRanker()
	{
	}

	/** Activities with a slot left to chase, unlocked first then by expected
	 *  hours (locked demoted but still ordered, so the closest unlock floats
	 *  to the top of its block). {@code skipped} activities are excluded. */
	static List<Ranked> rank(ClogPack pack, Set<Integer> obtained, Set<Integer> skipped,
		StateView state)
	{
		return rank(pack, obtained, skipped, state, false);
	}

	/** The skipped activities only, ranked the same way (the recoverable sink). */
	static List<Ranked> rankSkipped(ClogPack pack, Set<Integer> obtained, Set<Integer> skipped,
		StateView state)
	{
		return rank(pack, obtained, skipped, state, true);
	}

	private static List<Ranked> rank(ClogPack pack, Set<Integer> obtained, Set<Integer> skipped,
		StateView state, boolean skippedOnly)
	{
		List<Ranked> out = new ArrayList<>();
		for (ClogPack.Activity activity : pack.activities)
		{
			if (skipped.contains(activity.index) != skippedOnly)
			{
				continue;
			}
			Ranked ranked = compute(activity, obtained, state);
			if (ranked != null)
			{
				out.add(ranked);
			}
		}
		out.sort((x, y) ->
		{
			if (x.locked != y.locked)
			{
				return x.locked ? 1 : -1;
			}
			return Double.compare(x.hours, y.hours);
		});
		return out;
	}

	/** Null when the activity has nothing chaseable (all obtained, gated
	 *  behind an unobtained predecessor with no free item, or no rate). */
	static Ranked compute(ClogPack.Activity activity, Set<Integer> obtained, StateView state)
	{
		List<ClogPack.Item> items = activity.items;
		if (activity.perHour <= 0.0 || items.isEmpty())
		{
			return null;
		}

		// Active flag mirrors the spreadsheet: !Completed AND
		// (!RequiresPrevious OR previous row Completed).
		boolean[] active = new boolean[items.size()];
		boolean prevCompleted = false;
		for (int i = 0; i < items.size(); i++)
		{
			ClogPack.Item it = items.get(i);
			boolean done = obtained.contains(it.itemId);
			active[i] = !done && (!it.requiresPrevious || prevCompleted);
			prevCompleted = done;
		}

		// Bucket active items by (exact, independent); for each bucket keep
		// the smallest-attempts item — the slot that drives the bucket's time.
		double sumInverseNeither = 0.0;
		double minNeither = Double.POSITIVE_INFINITY;
		double minInd = Double.POSITIVE_INFINITY;
		double minExact = Double.POSITIVE_INFINITY;
		double minEi = Double.POSITIVE_INFINITY;
		ClogPack.Item neitherMin = null;
		ClogPack.Item indMin = null;
		ClogPack.Item exactMin = null;
		ClogPack.Item eiMin = null;
		ClogPack.Item fastest = null;
		double fastestK = Double.POSITIVE_INFINITY;

		for (int i = 0; i < items.size(); i++)
		{
			if (!active[i])
			{
				continue;
			}
			ClogPack.Item it = items.get(i);
			double k = it.attempts;
			if (k <= 0.0)
			{
				continue;
			}
			if (!it.exact && !it.independent)
			{
				sumInverseNeither += 1.0 / k;
				if (k < minNeither)
				{
					minNeither = k;
					neitherMin = it;
				}
			}
			else if (!it.exact)
			{
				if (k < minInd)
				{
					minInd = k;
					indMin = it;
				}
			}
			else if (!it.independent)
			{
				if (k < minExact)
				{
					minExact = k;
					exactMin = it;
				}
			}
			else if (k < minEi)
			{
				minEi = k;
				eiMin = it;
			}
			if (k < fastestK)
			{
				fastestK = k;
				fastest = it;
			}
		}

		// hours = expected completions / completions-per-hour + first-run
		// overhead — the shared geometric core (CostModel) the engine's drop
		// coster also uses, so the two never drift (G3)
		double cph = activity.perHour;
		double timeNeither = sumInverseNeither > 0.0
			? com.ironhub.engine.CostModel.completionsToHours(
				1.0 / sumInverseNeither, cph, activity.extraTimeFirst)
			: Double.POSITIVE_INFINITY;
		double timeInd = Double.isFinite(minInd)
			? com.ironhub.engine.CostModel.completionsToHours(minInd, cph, activity.extraTimeFirst)
			: Double.POSITIVE_INFINITY;
		double timeExact = Double.isFinite(minExact)
			? com.ironhub.engine.CostModel.completionsToHours(minExact, cph, activity.extraTimeFirst)
			: Double.POSITIVE_INFINITY;
		double timeEi = Double.isFinite(minEi)
			? com.ironhub.engine.CostModel.completionsToHours(minEi, cph, activity.extraTimeFirst)
			: Double.POSITIVE_INFINITY;

		double best = Math.min(Math.min(timeNeither, timeInd), Math.min(timeExact, timeEi));
		if (!Double.isFinite(best))
		{
			return null;
		}

		// The display item is the winning bucket's min-attempts item (ties
		// broken toward fewer attempts); `best` is a min of the bucket times
		// so `== best` reliably identifies the winner.
		ClogPack.Item display = null;
		double displayK = Double.POSITIVE_INFINITY;
		if (neitherMin != null && timeNeither == best && minNeither < displayK)
		{
			display = neitherMin;
			displayK = minNeither;
		}
		if (indMin != null && timeInd == best && minInd < displayK)
		{
			display = indMin;
			displayK = minInd;
		}
		if (exactMin != null && timeExact == best && minExact < displayK)
		{
			display = exactMin;
			displayK = minExact;
		}
		if (eiMin != null && timeEi == best && minEi < displayK)
		{
			display = eiMin;
			displayK = minEi;
		}

		Set<Integer> uniq = new HashSet<>();
		Set<Integer> uniqDone = new HashSet<>();
		for (ClogPack.Item it : items)
		{
			uniq.add(it.itemId);
			if (obtained.contains(it.itemId))
			{
				uniqDone.add(it.itemId);
			}
		}

		return new Ranked(activity, best, display != null ? display : fastest,
			uniq.size() - uniqDone.size(), uniq.size(), missingLabel(activity, state));
	}

	/** Unmet requirement labels ("Slayer 85, Priest in Peril"), or null when
	 *  every requirement is met. */
	static String missingLabel(ClogPack.Activity activity, StateView state)
	{
		List<String> missing = new ArrayList<>();
		for (String req : activity.reqs)
		{
			com.ironhub.requirements.Requirement parsed = Requirements.parse(req);
			if (!parsed.isMet(state))
			{
				missing.add(parsed.describe());
			}
		}
		return missing.isEmpty() ? null : String.join(", ", missing);
	}
}
