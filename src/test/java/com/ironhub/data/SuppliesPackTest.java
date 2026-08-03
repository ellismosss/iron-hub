package com.ironhub.data;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The supplies catalog: every category carries a curated top-20 default and
 * a broader searchable membership. The load-bearing invariant is that the
 * catalog is a closed set of consumables and resources — a weapon (the old
 * module's Rune longsword bug) must never be a member.
 */
public class SuppliesPackTest
{
	private static final SuppliesPack PACK =
		new DataPack(new Gson()).load("supplies", SuppliesPack.class);

	@Test
	public void everyCategoryHasAnIconAndCuratedDefaults()
	{
		assertTrue("expected a handful of categories", PACK.categories.size() >= 5);
		for (SuppliesPack.Category c : PACK.categories)
		{
			assertNotNull(c.key, c.name);
			assertTrue(c.name + " needs an icon sprite", c.icon > 0);
			List<Integer> defaults = PACK.defaultIds(c.key);
			assertTrue(c.name + " should have ~10-20 defaults, had " + defaults.size(),
				defaults.size() >= 10 && defaults.size() <= 20);
			assertTrue(c.name + " icon must be a member of the catalog",
				PACK.item(c.icon) != null);
		}
	}

	@Test
	public void everyItemBelongsToAKnownCategory()
	{
		for (SuppliesPack.Item item : PACK.items)
		{
			assertTrue(item.name, item.id > 0);
			assertNotNull("item " + item.name + " has no category", item.category);
			assertNotNull("item " + item.name + " category " + item.category
				+ " is not declared", PACK.category(item.category));
		}
	}

	/** The whole point of the rebuild: no weapon/armour can be a supply. The
	 *  Rune longsword (ids 1303, 6897) was the reported bug. */
	@Test
	public void theCatalogContainsNoWeapon()
	{
		assertNull("Rune longsword must not be a supply", PACK.item(1303));
		assertNull("Rune longsword (g) must not be a supply", PACK.item(6897));
		for (SuppliesPack.Item item : PACK.items)
		{
			String n = item.name.toLowerCase();
			assertFalse(item.name + " looks like a weapon", n.endsWith("longsword")
				|| n.endsWith("scimitar") || n.endsWith("platebody")
				|| n.endsWith("2h sword") || n.endsWith("battleaxe"));
		}
	}

	@Test
	public void searchFindsSuppliesButNotJunk()
	{
		// a real supply is findable
		assertTrue("shark should be searchable",
			PACK.search("shark").stream().anyMatch(i -> i.name.equals("Shark")));
		// ammunition resolves (it lives in the equipment slot but is a supply)
		assertTrue("rune arrow should be searchable",
			PACK.search("rune arrow").stream().anyMatch(i -> i.name.equals("Rune arrow")));
		// an empty query lists nothing (search-gated)
		assertTrue(PACK.search("").isEmpty());
		assertTrue(PACK.search("   ").isEmpty());
	}

	private static void assertNull(String msg, Object o)
	{
		assertTrue(msg, o == null);
	}
}
