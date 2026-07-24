package com.ironhub.modules.gear;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.EquipmentPack;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Gear library's filter/sort over the real equipment pack. */
public class EquipmentLibraryTest
{
	private static final EquipmentPack PACK =
		new DataPack(new Gson()).load("equipment", EquipmentPack.class);

	private EquipmentLibrary library(Set<String> owned)
	{
		// headless: the market value is the high-alch fallback (no ItemManager)
		return new EquipmentLibrary(PACK, item -> owned.contains(item.name), item -> item.alch);
	}

	@Test
	public void everySlotHasItemsAndTheStatArrayIsFixed()
	{
		Set<String> slots = new java.util.HashSet<>();
		for (EquipmentPack.Item item : PACK.items)
		{
			slots.add(item.slot);
			assertEquals("stats are 14 fixed bonuses", 14, item.stats.length);
		}
		assertTrue("every slot present", slots.containsAll(List.of("head", "cape", "neck",
			"ammo", "weapon", "2h", "body", "shield", "legs", "hands", "feet", "ring")));
	}

	@Test
	public void slotFilterKeepsOnlyThatSlot()
	{
		List<EquipmentPack.Item> rings = library(Set.of()).query(
			"", "ring", EquipmentLibrary.Owned.ALL, EquipmentLibrary.Access.ALL,
			EquipmentLibrary.Sort.NAME, true);
		assertFalse(rings.isEmpty());
		assertTrue(rings.stream().allMatch(i -> i.slot.equals("ring")));
	}

	@Test
	public void searchMatchesTheName()
	{
		List<EquipmentPack.Item> whip = library(Set.of()).query(
			"abyssal whip", null, EquipmentLibrary.Owned.ALL, EquipmentLibrary.Access.ALL,
			EquipmentLibrary.Sort.NAME, true);
		assertTrue(whip.stream().anyMatch(i -> i.name.equals("Abyssal whip")));
		assertTrue(whip.stream().allMatch(i -> i.name.toLowerCase().contains("abyssal whip")));
	}

	@Test
	public void valueSortLeadsWithTheMostExpensive()
	{
		EquipmentLibrary library = library(Set.of());
		List<EquipmentPack.Item> byValue = library.query(
			"", null, EquipmentLibrary.Owned.ALL, EquipmentLibrary.Access.ALL,
			EquipmentLibrary.Sort.VALUE, false);
		// the leader is worth more than the item 100 places down — the metric
		// is genuinely descending, not just a stable no-op (value here is the
		// high-alch fallback, since the test has no live prices)
		assertTrue(library.value(byValue.get(0)) >= library.value(byValue.get(100)));
		assertTrue(library.value(byValue.get(0)) > 1_000_000);
	}

	@Test
	public void statSortRanksByThatBonus()
	{
		List<EquipmentPack.Item> bySlash = library(Set.of()).query(
			"", "weapon", EquipmentLibrary.Owned.ALL, EquipmentLibrary.Access.ALL,
			EquipmentLibrary.Sort.SLASH, false);
		int top = bySlash.get(0).stats[EquipmentPack.A_SLASH];
		assertTrue("weapons carry a slash bonus", top > 0);
		for (EquipmentPack.Item item : bySlash)
		{
			assertTrue("descending by slash", item.stats[EquipmentPack.A_SLASH] <= top);
		}
	}

	@Test
	public void ownedAndMissingPartitionTheLibrary()
	{
		Set<String> owned = Set.of("Abyssal whip", "Rune platebody");
		EquipmentLibrary library = library(owned);
		List<EquipmentPack.Item> mine = library.query("", null, EquipmentLibrary.Owned.OWNED,
			EquipmentLibrary.Access.ALL, EquipmentLibrary.Sort.NAME, true);
		List<EquipmentPack.Item> missing = library.query("", null, EquipmentLibrary.Owned.MISSING,
			EquipmentLibrary.Access.ALL, EquipmentLibrary.Sort.NAME, true);
		assertEquals(2, mine.size());
		assertTrue(mine.stream().allMatch(i -> owned.contains(i.name)));
		assertTrue(missing.stream().noneMatch(i -> owned.contains(i.name)));
		assertEquals(PACK.items.size(), mine.size() + missing.size());
	}

	@Test
	public void accessFilterSplitsMembersAndFree()
	{
		EquipmentLibrary library = library(Set.of());
		List<EquipmentPack.Item> free = library.query("", null, EquipmentLibrary.Owned.ALL,
			EquipmentLibrary.Access.FREE, EquipmentLibrary.Sort.NAME, true);
		assertFalse(free.isEmpty());
		assertTrue(free.stream().noneMatch(i -> i.members));
	}

	/** Variant grouping strips trailing parentheticals down to the base name. */
	@Test
	public void baseNameStripsVariantMarkers()
	{
		assertEquals("Avernic treads", GearLibraryTab.baseName("Avernic treads (pr)(pe)"));
		assertEquals("Rune platebody", GearLibraryTab.baseName("Rune platebody (t)"));
		assertEquals("Amulet of glory", GearLibraryTab.baseName("Amulet of glory(4)"));
		assertEquals("Abyssal whip", GearLibraryTab.baseName("Abyssal whip"));
	}

	/** A set's name is the base minus its piece-type word. */
	@Test
	public void setKeyStripsThePieceWord()
	{
		assertEquals("Rune", GearLibraryTab.setKey("Rune platebody"));
		assertEquals("Rune", GearLibraryTab.setKey("Rune full helm"));
		assertEquals("Masori", GearLibraryTab.setKey("Masori body (f)"));
		assertEquals("Ancestral", GearLibraryTab.setKey("Ancestral robe top"));
		assertEquals("Bandos", GearLibraryTab.setKey("Bandos chestplate"));
		// a weapon has no piece word — it stands alone
		assertEquals("Rune scimitar", GearLibraryTab.setKey("Rune scimitar"));
		assertEquals("Abyssal whip", GearLibraryTab.setKey("Abyssal whip"));
	}

	/** Hiding Leagues/Deadman rewards drops the flagged items. */
	@Test
	public void hideLeaguesDropsRewardItems()
	{
		EquipmentLibrary library = library(Set.of());
		List<EquipmentPack.Item> all = library.query("", null, EquipmentLibrary.Owned.ALL,
			EquipmentLibrary.Access.ALL, EquipmentLibrary.Sort.NAME, true, false);
		List<EquipmentPack.Item> mainGame = library.query("", null, EquipmentLibrary.Owned.ALL,
			EquipmentLibrary.Access.ALL, EquipmentLibrary.Sort.NAME, true, true);
		assertTrue("some items are Leagues/Deadman", all.size() > mainGame.size());
		assertTrue(mainGame.stream().noneMatch(i -> i.leagues));
		// the Twisted slayer helmet is a Twisted League reward
		assertTrue(all.stream().anyMatch(i -> i.name.equals("Twisted slayer helmet")));
		assertTrue(mainGame.stream().noneMatch(i -> i.name.equals("Twisted slayer helmet")));
	}
}
