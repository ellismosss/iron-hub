package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The dropdown's hover wash stays inside the well.
 *
 * <p>It was a fillRect over the whole component, so it lit the transparent air
 * outside the well's chamfered end caps and left a halo on the panel behind
 * (Luke, 2026-07-25). The corners are the only place that can show it.
 */
public class V2DropdownHoverTest
{
	@Test
	public void theWashDoesNotSpillPastTheWellsCorners()
	{
		V2Well well = new V2Well();
		int w = 120;
		int h = V2Tokens.CONTROL_HEIGHT;

		BufferedImage plain = draw(well, w, h, false);
		BufferedImage lit = draw(well, w, h, true);

		// every pixel the plain well leaves transparent must stay transparent
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((plain.getRGB(x, y) >>> 24) == 0)
				{
					assertEquals("wash spilled at " + x + "," + y,
						0, lit.getRGB(x, y) >>> 24);
				}
			}
		}
	}

	private static BufferedImage draw(V2Well well, int w, int h, boolean lit)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		if (lit)
		{
			well.paintLit(g, OsrsTheme.STONE, 0, 0, w, h);
		}
		else
		{
			well.paint(g, OsrsTheme.STONE, 0, 0, w, h);
		}
		g.dispose();
		return img;
	}
}
