package com.ironhub.modules.porttasks;

import com.ironhub.data.PortTasksPack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The noticeboard advisor's brain: exact shortest-tour math over the
 * player's courier jobs. A job is "pick cargo up at port A, deliver it
 * at port B" (pickup already done = a bare delivery visit); the tour
 * starts at the port you're standing in and must visit every job's
 * pickup before its delivery. Held-Karp over job events (at most ~12,
 * five slots + a candidate) — exact, not a heuristic.
 *
 * <p>The advisor ranks a board's offers by Sailing XP per tile the offer
 * ADDS to the optimal tour ({@link #marginalDistance}) — with boat speed
 * an unknown constant, xp-per-marginal-tile preserves the xp/h ordering
 * without inventing a speed figure. NaN distances (unroutable port)
 * propagate honestly.</p>
 */
public final class PortTaskPlanner
{
	private PortTaskPlanner()
	{
	}

	/** One courier job. {@code pickup} = -1 when the cargo is aboard. */
	public static final class Job
	{
		public final int pickup;   // port dbrow, or -1
		public final int deliver;  // port dbrow

		public Job(int pickup, int deliver)
		{
			this.pickup = pickup;
			this.deliver = deliver;
		}
	}

	/**
	 * Minimal total sailing distance starting at {@code fromPort},
	 * visiting every job's pickup before its delivery. 0 for no jobs;
	 * NaN when any needed leg is unroutable.
	 */
	public static double tourDistance(PortTasksPack pack, int fromPort, List<Job> jobs)
	{
		if (jobs.isEmpty())
		{
			return 0;
		}
		// events: pickup (where present) and delivery per job
		List<int[]> events = new ArrayList<>(); // {portDbrow, jobIndex, isDelivery}
		int[] pickupEvent = new int[jobs.size()];
		Arrays.fill(pickupEvent, -1);
		for (int j = 0; j < jobs.size(); j++)
		{
			Job job = jobs.get(j);
			if (job.pickup >= 0)
			{
				pickupEvent[j] = events.size();
				events.add(new int[]{job.pickup, j, 0});
			}
			events.add(new int[]{job.deliver, j, 1});
		}
		int n = events.size();
		double[][] leg = new double[n + 1][n];
		for (int e = 0; e < n; e++)
		{
			leg[n][e] = pack.distance(fromPort, events.get(e)[0]);
			for (int f = 0; f < n; f++)
			{
				leg[e][f] = pack.distance(events.get(e)[0], events.get(f)[0]);
			}
		}
		double[][] dp = new double[1 << n][n];
		for (double[] row : dp)
		{
			Arrays.fill(row, Double.POSITIVE_INFINITY);
		}
		for (int e = 0; e < n; e++)
		{
			if (allowed(e, 0, events, pickupEvent))
			{
				dp[1 << e][e] = leg[n][e];
			}
		}
		for (int mask = 1; mask < (1 << n); mask++)
		{
			for (int last = 0; last < n; last++)
			{
				double cur = dp[mask][last];
				if ((mask & (1 << last)) == 0 || Double.isInfinite(cur))
				{
					continue;
				}
				for (int next = 0; next < n; next++)
				{
					if ((mask & (1 << next)) != 0 || !allowed(next, mask, events, pickupEvent))
					{
						continue;
					}
					double cand = cur + leg[last][next];
					int nextMask = mask | (1 << next);
					if (cand < dp[nextMask][next])
					{
						dp[nextMask][next] = cand;
					}
				}
			}
		}
		double best = Double.POSITIVE_INFINITY;
		for (int last = 0; last < n; last++)
		{
			best = Math.min(best, dp[(1 << n) - 1][last]);
		}
		return Double.isInfinite(best) ? Double.NaN : best;
	}

	private static boolean allowed(int event, int visitedMask, List<int[]> events, int[] pickupEvent)
	{
		int[] e = events.get(event);
		if (e[2] == 0)
		{
			return true; // pickups are always legal
		}
		int pickup = pickupEvent[e[1]];
		return pickup < 0 || (visitedMask & (1 << pickup)) != 0;
	}

	/**
	 * Tiles the candidate job adds to the optimal tour over the active
	 * jobs. Never negative (a candidate on the way costs ~0). NaN when
	 * either tour is unroutable.
	 */
	public static double marginalDistance(PortTasksPack pack, int fromPort,
		List<Job> active, Job candidate)
	{
		return marginalDistance(pack, fromPort, active,
			tourDistance(pack, fromPort, active), candidate);
	}

	/** As above with the base tour precomputed — rankOffers scores every
	 *  offer against the same active set, so the identical "without" tour
	 *  is Held-Karp'd once, not once per offer (2026-07-20 audit). */
	public static double marginalDistance(PortTasksPack pack, int fromPort,
		List<Job> active, double without, Job candidate)
	{
		List<Job> with = new ArrayList<>(active);
		with.add(candidate);
		double total = tourDistance(pack, fromPort, with);
		if (Double.isNaN(without) || Double.isNaN(total))
		{
			return Double.NaN;
		}
		return Math.max(0, total - without);
	}
}
