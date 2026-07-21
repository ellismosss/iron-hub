package com.ironhub.engine;

import com.google.gson.Gson;
import com.ironhub.data.ClogPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.GearProgressionPack;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2 G3: the drop/kill rate source over clog.json — expected hours +
 * P90 spread from the shared geometric core, honest NaN for purchase slots
 * and unsourced items, and a cross-check that the two hour sources (gear
 * curated vs clog drop) never disagree by an order of magnitude.
 */
public class RateSourceTest
{
	private static ClogPack.Activity activity(String name, double perHour, double extraFirst,
		ClogPack.Item... items)
	{
		ClogPack.Activity a = new ClogPack.Activity();
		a.name = name;
		a.perHour = perHour;
		a.extraTimeFirst = extraFirst;
		a.reqs = List.of();
		a.items = new ArrayList<>(List.of(items));
		return a;
	}

	private static ClogPack.Item item(int id, double attempts)
	{
		ClogPack.Item it = new ClogPack.Item();
		it.itemId = id;
		it.name = "item" + id;
		it.attempts = attempts;
		return it;
	}

	private static ClogPack pack(ClogPack.Activity... activities)
	{
		ClogPack clog = new ClogPack();
		clog.activities = new ArrayList<>(List.of(activities));
		clog.aliases = List.of();
		clog.slots = List.of();
		clog.chatNames = List.of();
		return clog;
	}

	@Test
	public void obtainHoursAndP90()
	{
		// 10 completions/hr, a 1-in-50 drop: expected 50/10 = 5h;
		// P90 geometric = ceil(ln0.1 / ln(1-1/50)) = 114 completions → 11.4h
		RateSource rates = new RateSource(pack(activity("Boss", 10, 0, item(900, 50))));
		assertEquals(5.0, rates.obtainHours(900), 1e-9);
		assertEquals(11.4, rates.obtainSpreadHours(900), 1e-9);
		assertTrue(rates.hasDropRate(900));
	}

	@Test
	public void firstRunOverheadCountsOnce()
	{
		RateSource rates = new RateSource(pack(activity("Boss", 20, 0.5, item(901, 100))));
		assertEquals(100.0 / 20 + 0.5, rates.obtainHours(901), 1e-9);
	}

	@Test
	public void purchaseSlotsAndUnsourcedAreNaN()
	{
		RateSource rates = new RateSource(pack(activity("Shop", 5, 0, item(902, 0))));
		assertTrue("attempts==0 is a purchase slot, never rate-ranked",
			Double.isNaN(rates.obtainHours(902)));
		assertFalse(rates.hasDropRate(902));
		assertTrue("an item with no clog row is unknown",
			Double.isNaN(rates.obtainHours(123456)));
	}

	@Test
	public void lockedActivitiesContributeNoRate()
	{
		RateSource rates = new RateSource(pack(activity("Locked", 0, 0, item(903, 10))));
		assertTrue(Double.isNaN(rates.obtainHours(903)));
	}

	@Test
	public void bestSourceWins()
	{
		// same item drops from a fast activity (2h) and a slow one (20h) → 2h
		RateSource rates = new RateSource(pack(
			activity("Slow", 1, 0, item(904, 20)),
			activity("Fast", 10, 0, item(904, 20))));
		assertEquals(2.0, rates.obtainHours(904), 1e-9);
	}

	@Test
	public void killHoursByActivityName()
	{
		RateSource rates = new RateSource(pack(activity("Zulrah", 25, 0)));
		assertEquals(2.0, rates.killHours("Zulrah", 50), 1e-9);  // 50 / 25
		assertEquals(2.0, rates.killHours("zulrah", 50), 1e-9);  // case-insensitive
		assertTrue("an unknown source is never invented",
			Double.isNaN(rates.killHours("Not a boss", 50)));
	}

	/** Drop copy names the plain SOURCE, never the Log Adviser slot prose
	 *  (Luke: "1/75 · from Royal titans", not "Looting eldric, the ice king"). */
	@Test
	public void sourceLabelNamesThePlainActivity()
	{
		// a real-activity parenthetical wins
		assertEquals("Royal titans",
			RateSource.sourceLabel("Looting eldric, the ice king (royal titans)"));
		assertEquals("Corrupted gauntlet",
			RateSource.sourceLabel("Opening reward chest (corrupted gauntlet)"));
		assertEquals("Chambers of xeric",
			RateSource.sourceLabel("Ancient chest (chambers of xeric)"));
		// modifier parens ("on task", counts) stay with the stripped name
		assertEquals("Abyssal sire (on task)",
			RateSource.sourceLabel("Killing abyssal sire (on task)"));
		assertEquals("Tombs of amascut reward chests (150)",
			RateSource.sourceLabel("Tombs of amascut reward chests (150)"));
		// gerund verb + preposition drop
		assertEquals("Amoxliatl", RateSource.sourceLabel("Killing amoxliatl"));
		assertEquals("Small salvage", RateSource.sourceLabel("Sorting through small salvage"));
		assertEquals("Darkmeyer vyres", RateSource.sourceLabel("Pickpocketing from darkmeyer vyres"));
	}

	@Test
	public void nullClogYieldsOnlyNaN()
	{
		RateSource rates = new RateSource(null);
		assertTrue(Double.isNaN(rates.obtainHours(1)));
		assertTrue(Double.isNaN(rates.killHours("x", 1)));
		assertFalse(rates.hasDropRate(1));
	}

	/**
	 * Where an item has BOTH a curated gear-pack hours and a clog drop rate,
	 * the two must agree within an order of magnitude — a wilder disagreement
	 * is a data bug (a mislabeled rate), not a modelling choice.
	 */
	@Test
	public void gearAndClogHoursAgreeWithinAnOrderOfMagnitude()
	{
		DataPack data = new DataPack(new Gson());
		ClogPack clog = data.load("clog", ClogPack.class);
		GearProgressionPack gear = data.load("gear-progression", GearProgressionPack.class);
		RateSource rates = new RateSource(clog);

		int checked = 0;
		for (GearProgressionPack.Phase phase : gear.getPhases())
		{
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				for (GearProgressionPack.Item item : group.getItems())
				{
					if (item.getItemId() == null || item.getHours() == null || item.getHours() <= 0)
					{
						continue;
					}
					double clogHours = rates.obtainHours(item.getItemId());
					if (Double.isNaN(clogHours))
					{
						continue; // only cross-check items present in both sources
					}
					double ratio = Math.max(item.getHours() / clogHours, clogHours / item.getHours());
					assertTrue(item.getName() + ": gear " + item.getHours() + "h vs clog "
						+ clogHours + "h disagree by " + ratio + "x", ratio < 10);
					checked++;
				}
			}
		}
		assertTrue("no overlapping items cross-checked — did the packs change?", checked > 0);
	}
}
