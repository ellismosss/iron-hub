package com.ironhub.data;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemNameIndexTest
{
	/** R7 (2026-08-03): display names go through pretty() — the raw
	 *  normalized keys (FIRE_RUNE) leaked into the slayer history, and
	 *  coins had no entry at all ("Item 995"). */
	@Test
	public void namesReadLikeTheWikiWritesThem()
	{
		ItemNameIndex index = new ItemNameIndex(new Gson());
		assertEquals("Fire rune", index.nameOf(554));
		assertEquals("Vile ashes", index.nameOf(25769));
		assertEquals("Coins", index.nameOf(995));
		assertEquals("Abyssal whip", index.nameOf(4151));
	}
}
