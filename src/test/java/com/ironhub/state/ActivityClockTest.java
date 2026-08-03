package com.ironhub.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * H5 (2026-08-03): "time taken" means ACTIVE time. The clock accrues
 * between activity signals, an idle gap contributes at most the grace
 * window, and legacy records (no activity data) report -1 so callers
 * fall back to wall-clock instead of inventing a figure.
 */
public class ActivityClockTest
{
	private static final long MIN = 60_000L;

	@Test
	public void idleGapsContributeAtMostTheGrace()
	{
		long t0 = 1_000_000L;
		// first signal: nothing to accrue yet
		long active = ActivityClock.accrue(0, 0, t0);
		assertEquals(0, active);
		long last = t0;
		// one minute of hunting
		active = ActivityClock.accrue(active, last, t0 + MIN);
		last = t0 + MIN;
		assertEquals(MIN, active);
		// half an hour away: only the grace counts
		active = ActivityClock.accrue(active, last, t0 + 31 * MIN);
		assertEquals(MIN + ActivityClock.IDLE_GRACE_MS, active);
	}

	@Test
	public void displayAddsTheCappedLiveTailAndStopsAtEnd()
	{
		long t0 = 1_000_000L;
		// active record, 30s since the last signal: tail counts
		assertEquals(MIN + 30_000,
			ActivityClock.activeElapsed(MIN, t0, 0, t0 + 30_000));
		// active record, an hour since the last signal: tail caps at grace
		assertEquals(MIN + ActivityClock.IDLE_GRACE_MS,
			ActivityClock.activeElapsed(MIN, t0, 0, t0 + 60 * MIN));
		// closed record: the tail stops at end, not now
		assertEquals(MIN + 10_000,
			ActivityClock.activeElapsed(MIN, t0, t0 + 10_000, t0 + 60 * MIN));
		// legacy record: -1, caller falls back to wall-clock
		assertEquals(-1, ActivityClock.activeElapsed(0, 0, 0, t0));
	}

	@Test
	public void backwardsClockNeverSubtracts()
	{
		assertEquals(MIN, ActivityClock.accrue(MIN, 1_000_000L, 999_000L));
	}
}
