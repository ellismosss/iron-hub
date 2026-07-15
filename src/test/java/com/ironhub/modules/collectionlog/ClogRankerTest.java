package com.ironhub.modules.collectionlog;

import com.google.gson.Gson;
import com.ironhub.data.ClogPack;
import com.ironhub.data.DataPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Time-To-Next-Slot math ported from Log Adviser: the four rate-mode
 * buckets, requires-previous chains, locked demotion, skips, and the real
 * pack's integrity (every requirement string must gate honestly).
 */
public class ClogRankerTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private AccountState state() throws Exception
	{
		return StateFixture.state(temp.newFolder());
	}

	private static ClogPack.Activity activity(int index, double perHour, ClogPack.Item... items)
	{
		ClogPack.Activity a = new ClogPack.Activity();
		a.index = index;
		a.name = "Activity " + index;
		a.perHour = perHour;
		a.extraTimeFirst = 0;
		a.category = "combat";
		a.reqs = List.of();
		a.items = List.of(items);
		return a;
	}

	private static ClogPack.Item item(int id, double attempts, boolean exact, boolean independent,
		boolean requiresPrevious)
	{
		ClogPack.Item it = new ClogPack.Item();
		it.itemId = id;
		it.name = "Item " + id;
		it.attempts = attempts;
		it.exact = exact;
		it.independent = independent;
		it.requiresPrevious = requiresPrevious;
		return it;
	}

	private static ClogPack pack(ClogPack.Activity... activities)
	{
		ClogPack p = new ClogPack();
		p.activities = List.of(activities);
		p.slots = new ArrayList<>();
		p.aliases = List.of();
		p.chatNames = List.of();
		return p;
	}

	@Test
	public void sharedDropsCombineHarmonically() throws Exception
	{
		// Two "neither" items at 1/100 each from a 50/hr activity: the next
		// slot arrives at the combined 1/50 rate — 1 hour, not 2.
		ClogPack.Activity a = activity(1, 50,
			item(11, 100, false, false, false),
			item(12, 100, false, false, false));
		ClogRanker.Ranked r = ClogRanker.compute(a, Set.of(), state());
		assertEquals(1.0, r.hours, 1e-9);
		assertEquals(2, r.slotsLeft);
		assertEquals(2, r.slotsTotal);
	}

	@Test
	public void exactAndIndependentBucketsTimeTheirOwnMinimum() throws Exception
	{
		// An exact 1/200 slot beats a shared 1/1000 pool: 4h vs 20h — and the
		// display item is the slot that produced the winning estimate.
		ClogPack.Activity a = activity(1, 50,
			item(11, 1000, false, false, false),
			item(12, 200, true, false, false));
		ClogRanker.Ranked r = ClogRanker.compute(a, Set.of(), state());
		assertEquals(4.0, r.hours, 1e-9);
		assertEquals(12, r.display.itemId);
	}

	@Test
	public void extraTimeFirstAddsSetupHours() throws Exception
	{
		ClogPack.Activity a = activity(1, 100, item(11, 100, false, false, false));
		a.extraTimeFirst = 2.5;
		assertEquals(3.5, ClogRanker.compute(a, Set.of(), state()).hours, 1e-9);
	}

	@Test
	public void requiresPreviousGatesUntilThePredecessorDrops() throws Exception
	{
		// Item 12 only becomes active once item 11 is obtained (the
		// spreadsheet's RequiresPrevious column).
		ClogPack.Activity a = activity(1, 50,
			item(11, 100, false, false, false),
			item(12, 100, false, false, true));
		ClogRanker.Ranked before = ClogRanker.compute(a, Set.of(), state());
		assertEquals(2.0, before.hours, 1e-9); // only item 11 active
		assertEquals(11, before.display.itemId);

		ClogRanker.Ranked after = ClogRanker.compute(a, Set.of(11), state());
		assertEquals(2.0, after.hours, 1e-9); // now only item 12
		assertEquals(12, after.display.itemId);
		assertEquals(1, after.slotsLeft);
	}

	@Test
	public void fullyObtainedActivitiesLeaveTheRanking() throws Exception
	{
		ClogPack.Activity a = activity(1, 50, item(11, 100, false, false, false));
		assertNull(ClogRanker.compute(a, Set.of(11), state()));
		assertTrue(ClogRanker.rank(pack(a), Set.of(11), Set.of(), state()).isEmpty());
	}

	@Test
	public void lockedActivitiesSinkBelowUnlockedButStayOrdered() throws Exception
	{
		AccountState state = state();
		StateFixture.stat(state, Skill.SLAYER, 50, 110_000);

		ClogPack.Activity slow = activity(1, 1, item(11, 100, false, false, false)); // 100h, unlocked
		ClogPack.Activity fastLocked = activity(2, 100, item(12, 100, false, false, false)); // 1h, locked
		fastLocked.reqs = List.of("skill:Slayer:85");

		List<ClogRanker.Ranked> ranked = ClogRanker.rank(pack(slow, fastLocked),
			Set.of(), Set.of(), state);
		assertEquals(1, ranked.get(0).activity.index); // unlocked first despite 100h
		assertTrue(ranked.get(1).locked);
		assertEquals("85 Slayer", ranked.get(1).missing);

		StateFixture.stat(state, Skill.SLAYER, 85, 3_258_594);
		ranked = ClogRanker.rank(pack(slow, fastLocked), Set.of(), Set.of(), state);
		assertEquals(2, ranked.get(0).activity.index); // unlocked now, and faster
		assertFalse(ranked.get(0).locked);
	}

	@Test
	public void skippedActivitiesMoveToTheSkippedRanking() throws Exception
	{
		ClogPack.Activity a = activity(1, 50, item(11, 100, false, false, false));
		ClogPack.Activity b = activity(2, 50, item(12, 100, false, false, false));
		ClogPack p = pack(a, b);

		List<ClogRanker.Ranked> main = ClogRanker.rank(p, Set.of(), Set.of(2), state());
		assertEquals(1, main.size());
		assertEquals(1, main.get(0).activity.index);

		List<ClogRanker.Ranked> skipped = ClogRanker.rankSkipped(p, Set.of(), Set.of(2), state());
		assertEquals(1, skipped.size());
		assertEquals(2, skipped.get(0).activity.index);
	}

	@Test
	public void theRealPackRanksHonestly() throws Exception
	{
		ClogPack pack = new DataPack(new Gson()).load("clog", ClogPack.class);
		AccountState state = state();

		// Every requirement string must gate through the graph — an
		// unparseable one would become a never-met manual leaf and silently
		// lock its activity forever.
		for (ClogPack.Activity a : pack.activities)
		{
			for (String req : a.reqs)
			{
				assertFalse(a.name + " req did not parse: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
		}

		// Every alias lands on a real slot, and slot names resolve ids.
		for (ClogPack.Alias alias : pack.aliases)
		{
			assertTrue(pack.slotIds().contains(alias.canonical));
			assertEquals(alias.canonical, pack.canonical(alias.alt));
		}
		assertEquals((Integer) 13642, pack.itemIdByName("Farmer's jacket"));

		// A fresh account ranks the full catalog with finite estimates,
		// deterministically.
		List<ClogRanker.Ranked> ranked = ClogRanker.rank(pack, Set.of(), Set.of(), state);
		assertTrue(ranked.size() > 200);
		for (ClogRanker.Ranked r : ranked)
		{
			assertTrue(r.activity.name, Double.isFinite(r.hours));
			assertTrue(r.activity.name, r.hours > 0);
			assertTrue(r.activity.name, r.display != null);
			assertTrue(r.activity.name, r.slotsLeft > 0);
		}
		List<ClogRanker.Ranked> again = ClogRanker.rank(pack, Set.of(), Set.of(), state);
		for (int i = 0; i < ranked.size(); i++)
		{
			assertEquals(ranked.get(i).activity.index, again.get(i).activity.index);
		}
	}
}
