package com.ironhub.data;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2: recipes.json (tools/gen_recipes.py, wiki {{Recipe}} templates)
 * loads, and the full-raw decomposer reproduces Luke's Bracelet of slaughter
 * example end to end + terminates on any input.
 */
public class RecipesPackTest
{
	private final RecipesPack pack = new DataPack(new Gson()).load("recipes", RecipesPack.class);

	@Test
	public void loadsWithACanonicalBraceletChain()
	{
		assertTrue("too few recipes", pack.recipes.size() > 1000);
		// Bracelet of slaughter <- topaz bracelet + cosmic + 5 fire
		RecipesPack.Recipe brace = pack.recipe(21183);
		assertNotNull(brace);
		assertTrue(brace.materials.stream().anyMatch(m -> m.itemId == 21123));
		assertTrue(brace.materials.stream().anyMatch(m -> m.itemId == 564));
		assertTrue(brace.materials.stream().anyMatch(m -> m.itemId == 554 && m.qty == 5));
	}

	@Test
	public void decomposesBraceletOfSlaughterToRawMaterials()
	{
		// full raw (Luke): 100 bracelets -> essence/ore/uncut leaves
		Map<Integer, Integer> leaves = pack.decompose(21183, 100);
		assertEquals(Integer.valueOf(100), leaves.get(442));  // Silver ore
		assertEquals(Integer.valueOf(100), leaves.get(1629)); // Uncut red topaz
		assertEquals(Integer.valueOf(500), leaves.get(1436)); // Rune essence (5 fire each)
		assertEquals(Integer.valueOf(100), leaves.get(7936)); // Pure essence (cosmic)
		// no intermediate (topaz bracelet / silver bar) survives a full decompose
		assertTrue("intermediates fully decomposed", !leaves.containsKey(21123) && !leaves.containsKey(2355));
	}

	@Test
	public void aRawItemDecomposesToItself()
	{
		// Silver ore has no recipe -> it is its own leaf
		Map<Integer, Integer> leaves = pack.decompose(442, 7);
		assertEquals(1, leaves.size());
		assertEquals(Integer.valueOf(7), leaves.get(442));
	}

	/** Bank-aware gather (Luke's games-necklace report): owned intermediates
	 *  stop the walk, so only the truly-missing branch decomposes. */
	@Test
	public void gatherStopsAtOwnedIntermediates()
	{
		// 100 games necklaces = sapphire necklace (sapphire + gold bar) + cosmic
		// + water rune. Own plenty of gold bars, sapphires, cosmics.
		Map<Integer, Integer> bank = new java.util.HashMap<>();
		bank.put(2357, 200); // Gold bar
		bank.put(1607, 200); // Sapphire
		bank.put(564, 200);  // Cosmic rune
		Map<Integer, Integer> g = pack.gather(3853, 100, id -> bank.getOrDefault(id, 0));

		// the owned branches never decompose to raw…
		assertTrue("gold bars owned → no gold ore", !g.containsKey(444));
		assertTrue("sapphires owned → no uncut sapphire", !g.containsKey(1623));
		assertTrue("cosmics owned → no pure essence", !g.containsKey(7936));
		// …only the missing water-rune branch remains (full-raw → rune essence)
		assertEquals(Integer.valueOf(100), g.get(1436)); // Rune essence

		// own the water runes too → nothing to gather
		bank.put(555, 200);
		assertTrue("owning everything gathers nothing",
			pack.gather(3853, 100, id -> bank.getOrDefault(id, 0)).isEmpty());

		// own nothing → the full raw set
		Map<Integer, Integer> raw = pack.gather(3853, 100, id -> 0);
		assertEquals(Integer.valueOf(100), raw.get(444));  // Gold ore
		assertEquals(Integer.valueOf(100), raw.get(1623)); // Uncut sapphire
	}

	@Test
	public void decompositionAlwaysTerminates()
	{
		// every recipe output decomposes without hanging (cycle-safe)
		for (String key : pack.recipes.keySet())
		{
			pack.decompose(Integer.parseInt(key), 1);
		}
	}
}
