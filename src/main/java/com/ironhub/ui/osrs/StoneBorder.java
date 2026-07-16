package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * The engraved stone-box border: a 1px dark line outside a 1px light line,
 * with the game's stepped concave notch at each corner. The 7x7 corner stamp
 * is the literal pixel pattern of the Character Summary boxes (sampled from
 * the wiki's 1x screenshot; all four corners are exact mirrors), so at any
 * box size this paints identically to the game's own 9-sliced sprite art.
 */
public class StoneBorder extends AbstractBorder
{
	private static final int CORNER = 7;
	// B = outside, D = dark line, L = light line, F = box fill
	private static final String[] STAMP = {
		"BBBBBDD",
		"BBBBBDL",
		"BBBBBDL",
		"BBBBDDL",
		"BBBDDLL",
		"DDDDLLF",
		"DLLLLFF",
	};

	private final Color outside;

	public StoneBorder()
	{
		this(OsrsSkin.BACKGROUND);
	}

	/** The notch cuts through to whatever the box sits on. */
	public StoneBorder(Color outside)
	{
		this.outside = outside;
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h)
	{
		// edges between the corner stamps: dark outside, light inside
		g.setColor(OsrsSkin.EDGE_DARK);
		g.fillRect(x + CORNER, y, w - 2 * CORNER, 1);
		g.fillRect(x + CORNER, y + h - 1, w - 2 * CORNER, 1);
		g.fillRect(x, y + CORNER, 1, h - 2 * CORNER);
		g.fillRect(x + w - 1, y + CORNER, 1, h - 2 * CORNER);
		g.setColor(OsrsSkin.EDGE_LIGHT);
		g.fillRect(x + CORNER, y + 1, w - 2 * CORNER, 1);
		g.fillRect(x + CORNER, y + h - 2, w - 2 * CORNER, 1);
		g.fillRect(x + 1, y + CORNER, 1, h - 2 * CORNER);
		g.fillRect(x + w - 2, y + CORNER, 1, h - 2 * CORNER);

		for (int row = 0; row < CORNER; row++)
		{
			for (int col = 0; col < CORNER; col++)
			{
				Color color = color(STAMP[row].charAt(col));
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
				return OsrsSkin.EDGE_DARK;
			case 'L':
				return OsrsSkin.EDGE_LIGHT;
			case 'F':
				return OsrsSkin.BOX_FILL;
			default:
				return outside;
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
