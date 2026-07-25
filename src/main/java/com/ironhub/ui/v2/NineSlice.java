package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;

/**
 * Draws a curated sprite at any size by TILING it — never scaling it.
 *
 * <p>This is rule one of the V2 system, and it is not a preference. Every
 * stone surface in the set is a noise texture: no two adjacent pixel columns
 * of {@code regular_large} are identical, so scaling the middle duplicates
 * specific columns and turns the 1px grain into visible 2px vertical bands
 * (measured 2026-07-24). Tiling reproduces the texture's own period, so a
 * 217px-wide button is made of the same grain as the 132px sprite it came
 * from. {@code NineSliceTest} pins that a rebuild at the sprite's NATIVE size
 * is pixel-identical to the sprite — the strongest statement of correctness
 * available, and it fails on any scaling implementation.
 *
 * <p>Two source shapes, because the curated set has both: one sprite sliced
 * into nine ({@link #of}, e.g. the filled card), or eight separate border
 * pieces with a transparent middle ({@link #frame}, e.g. the metal frame and
 * the side-panel border).
 */
public final class NineSlice
{
	private static final String[] CORNERS = {"top_left", "top_right", "bottom_left", "bottom_right"};
	private static final String[] EDGES = {"top", "bottom", "left", "right"};

	/** Single-sprite form: null, and {@link #single} carries the key. */
	private final String single;
	private final String[] corners;
	private final String[] edges;
	/** Piece form only: the sprite tiled through the middle, or null to leave
	 *  it transparent (a border-only frame such as the Well). */
	private final String middle;
	private final int inset;

	private NineSlice(String single, String[] corners, String[] edges, String middle, int inset)
	{
		this.single = single;
		this.corners = corners;
		this.edges = edges;
		this.middle = middle;
		this.inset = inset;
	}

	/**
	 * One sprite cut into nine at a uniform inset — the filled card, the text
	 * button. The inset must clear the sprite's own border art, or the border
	 * ends up tiled through the middle.
	 */
	public static NineSlice of(String key, int inset)
	{
		if (!V2Sprites.has(key))
		{
			throw new IllegalArgumentException("no V2 sprite '" + key + "'");
		}
		return new NineSlice(key, null, null, null, inset);
	}

	/**
	 * Eight border pieces, middle left alone — the metal frame, the panel
	 * border. Both families name their pieces the same way, so the two
	 * patterns take a position token: {@code "ui/borders/equipment_metal_corner_%s"}
	 * and {@code "ui/borders/equipment_edge_%s"}.
	 */
	public static NineSlice frame(String cornerPattern, String edgePattern, int inset)
	{
		return pieces(cornerPattern, edgePattern, null, inset);
	}

	/**
	 * The same eight pieces plus a sprite tiled through the middle — the
	 * game's own notched slab, which ships its corners, edges and middle as
	 * separate files. A null middle leaves the centre transparent, which is
	 * the border-only {@link #frame}.
	 */
	public static NineSlice pieces(String cornerPattern, String edgePattern, String middle,
		int inset)
	{
		String[] c = new String[4];
		String[] e = new String[4];
		for (int i = 0; i < 4; i++)
		{
			c[i] = String.format(cornerPattern, CORNERS[i]);
			e[i] = String.format(edgePattern, EDGES[i]);
			if (!V2Sprites.has(c[i]) || !V2Sprites.has(e[i]))
			{
				throw new IllegalArgumentException("no V2 sprite '" + c[i] + "' / '" + e[i] + "'");
			}
		}
		if (middle != null && !V2Sprites.has(middle))
		{
			throw new IllegalArgumentException("no V2 sprite '" + middle + "'");
		}
		return new NineSlice(null, c, e, middle, inset);
	}

	/**
	 * The same slice in another state — {@code "_hovered"}, {@code "_selected"}.
	 * Throws when that state isn't in the curated set, which is deliberate:
	 * the system only offers states the art actually has.
	 */
	public NineSlice variant(String suffix)
	{
		if (single != null)
		{
			return of(single + suffix, inset);
		}
		String[] c = new String[4];
		String[] e = new String[4];
		for (int i = 0; i < 4; i++)
		{
			c[i] = corners[i] + suffix;
			e[i] = edges[i] + suffix;
			if (!V2Sprites.has(c[i]) || !V2Sprites.has(e[i]))
			{
				throw new IllegalArgumentException("no '" + suffix + "' state for " + corners[i]);
			}
		}
		// the middle often has no per-state art (the game re-tints the border
		// and leaves the fill), so keep the plain one when the state is absent
		String m = middle == null ? null
			: V2Sprites.has(middle + suffix) ? middle + suffix : middle;
		return new NineSlice(null, c, e, m, inset);
	}

	/** The corner size — also the minimum padding content needs to clear the art. */
	public int inset()
	{
		return inset;
	}

	public void paint(Graphics2D g, OsrsTheme theme, int x, int y, int w, int h)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		Shape clip = g.getClip();
		g.clipRect(x, y, w, h);
		int c = inset;
		int midW = w - 2 * c;
		int midH = h - 2 * c;
		if (single != null)
		{
			BufferedImage s = V2Sprites.get(theme, single);
			int sw = s.getWidth();
			int sh = s.getHeight();
			tile(g, s, c, c, sw - 2 * c, sh - 2 * c, x + c, y + c, midW, midH);
			tile(g, s, c, 0, sw - 2 * c, c, x + c, y, midW, c);
			tile(g, s, c, sh - c, sw - 2 * c, c, x + c, y + h - c, midW, c);
			tile(g, s, 0, c, c, sh - 2 * c, x, y + c, c, midH);
			tile(g, s, sw - c, c, c, sh - 2 * c, x + w - c, y + c, c, midH);
			tile(g, s, 0, 0, c, c, x, y, c, c);
			tile(g, s, sw - c, 0, c, c, x + w - c, y, c, c);
			tile(g, s, 0, sh - c, c, c, x, y + h - c, c, c);
			tile(g, s, sw - c, sh - c, c, c, x + w - c, y + h - c, c, c);
		}
		else
		{
			if (middle != null)
			{
				BufferedImage fill = V2Sprites.get(theme, middle);
				tile(g, fill, 0, 0, fill.getWidth(), fill.getHeight(),
					x + c, y + c, midW, midH);
			}
			BufferedImage top = V2Sprites.get(theme, edges[0]);
			BufferedImage bottom = V2Sprites.get(theme, edges[1]);
			BufferedImage left = V2Sprites.get(theme, edges[2]);
			BufferedImage right = V2Sprites.get(theme, edges[3]);
			// each piece is placed and sized by its OWN art, not by the slice
			// inset: a pack that trims the transparent margin ships a 32x20
			// edge where vanilla has 32x32, and using the inset for both put
			// the bottom and right bars 12px out of line (Luke, 2026-07-25)
			tile(g, top, 0, 0, top.getWidth(), top.getHeight(),
				x + c, y, midW, top.getHeight());
			tile(g, bottom, 0, 0, bottom.getWidth(), bottom.getHeight(),
				x + c, y + h - bottom.getHeight(), midW, bottom.getHeight());
			tile(g, left, 0, 0, left.getWidth(), left.getHeight(),
				x, y + c, left.getWidth(), midH);
			tile(g, right, 0, 0, right.getWidth(), right.getHeight(),
				x + w - right.getWidth(), y + c, right.getWidth(), midH);
			BufferedImage tl = V2Sprites.get(theme, corners[0]);
			BufferedImage tr = V2Sprites.get(theme, corners[1]);
			BufferedImage bl = V2Sprites.get(theme, corners[2]);
			BufferedImage br = V2Sprites.get(theme, corners[3]);
			g.drawImage(tl, x, y, null);
			g.drawImage(tr, x + w - tr.getWidth(), y, null);
			g.drawImage(bl, x, y + h - bl.getHeight(), null);
			g.drawImage(br, x + w - br.getWidth(), y + h - br.getHeight(), null);
		}
		g.setClip(clip);
	}

	/** Repeat one region of the source across a destination rect, clipping the
	 *  last row/column. Source and destination pixels are 1:1 — always. */
	private static void tile(Graphics2D g, BufferedImage src, int sx, int sy, int sw, int sh,
		int dx, int dy, int dw, int dh)
	{
		if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0)
		{
			return;
		}
		for (int oy = 0; oy < dh; oy += sh)
		{
			int ph = Math.min(sh, dh - oy);
			for (int ox = 0; ox < dw; ox += sw)
			{
				int pw = Math.min(sw, dw - ox);
				g.drawImage(src,
					dx + ox, dy + oy, dx + ox + pw, dy + oy + ph,
					sx, sy, sx + pw, sy + ph, null);
			}
		}
	}
}
