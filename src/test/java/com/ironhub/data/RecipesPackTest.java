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
