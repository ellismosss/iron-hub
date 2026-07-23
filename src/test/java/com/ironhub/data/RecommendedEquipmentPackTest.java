package com.ironhub.data;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The wiki recommended-gear pack + its monster/task name join
 * (design/KB-RUNTIME.md slice 3).
 */
public class RecommendedEquipmentPackTest
{
	private final RecommendedEquipmentPack pack =
		new DataPack(new Gson()).load("recommended-equipment", RecommendedEquipmentPack.class);

	@Test
	public void strategyPagesMatchByMonsterName()
	{
		List<RecommendedEquipmentPack.Activity> zulrah = pack.match("Zulrah");
		assertEquals(4, zulrah.size());
		assertTrue(zulrah.stream().anyMatch(a -> "Magic".equals(a.getStyle())));
		assertTrue(zulrah.stream().anyMatch(a -> "Ranged".equals(a.getStyle())));
	}

	@Test
	public void slayerTaskPagesMatchBothWays()
	{
		// the corpus monster name is singular, the task page is plural —
		// both directions must land on "Slayer task/Dust devils"
		assertEquals(1, pack.match("Dust devil").size());
		assertEquals(1, pack.match("Dust devils").size());
		assertEquals("Slayer task/Dust devils",
			pack.match("dust devil").get(0).getPage());
	}

	@Test
	public void unknownStaysEmpty()
	{
		assertTrue(pack.match("Definitely not a monster").isEmpty());
		assertTrue(pack.match(null).isEmpty());
		assertTrue(pack.match("  ").isEmpty());
	}

	@Test
	public void slotOrderCoversEverySlotKey()
	{
		// a slot key SLOT_ORDER misses would silently vanish from the fold
		Set<String> known = new HashSet<>(List.of(RecommendedEquipmentPack.SLOT_ORDER));
		for (RecommendedEquipmentPack.Activity a : pack.getActivities())
		{
			for (String slot : a.getSlots().keySet())
			{
				assertTrue("uncovered slot key: " + slot + " on " + a.getPage(),
					known.contains(slot));
			}
		}
	}

	@Test
	public void packShapeSane()
	{
		assertTrue(pack.getActivities().size() >= 400);
		// every rec has a name; itemId may honestly be absent
		for (RecommendedEquipmentPack.Activity a : pack.getActivities())
		{
			a.getSlots().values().forEach(recs -> recs.forEach(r ->
				assertTrue(a.getPage(), r.getName() != null && !r.getName().isBlank())));
		}
	}
}
