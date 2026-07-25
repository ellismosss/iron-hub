package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The curated art and the theme rule Luke set (2026-07-24): a sprite changes
 * with the theme only where the pack ships art for it; everything else is
 * the same sprite in every theme. Dark Vanilla joined on 2026-07-25.
 */
public class V2SpritesTest
{
	@Test
	public void everyIndexedSpriteActuallyShips()
	{
		Map<String, V2Sprites.Meta> all = V2Sprites.all();
		assertTrue("the index looks truncated: " + all.size(), all.size() > 400);
		for (Map.Entry<String, V2Sprites.Meta> entry : all.entrySet())
		{
			for (OsrsTheme theme : OsrsTheme.values())
			{
				assertNotNull(entry.getKey(), V2Sprites.get(theme, entry.getKey()));
			}
		}
	}

	/**
	 * The theme layer in one assertion each way: a theme gets the pack's art
	 * where it exists, and the SAME image as vanilla where it doesn't. The
	 * fallback is the model — a resource pack overrides what it ships and
	 * leaves the rest of the interface alone.
	 */
	@Test
	public void aThemeGetsPackArtWhereItExistsAndVanillaEverywhereElse()
	{
		int reskinned = 0;
		int shared = 0;
		for (Map.Entry<String, V2Sprites.Meta> entry : V2Sprites.all().entrySet())
		{
			V2Sprites.Meta meta = entry.getValue();
			BufferedImage vanilla = V2Sprites.get(OsrsTheme.STONE, entry.getKey());
			for (OsrsTheme theme : new OsrsTheme[]{OsrsTheme.MYSTIC, OsrsTheme.DARK})
			{
				BufferedImage themed = V2Sprites.get(theme, entry.getKey());
				if (meta.has(theme.spriteVariant()) && meta.has("vanilla"))
				{
					assertFalse("re-skinned sprite resolves to the vanilla image: "
						+ entry.getKey() + " " + theme, vanilla == themed);
					reskinned++;
				}
				else if (!meta.packOnly())
				{
					assertSame("a sprite the pack doesn't ship must fall back to vanilla: "
						+ entry.getKey() + " " + theme, vanilla, themed);
					shared++;
				}
			}
		}
		// counts move when Luke curates more art; they are here so a silent
		// collapse of the theme layer can't pass
		assertEquals("the curated set changed — rerun tools/gen_v2_sprites.py",
			160, reskinned);
		assertTrue(shared > 500);
	}

	/** Dark Vanilla must re-skin every surface the atoms are built on, or a
	 *  vanilla sprite turns up in the middle of a dark panel. */
	@Test
	public void darkVanillaCoversEverySurfaceTheAtomsUse()
	{
		for (String key : new String[]{
			"ui/buttons/enter_wilderness_teleport",
			"ui/buttons/enter_wilderness_teleport_hovered",
			"ui/borders/equipment_metal_corner_top_left",
			"ui/borders/equipment_edge_top",
			"ui/borders/bottom_line_mode_side_panel_edge_top",
			"ui/borders/bottom_line_mode_side_panel_edge_horizontal",
			"ui/buttons/regular_large",
			"ui/checkbox/square_bordered_checkbox",
			"ui/checkbox/square_bordered_checkbox_checked",
			"icons/equipment/slot_tile",
			"icons/search/search_1"})
		{
			assertTrue(key + " has no dark variant",
				V2Sprites.meta(key).has("dark"));
			assertEquals("dark", V2Sprites.variantFor(OsrsTheme.DARK, V2Sprites.meta(key)));
		}
	}

	/** Pack-only art has no vanilla original; it is shared by every theme and
	 *  stays flagged so the gallery never implies it is the game's own. */
	@Test
	public void packOnlyArtIsFlaggedAndSharedByEveryTheme()
	{
		long flagged = V2Sprites.all().values().stream()
			.filter(V2Sprites.Meta::packOnly).count();
		assertEquals(82, flagged);
		V2Sprites.Meta sort = V2Sprites.meta("ui/arrows/list_sorting_arrow_ascending");
		assertTrue(sort.packOnly());
		assertSame(V2Sprites.get(OsrsTheme.STONE, "ui/arrows/list_sorting_arrow_ascending"),
			V2Sprites.get(OsrsTheme.DARK, "ui/arrows/list_sorting_arrow_ascending"));
		assertFalse(V2Sprites.meta("ui/buttons/regular_large").packOnly());
	}

	@Test
	public void anUnknownKeyThrowsRatherThanPaintingNothing()
	{
		try
		{
			V2Sprites.get(OsrsTheme.STONE, "ui/buttons/does_not_exist");
			fail("a typo in an atom must not silently render nothing");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("gen_v2_sprites"));
		}
	}
}
