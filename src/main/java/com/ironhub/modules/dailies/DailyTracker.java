package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Whether each repeatable event is claimable right now.
 *
 * <p>Detection is ported from RuneLite's core Daily Task Indicator plugin
 * ({@code net.runelite.client.plugins.dailytaskindicators.DailyTasksPlugin},
 * BSD-2-Clause — see {@code licenses/runelite-LICENSE}) at tag
 * runelite-parent-1.12.32, and keeps its varbit semantics byte-faithful:
 *
 * <ul>
 *   <li>a "last claimed" varbit reads <b>0 while the daily is UNCLAIMED</b> —
 *       the flag is set when you take it, so 0 means "go and get it";</li>
 *   <li>the game only refreshes those varbits on login, so a reset crossed
 *       while you stay logged in leaves them stale. Core covers that with its
 *       {@code dailyReset} escape hatch ({@code varbit == 0 || dailyReset});
 *       {@code crossedReset} here is the same flag.</li>
 * </ul>
 *
 * Pure and static so it unit-tests without a client.
 */
final class DailyTracker
{
	private static final long DAY_MS = 86_400_000L; // core's ONE_DAY

	/** Tears of Guthix returns 7 days after the last visit, at 00:00 UTC —
	 *  "the same day of the week in UTC as the previous attempt" (wiki). */
	private static final long TOG_COOLDOWN_MS = 7 * DAY_MS;

	private DailyTracker()
	{
	}

	enum State
	{
		/** Claimable now. */
		AVAILABLE,
		/** Already taken this reset. */
		DONE,
		/** Requirements not met — never shown as outstanding. */
		LOCKED,
		/** Genuinely not knowable (see {@link #stateOf}) — never guessed at. */
		UNKNOWN
	}

	/**
	 * Live state of one event.
	 *
	 * @param crossedReset a daily reset has passed since the varbits were last
	 *                     refreshed from the server (login) — core's
	 *                     {@code dailyReset}; the varbit may claim "taken"
	 *                     when the server has already reissued it.
	 */
	static State stateOf(AccountState state, DailiesPack.Daily daily, boolean crossedReset, long now)
	{
		if (!requirement(daily).isMet(state))
		{
			return State.LOCKED;
		}
		DailiesPack.Detection detection = daily.detection;
		String mode = detection == null ? "manual" : detection.mode;
		switch (mode)
		{
			case "flag":
				// core: varbit == 0 (unclaimed) || dailyReset
				return state.getVarbit(detection.varbit) == 0 || crossedReset
					? State.AVAILABLE : State.DONE;
			case "count":
				// core's bonemeal check: collected < max || dailyReset, where max
				// is the best diary tier's allowance.
				return state.getVarbit(detection.varbit) < quantity(state, daily) || crossedReset
					? State.AVAILABLE : State.DONE;
			case "rolling7":
				return togState(state, daily, now);
			default:
				// No claim flag exists (Miscellania) — a manual tick is the only
				// truth we have, and it expires at the daily reset.
				return manuallyDone(state, daily, now) ? State.DONE : State.AVAILABLE;
		}
	}

	/**
	 * Tears of Guthix. No varbit tracks the 7-day cooldown — varbit 5099
	 * ("TOG_COUNTDOWN") is the in-minigame ticks-left timer, not a weekly one —
	 * so the only honest source is a visit we watched happen. Until we have
	 * seen one, this is UNKNOWN rather than a guess in either direction.
	 */
	private static State togState(AccountState state, DailiesPack.Daily daily, long now)
	{
		long lastPlayed = state.dailyDoneAt(daily.id);
		if (lastPlayed <= 0)
		{
			return State.UNKNOWN;
		}
		return now < startOfUtcDay(lastPlayed) + TOG_COOLDOWN_MS ? State.DONE : State.AVAILABLE;
	}

	/** Ticked by hand and still inside the current daily reset window. */
	static boolean manuallyDone(AccountState state, DailiesPack.Daily daily, long now)
	{
		long doneAt = state.dailyDoneAt(daily.id);
		return doneAt > 0 && doneAt >= startOfUtcDay(now);
	}

	/**
	 * How many you get right now — the best tier whose requirement is met
	 * (tiers are pack-ordered smallest first). 0 when the pack lists none.
	 */
	static int quantity(AccountState state, DailiesPack.Daily daily)
	{
		int qty = 0;
		if (daily.tiers != null)
		{
			for (DailiesPack.Tier tier : daily.tiers)
			{
				if (tier.req == null || Requirements.parse(tier.req).isMet(state))
				{
					qty = tier.qty;
				}
			}
		}
		return qty;
	}

	/** Epoch millis of 00:00 UTC on the day containing {@code now} — the
	 *  daily reset, and core's {@code floor(t / ONE_DAY) * ONE_DAY}. */
	static long startOfUtcDay(long now)
	{
		return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
			.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	/** All of an event's access gates as one requirement (empty = always open). */
	static Requirement requirement(DailiesPack.Daily daily)
	{
		if (daily.reqs == null || daily.reqs.isEmpty())
		{
			return Requirements.allOf();
		}
		return Requirements.allOf(daily.reqs.stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}
}
