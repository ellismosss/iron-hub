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
	/**
	 * The LEFT cap's top chamfer is 4 rows deep, not 2 — measured 2026-07-25,
	 * its rows read {@code 0,0,38,38 / 0,38,38,38 / 38,38,38,49 / 38,38,49,55}
	 * before settling at {@code 38,49,55,55}. Repeating from row 2 tiled those
	 * two ramp rows down the whole edge, which is the row-ends artifact Luke
	 * saw on the Well's inside-left. The right cap and the middle genuinely
	 * settle at row 2, so they keep {@link #EDGE}.
	 */
	private static final int LEFT_TOP = 4;

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
		int midW = Math.max(0, w - 2 * CAP);

		// three columns x three bands, each tiled from its own source region
		band(g, left, 0, x, y, CAP, h, sh, LEFT_TOP);
		band(g, right, 0, x + w - CAP, y, CAP, h, sh, EDGE);
		for (int ox = 0; ox < midW; ox += mid.getWidth())
		{
			int pw = Math.min(mid.getWidth(), midW - ox);
			band(g, mid, 0, x + CAP + ox, y, pw, h, sh, EDGE);
		}
	}

	/**
	 * The well with the pointer wash baked in, clipped to its OWN pixels.
	 *
	 * <p>Filling the component rectangle washes the transparent air outside the
	 * well's chamfered end caps as well as the well itself, which is the halo
	 * Luke saw on the Value dropdown (2026-07-25). Compositing SrcAtop over a
	 * copy of the well lights only what the well actually drew — the same trick
	 * {@code V2Sprites.highlighted} plays for a single sprite.
	 *
	 * <p>ponytail: allocates a buffer per paint. Hover repaints are rare and
	 * this is at most 217x22; cache per size if a hovered list ever shows up.
	 */
	public void paintLit(Graphics2D g, OsrsTheme theme, int x, int y, int w, int h)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		BufferedImage lit = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D buffer = lit.createGraphics();
		paint(buffer, theme, 0, 0, w, h);
		buffer.setComposite(java.awt.AlphaComposite.SrcAtop);
		buffer.setColor(V2Tokens.HIGHLIGHT);
		buffer.fillRect(0, 0, w, h);
		buffer.dispose();
		g.drawImage(lit, x, y, null);
	}

	/**
	 * One column of the well: its top rows, a tiled interior, its bottom rows.
	 * {@code topEdge} is per-piece — see {@link #LEFT_TOP}. Only rows below it
	 * repeat, so a chamfer can never be tiled down an edge.
	 */
	private static void band(Graphics2D g, BufferedImage src, int sx, int dx, int dy,
		int w, int h, int sh, int topEdge)
	{
		g.drawImage(src, dx, dy, dx + w, dy + topEdge, sx, 0, sx + w, topEdge, null);
		int interior = sh - topEdge - EDGE;
		int midH = Math.max(0, h - topEdge - EDGE);
		for (int oy = 0; oy < midH; oy += interior)
		{
			int ph = Math.min(interior, midH - oy);
			g.drawImage(src, dx, dy + topEdge + oy, dx + w, dy + topEdge + oy + ph,
				sx, topEdge, sx + w, topEdge + ph, null);
		}
		g.drawImage(src, dx, dy + h - EDGE, dx + w, dy + h, sx, sh - EDGE, sx + w, sh, null);
	}
}
