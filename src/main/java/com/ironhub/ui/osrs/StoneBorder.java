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
	private final OsrsTheme theme;
	private final int corner;

	public StoneBorder()
	{
		this(OsrsSkin.STONE);
	}

	public StoneBorder(OsrsTheme theme)
	{
		this.theme = theme;
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
				Color color = color(theme.cornerStamp[row].charAt(col));
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

	private Color color(char token)
	{
		switch (token)
		{
			case 'D':
				return theme.edgeDark;
			case 'L':
				return theme.edgeLight;
			case 'F':
				return theme.boxFill;
			default:
				return theme.background; // the notch cuts through to the backing
		}
	}

	@Override
	public Insets getBorderInsets(Component c)
	{
		// measured from the game's boxes: ~4px vertical, ~6px horizontal pad
		return new Insets(4, 6, 4, 6);
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets)
	{
		insets.set(4, 6, 4, 6);
		return insets;
	}
}
