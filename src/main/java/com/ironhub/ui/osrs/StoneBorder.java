package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * The engraved stone-box border: a 1px dark line outside a 1px light line,
 * with the theme's stepped concave notch at each corner. Corner stamps are
 * the literal pixel patterns of the source art (wiki 1x screenshot for
 * STONE, the Mystic pack's 9-slice sprites for MYSTIC; all four corners are
 * exact mirrors), so at any box size this paints identically to the game's
 * own 9-sliced sprite art.
 */
public class StoneBorder extends AbstractBorder
{
	/** Measured from the game's boxes: ~4px vertical, ~6px horizontal pad. */
	private static final Insets PAD = new Insets(4, 6, 4, 6);

	private final OsrsTheme theme;
	private final Color outside;
	private final Insets pad;
	private final int corner;
	/** Rendered corner stamps per component fill (rest/hover/press) — EDT only. */
	private final java.util.Map<Color, java.awt.image.BufferedImage[]> stampCache =
		new java.util.HashMap<>();

	/** A box sitting directly on the theme's backing. */
	public StoneBorder(OsrsTheme theme)
	{
		this(theme, theme.background, PAD);
	}

	/** A box nested on some other surface — the notch cuts through to it. */
	public StoneBorder(OsrsTheme theme, Color outside)
	{
		this(theme, outside, PAD);
	}

	/**
	 * Custom content padding — for surfaces whose content must reach the
	 * engraved edge (a checklist's row highlights). Keep the vertical pad at
	 * or above the corner stamp's height or content collides with the notch.
	 */
	public StoneBorder(OsrsTheme theme, Color outside, Insets pad)
	{
		this.theme = theme;
		this.outside = outside;
		this.pad = pad;
		this.corner = theme.cornerStamp.length;
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h)
	{
		// edges between the corner stamps: dark outside, light inside
		g.setColor(theme.edgeDark);
		g.fillRect(x + corner, y, w - 2 * corner, 1);
		g.fillRect(x + corner, y + h - 1, w - 2 * corner, 1);
		g.fillRect(x, y + corner, 1, h - 2 * corner);
		g.fillRect(x + w - 1, y + corner, 1, h - 2 * corner);
		g.setColor(theme.edgeLight);
		g.fillRect(x + corner, y + 1, w - 2 * corner, 1);
		g.fillRect(x + corner, y + h - 2, w - 2 * corner, 1);
		g.fillRect(x + 1, y + corner, 1, h - 2 * corner);
		g.fillRect(x + w - 2, y + corner, 1, h - 2 * corner);

		// pre-rendered corner stamps: the pixel loop was ~200 fillRect(1,1)
		// calls per paint per box, and every skinned surface repaints many
		// boxes (2026-07-17 freeze audit). Keyed by the component fill so
		// hover/press states still reach the corner pixels.
		Color fill = c.isOpaque() ? c.getBackground() : theme.boxFill;
		java.awt.image.BufferedImage[] stamps =
			stampCache.computeIfAbsent(fill, this::renderStamps);
		g.drawImage(stamps[0], x, y, null);
		g.drawImage(stamps[1], x + w - corner, y, null);
		g.drawImage(stamps[2], x, y + h - corner, null);
		g.drawImage(stamps[3], x + w - corner, y + h - corner, null);
	}

	/** TL, TR, BL, BR corner images for one component fill colour. */
	private java.awt.image.BufferedImage[] renderStamps(Color fill)
	{
		java.awt.image.BufferedImage[] out = new java.awt.image.BufferedImage[4];
		for (int i = 0; i < 4; i++)
		{
			out[i] = new java.awt.image.BufferedImage(corner, corner,
				java.awt.image.BufferedImage.TYPE_INT_RGB);
		}
		for (int row = 0; row < corner; row++)
		{
			for (int col = 0; col < corner; col++)
			{
				int rgb = color(theme.cornerStamp[row].charAt(col), fill).getRGB();
				out[0].setRGB(col, row, rgb);
				out[1].setRGB(corner - 1 - col, row, rgb);
				out[2].setRGB(col, corner - 1 - row, rgb);
				out[3].setRGB(corner - 1 - col, corner - 1 - row, rgb);
			}
		}
		return out;
	}

	private Color color(char token, Color fill)
	{
		switch (token)
		{
			case 'D':
				return theme.edgeDark;
			case 'L':
				return theme.edgeLight;
			case 'F':
				// the component's own fill, so hover/press states reach the
				// corner pixels instead of stranding the resting color there
				return fill;
			default:
				return outside;
		}
	}

	@Override
	public Insets getBorderInsets(Component c)
	{
		return new Insets(pad.top, pad.left, pad.bottom, pad.right);
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets)
	{
		insets.set(pad.top, pad.left, pad.bottom, pad.right);
		return insets;
	}
}
