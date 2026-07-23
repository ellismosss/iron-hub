package com.ironhub.data;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rank ladder the collection log's overview frames your count with.
 * Anchored on the two ranks visible in the game's own overview screenshot
 * (1,190 slots reads "VII: Rune" reached, "VIII: Dragon" next) — if the
 * parse ever mangles the table, that pairing is what breaks first.
 */
public class ClogRanksPackTest
{
	private static final ClogRanksPack PACK =
		new DataPack(new Gson()).load("clog-ranks", ClogRanksPack.class);

	@Test
	public void theLadderClimbsFromBronzeToGilded()
	{
		assertEquals(9, PACK.ranks.size());
		assertEquals("Bronze", PACK.ranks.get(0).name);
		assertEquals("Gilded", PACK.ranks.get(PACK.ranks.size() - 1).name);
		int previous = 0;
		for (ClogRanksPack.Rank rank : PACK.ranks)
		{
			assertTrue(rank.name + " must climb", rank.slots > previous);
			assertTrue(rank.name + " needs a staff sprite", rank.itemId > 0);
			previous = rank.slots;
		}
	}

	@Test
	public void aCountLandsBetweenTheRankReachedAndTheOneBeingClimbed()
	{
		int total = PACK.totalSlots;
		assertEquals("VII: Rune", PACK.label(PACK.reached(1190, total)));
		assertEquals("VIII: Dragon", PACK.label(PACK.next(1190, total)));
		assertNull("nothing is reached below the first rank", PACK.reached(99, total));
		assertEquals("Bronze", PACK.next(99, total).name);
	}

	/** Gilded is a SHARE of the log's total, so a game update that adds slots
	 *  must move it — quoting the generated number would go stale. */
	@Test
	public void theGildedThresholdFollowsTheLiveTotal()
	{
		ClogRanksPack.Rank gilded = PACK.ranks.get(PACK.ranks.size() - 1);
		assertEquals(1700, PACK.threshold(gilded, 1911));
		assertEquals(1800, PACK.threshold(gilded, 2000));
		// no live total known: the pack's own generated figure stands
		assertEquals(gilded.slots, PACK.threshold(gilded, 0));
	}
}
