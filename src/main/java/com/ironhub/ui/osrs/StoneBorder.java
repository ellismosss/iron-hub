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

		for (int row = 0; row < corner; row++)
		{
			for (int col = 0; col < corner; col++)
			{
				Color color = color(theme.cornerStamp[row].charAt(col), c);
				stamp(g, color, x + col, y + row);
				stamp(g, color, x + w - 1 - col, y + row);
				stamp(g, color, x + col, y + h - 1 - row);
				stamp(g, color, x + w - 1 - col, y + h - 1 - row);
			}
		}
	}

	private void stamp(Graphics g, Color color, int px, int py)
	{
		g.setColor(color);
		g.fillRect(px, py, 1, 1);
	}

	private Color color(char token, Component c)
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
				return c.isOpaque() ? c.getBackground() : theme.boxFill;
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
