package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Rule one of the V2 system: sprites TILE, they never scale.
 *
 * <p>The native-size test is the load-bearing one. Redrawing a sprite at
 * exactly its own size through the slicer must reproduce it pixel for pixel —
 * true of a tiling implementation, and false of any implementation that
 * scales, since scaling a 132px sprite to 132px still round-trips through the
 * interpolator. It also catches an inset that doesn't match the art.
 */
public class NineSliceTest
{
	private static BufferedImage draw(NineSlice slice, OsrsTheme theme, int w, int h)
	{
		BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		slice.paint(g, theme, 0, 0, w, h);
		g.dispose();
		return canvas;
	}

	private static void assertPixelIdentical(String what, BufferedImage a, BufferedImage b)
	{
		assertEquals(what + " width", a.getWidth(), b.getWidth());
		assertEquals(what + " height", a.getHeight(), b.getHeight());
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				int pa = a.getRGB(x, y);
				int pb = b.getRGB(x, y);
				// a fully transparent pixel's colour channels are not part of
				// the image — some Mystic sprites carry stale RGB behind
				// alpha 0, which compositing correctly discards
				if ((pa >>> 24) == 0 && (pb >>> 24) == 0)
				{
					continue;
				}
				if (pa != pb)
				{
					fail(what + ": pixel " + x + "," + y + " differs — the slicer is not tiling 1:1");
				}
			}
		}
	}

	@Test
	public void redrawingASpriteAtItsOwnSizeReproducesItExactly()
	{
		for (String key : new String[]{
			"ui/buttons/enter_wilderness_teleport",
			"ui/buttons/regular_large"})
		{
			int inset = key.endsWith("teleport") ? 9 : 8;
			NineSlice slice = NineSlice.of(key, inset);
			for (OsrsTheme theme : OsrsTheme.values())
			{
				BufferedImage source = V2Sprites.get(theme, key);
				assertPixelIdentical(key + " " + theme,
					source, draw(slice, theme, source.getWidth(), source.getHeight()));
			}
		}
	}

	/** A widened surface must be made of the SOURCE's pixels — every column of
	 *  the result has to exist in the sprite, which scaling breaks. */
	@Test
	public void wideningTilesTheTextureInsteadOfStretchingIt()
	{
		String key = "ui/buttons/enter_wilderness_teleport";
		NineSlice slice = NineSlice.of(key, 9);
		BufferedImage source = V2Sprites.get(OsrsTheme.STONE, key);
		BufferedImage wide = draw(slice, OsrsTheme.STONE, 217, source.getHeight());
		int period = source.getWidth() - 18;
		for (int y = 0; y < source.getHeight(); y++)
		{
			for (int x = 9; x + period < 217 - 9; x++)
			{
				assertEquals("the centre must repeat with the sprite's own period at " + x + "," + y,
					wide.getRGB(x, y), wide.getRGB(x + period, y));
			}
		}
	}

	@Test
	public void theBorderFamilyDrawsAtAnySizeInBothThemes()
	{
		NineSlice frame = NineSlice.frame(
			"ui/borders/equipment_metal_corner_%s", "ui/borders/equipment_edge_%s", 9);
		for (OsrsTheme theme : OsrsTheme.values())
		{
			for (int[] size : new int[][]{{217, 40}, {60, 22}, {22, 22}})
			{
				BufferedImage out = draw(frame, theme, size[0], size[1]);
				// the corners are opaque art, so all four must have landed
				assertTrue("top-left corner missing at " + size[0] + "x" + size[1],
					(out.getRGB(2, 2) >>> 24) > 0);
				assertTrue("bottom-right corner missing",
					(out.getRGB(size[0] - 3, size[1] - 3) >>> 24) > 0);
			}
		}
	}

	@Test
	public void aStateTheArtDoesNotHaveIsRefused()
	{
		NineSlice card = NineSlice.of("ui/buttons/enter_wilderness_teleport", 9);
		card.variant("_hovered"); // this one exists
		try
		{
			card.variant("_selected");
			fail("the system must not offer a state the curated art lacks");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("_selected"));
		}
	}
}
