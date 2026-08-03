package com.ironhub.modules.collectionlog;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

/**
 * Pins the X1 2026-08-03 fix: a SELECTED log tab wears the card art's own lit
 * variant, so an open tab and a merely hovered one can never read the same.
 * Before the fix both painted the identical HIGHLIGHT wash over the same art.
 */
public class ClogTabTileStateTest
{
	@Test
	public void selectedHoveredAndPlainAllReadDifferently()
	{
		BufferedImage plain = paint(tile(false), false);
		BufferedImage hovered = paint(tile(false), true);
		BufferedImage selected = paint(tile(true), false);

		assertFalse("hover must light the tile", identical(plain, hovered));
		assertFalse("selected must not read as plain", identical(plain, selected));
		assertFalse("selected must not read as hover alone", identical(hovered, selected));
	}

	private static ClogTabTile tile(boolean selected)
	{
		return new ClogTabTile(OsrsTheme.STONE, null, 3, 10, selected, null);
	}

	private static BufferedImage paint(ClogTabTile tile, boolean hover)
	{
		tile.setSize(ClogTabTile.WIDTH, ClogTabTile.HEIGHT);
		if (hover)
		{
			tile.getMouseListeners()[0].mouseEntered(new MouseEvent(tile,
				MouseEvent.MOUSE_ENTERED, 0, 0, 1, 1, 0, false));
		}
		BufferedImage image = new BufferedImage(ClogTabTile.WIDTH, ClogTabTile.HEIGHT,
			BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		tile.paint(g);
		g.dispose();
		return image;
	}

	private static boolean identical(BufferedImage a, BufferedImage b)
	{
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}
}
