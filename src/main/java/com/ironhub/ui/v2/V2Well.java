package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The game's sunken well, at any size. One texture serves every recessed
 * surface in the system — search field, dropdown, table, list frame (Luke,
 * 2026-07-25: "all of these should use the Field and dropdown well
 * texture"), so a framed list and the field above it stop looking like two
 * different ideas.
 *
 * <p>The art ships as three 4x20 pieces, which stretch sideways only. This
 * slices each of them again across its own 2px top and bottom border rows,
 * so the well grows in both directions while its edges stay one pixel and
 * its interior stays flat.
 */
public final class V2Well
{
	private static final String LEFT = "ui/borders/number_field_edge_left";
	private static final String MIDDLE = "ui/borders/number_field_middle";
	private static final String RIGHT = "ui/borders/number_field_edge_right";

	/** The horizontal cap width — the art's own end pieces. */
	public static final int CAP = 4;
	/** The border rows at the top and bottom of every piece. */
	public static final int EDGE = 2;

	/** The art's natural height, and the height a single-line field wants. */
	public int height()
	{
		return V2Sprites.meta(MIDDLE).height();
	}

	public void paint(Graphics2D g, OsrsTheme theme, int x, int y, int w, int h)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		BufferedImage left = V2Sprites.get(theme, LEFT);
		BufferedImage mid = V2Sprites.get(theme, MIDDLE);
		BufferedImage right = V2Sprites.get(theme, RIGHT);
		int sh = mid.getHeight();
		int midH = Math.max(0, h - 2 * EDGE);
		int midW = Math.max(0, w - 2 * CAP);

		// three columns x three bands, each tiled from its own source region
		band(g, left, 0, x, y, CAP, h, sh, midH);
		band(g, right, 0, x + w - CAP, y, CAP, h, sh, midH);
		for (int ox = 0; ox < midW; ox += mid.getWidth())
		{
			int pw = Math.min(mid.getWidth(), midW - ox);
			band(g, mid, 0, x + CAP + ox, y, pw, h, sh, midH);
		}
	}

	/** One column of the well: its top rows, a tiled interior, its bottom rows. */
	private static void band(Graphics2D g, BufferedImage src, int sx, int dx, int dy,
		int w, int h, int sh, int midH)
	{
		g.drawImage(src, dx, dy, dx + w, dy + EDGE, sx, 0, sx + w, EDGE, null);
		int interior = sh - 2 * EDGE;
		for (int oy = 0; oy < midH; oy += interior)
		{
			int ph = Math.min(interior, midH - oy);
			g.drawImage(src, dx, dy + EDGE + oy, dx + w, dy + EDGE + oy + ph,
				sx, EDGE, sx + w, EDGE + ph, null);
		}
		g.drawImage(src, dx, dy + h - EDGE, dx + w, dy + h, sx, sh - EDGE, sx + w, sh, null);
	}
}
