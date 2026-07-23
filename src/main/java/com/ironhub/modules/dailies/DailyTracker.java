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
		/** Claimable, but you do not have what it takes to claim it. */
		SHORT,
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
		State claimed = claimState(state, daily, crossedReset, now, mode);
		// Supplies only matter for something you could otherwise go and do.
		return claimed == State.AVAILABLE && !missing(state, daily).isEmpty()
			? State.SHORT : claimed;
	}

	private static State claimState(AccountState state, DailiesPack.Daily daily,
		boolean crossedReset, long now, String mode)
	{
		DailiesPack.Detection detection = daily.detection;
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
			case "approval":
				// Miscellania has no "collected today" flag, and 100% approval
				// does NOT mean the daily is done — collecting from Ghrim and
				// topping the coffer is the daily work (calling full approval
				// "done" greyed the kingdom out for well-kept accounts — Luke,
				// 2026-07-23). The varbit (0..127, core's MAX_APPROVAL) and the
				// coffer varp are display data; only the manual tick closes it.
				return manuallyDone(state, daily, now) ? State.DONE : State.AVAILABLE;
			case "rolling7":
				return togState(state, daily, now);
			default:
				// No claim flag exists (Miscellania) — a manual tick is the only
				// truth we have, and it expires at the daily reset.
				return manuallyDone(state, daily, now) ? State.DONE : State.AVAILABLE;
		}
	}

	/**
	 * Tears of Guthix, from two live signals, best first.
	 *
	 * <p>No varbit tracks the 7-day cooldown — varbit 5099 ("TOG_COUNTDOWN") is
	 * the in-minigame ticks-left timer, not a weekly one. But the game will
	 * simply tell you: ask Juna for reminders and it announces "You are
	 * eligible to drink from the Tears of Guthix" once a day, every day, for as
	 * long as you stay eligible. That message is the best answer there is —
	 * it needs no history from us and survives us having missed a visit.
	 *
	 * <p>Failing that, we fall back to a visit we watched happen and count 7
	 * days from it. With neither, this is UNKNOWN rather than a guess in either
	 * direction (the reminder is opt-in, so silence is not evidence).
	 */
	private static State togState(AccountState state, DailiesPack.Daily daily, long now)
	{
		if (state.isUnlocked(eligibleKey(daily)))
		{
			return State.AVAILABLE; // the game said so, and we've seen no visit since
		}
		long lastPlayed = state.dailyDoneAt(daily.id);
		if (lastPlayed <= 0)
		{
			return State.UNKNOWN;
		}
		return now < startOfUtcDay(lastPlayed) + TOG_COOLDOWN_MS ? State.DONE : State.AVAILABLE;
	}

	/** Unlock flag holding "the game announced this is claimable, and we have
	 *  not seen you claim it since". No colons — they don't round-trip through
	 *  the requirement graph's {@code unlock:} parse. */
	static String eligibleKey(DailiesPack.Daily daily)
	{
		return "dailyeligible_" + daily.id;
	}

	/**
	 * What you still need to bring, as "13 bones (have 4)" — empty when you are
	 * stocked, or when the pack does not gate on it (you can always buy fewer
	 * battlestaves; you cannot make bonemeal out of bones you do not own).
	 * Counts bank + inventory + worn, because a run starts at a bank.
	 */
	static java.util.List<String> missing(AccountState state, DailiesPack.Daily daily)
	{
		if (daily.bring == null || daily.bring.isEmpty())
		{
			return java.util.List.of();
		}
		int units = Math.max(1, quantity(state, daily));
		java.util.List<String> out = new java.util.ArrayList<>();
		for (DailiesPack.Bring bring : daily.bring)
		{
			if (bring.itemIds == null)
			{
				continue; // told, not gated
			}
			int needed = bring.per * units;
			int owned = 0;
			for (int itemId : bring.itemIds)
			{
				owned += state.ownedCount(itemId);
			}
			if (owned < needed)
			{
				out.add(needed + " " + bring.label + " (have " + owned + ")");
			}
		}
		return out;
	}

	/** Miscellania's approval varbit is 0..127 — core's
	 *  KingdomPlugin.getApprovalPercent, kept identical. */
	static int approvalPercent(int approval)
	{
		return (approval * 100) / MAX_APPROVAL;
	}

	/** core KingdomPlugin.MAX_APPROVAL — the varbit's full scale, not 100. */
	static final int MAX_APPROVAL = 127;

	/** core VarPlayer.KINGDOM_COFFER — synced at login like the approval varbit. */
	static final int KINGDOM_COFFER_VARP = 74;

	/**
	 * How many trips this stop takes at your tier — Robin gives back two
	 * unnoted items per bone, so an inventory only holds 14 bones' worth. 1
	 * when the pack does not say a trip is limited.
	 */
	static int trips(AccountState state, DailiesPack.Daily daily)
	{
		if (daily.perTrip == null || daily.perTrip < 1)
		{
			return 1;
		}
		int units = Math.max(1, quantity(state, daily));
		return (units + daily.perTrip - 1) / daily.perTrip;
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
