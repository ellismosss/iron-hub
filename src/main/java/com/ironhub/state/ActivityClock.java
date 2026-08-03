package com.ironhub.state;

/**
 * Idle-gated activity time (H5, 2026-08-03). A "time taken" figure that
 * keeps counting while the player is off doing something else is a lie —
 * the rumour timer ran forever. The reusable rule: time accrues between
 * consecutive ACTIVITY SIGNALS (a Hunter xp drop, a slayer kill), and a
 * gap longer than the grace window contributes only the grace — the
 * player had clearly moved on, and the clock resumes with the activity.
 *
 * <p>Pure arithmetic over persisted {@code activeMs}/{@code lastActivityMs}
 * fields so every "time taken" surface (rumour timer, slayer task
 * duration) shares ONE definition of active time. Records from before
 * these fields existed have {@code lastActivityMs == 0}; callers fall
 * back to wall-clock for those rather than inventing a figure.
 */
public final class ActivityClock
{
	/** How much of an inactivity gap still counts as "doing it". */
	public static final long IDLE_GRACE_MS = 3 * 60_000L;

	private ActivityClock()
	{
	}

	/** The accrued total after a signal at {@code nowMs}: the gap since the
	 *  previous signal, capped at the grace window. */
	public static long accrue(long activeMs, long lastActivityMs, long nowMs)
	{
		if (lastActivityMs <= 0 || nowMs <= lastActivityMs)
		{
			return activeMs;
		}
		return activeMs + Math.min(nowMs - lastActivityMs, IDLE_GRACE_MS);
	}

	/** Active time to DISPLAY: the accrued total plus the capped live tail
	 *  since the last signal (a closed record's tail stops at its end).
	 *  Returns -1 for a legacy record with no activity tracking — the
	 *  caller shows wall-clock rather than a made-up figure. */
	public static long activeElapsed(long activeMs, long lastActivityMs, long endMs, long nowMs)
	{
		if (lastActivityMs <= 0)
		{
			return -1;
		}
		long cutoff = endMs > 0 ? Math.min(endMs, nowMs) : nowMs;
		return accrue(activeMs, lastActivityMs, cutoff);
	}
}
