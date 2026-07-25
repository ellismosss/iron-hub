package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * A three-piece horizontal slice: left cap, tiled middle, right cap. The
 * game's search field and dropdown are built this way — a fixed-height well
 * that stretches sideways only — so a nine-piece slice would be inventing
 * corners the art doesn't have.
 *
 * <p>Same rule as {@link NineSlice}: the middle TILES, it never scales.
 */
public final class V2Strip
{
	private final String left;
	private final String middle;
	private final String right;

	public V2Strip(String left, String middle, String right)
	{
		for (String key : new String[]{left, middle, right})
		{
			if (!V2Sprites.has(key))
			{
				throw new IllegalArgumentException("no V2 sprite '" + key + "'");
			}
		}
		this.left = left;
		this.middle = middle;
		this.right = right;
	}

	/** The art's own height — a strip never stretches vertically. */
	public int height()
	{
		return V2Sprites.meta(middle).height();
	}

	public int capWidth()
	{
		return V2Sprites.meta(left).width();
	}

	public void paint(Graphics2D g, OsrsTheme theme, int x, int y, int w)
	{
		BufferedImage l = V2Sprites.get(theme, left);
		BufferedImage m = V2Sprites.get(theme, middle);
		BufferedImage r = V2Sprites.get(theme, right);
		int h = m.getHeight();
		for (int ox = l.getWidth(); ox < w - r.getWidth(); ox += m.getWidth())
		{
			int pw = Math.min(m.getWidth(), w - r.getWidth() - ox);
			g.drawImage(m, x + ox, y, x + ox + pw, y + h, 0, 0, pw, h, null);
		}
		g.drawImage(l, x, y, null);
		g.drawImage(r, x + w - r.getWidth(), y, null);
	}
}
