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
 * with the theme ONLY if a {@code _mystic} twin sits beside it in the source
 * folder; everything else is the same art in both themes.
 */
public class V2SpritesTest
{
	@Test
	public void everyIndexedSpriteActuallyShips()
	{
		Map<String, V2Sprites.Meta> all = V2Sprites.all();
		assertTrue("the index looks truncated: " + all.size(), all.size() > 350);
		for (Map.Entry<String, V2Sprites.Meta> entry : all.entrySet())
		{
			for (OsrsTheme theme : OsrsTheme.values())
			{
				BufferedImage image = V2Sprites.get(theme, entry.getKey());
				assertNotNull(entry.getKey(), image);
				assertEquals(entry.getKey() + " width", entry.getValue().width(), image.getWidth());
				assertEquals(entry.getKey() + " height", entry.getValue().height(), image.getHeight());
			}
		}
	}

	/** The whole theme layer, in one assertion each way. */
	@Test
	public void onlySpritesWithAMysticTwinChangeBetweenThemes()
	{
		int themed = 0;
		int shared = 0;
		for (Map.Entry<String, V2Sprites.Meta> entry : V2Sprites.all().entrySet())
		{
			BufferedImage stone = V2Sprites.get(OsrsTheme.STONE, entry.getKey());
			BufferedImage mystic = V2Sprites.get(OsrsTheme.MYSTIC, entry.getKey());
			if (entry.getValue().themed())
			{
				// a themed sprite must be DIFFERENT art, not the same file
				// reached twice — that would make the theme setting a no-op
				assertFalse("themed sprite resolves to the same image: " + entry.getKey(),
					stone == mystic);
				themed++;
			}
			else
			{
				assertSame("untwinned sprite must be shared by both themes: " + entry.getKey(),
					stone, mystic);
				shared++;
			}
		}
		assertEquals("the pairing in the source folder changed — rerun tools/gen_v2_sprites.py",
			78, themed);
		assertEquals(312, shared);
	}

	/** Mystic-only art is used in both themes, but stays flagged so the
	 *  gallery can say so rather than implying it is vanilla. */
	@Test
	public void mysticOnlyArtIsFlagged()
	{
		long flagged = V2Sprites.all().values().stream()
			.filter(V2Sprites.Meta::mysticOnly).count();
		assertEquals(20, flagged);
		assertTrue(V2Sprites.meta("ui/arrows/list_sorting_arrow_ascending").mysticOnly());
		assertFalse(V2Sprites.meta("ui/buttons/regular_large").mysticOnly());
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
