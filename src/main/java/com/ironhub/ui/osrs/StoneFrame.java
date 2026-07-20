package com.ironhub.ui.osrs;

import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * The game's thin side-panel frame (resizable mode): 1px dark over 1px light,
 * with a stepped chamfer at each corner — much slimmer than the fixed-mode
 * window frame, which matters when the whole surface is 225px wide.
 *
 * <p>Geometry is the literal 8x8 pixel pattern of the Mystic pack's
 * tab/side_border_top_left.png (the pack re-skins the game's own
 * V2StoneBorders SIDE_PANEL_* 9-slice, so the grid is the game's, not the
 * pack's); each theme paints it in its own sampled edge colors.
 */
public class StoneFrame extends AbstractBorder
{
	private static final int CORNER = 8;
	// . = outside the frame, D = dark line, L = light line, F = interior
	private static final String[] STAMP = {
		"....DDDD",
		"...DLLLL",
		"...DLFFF",
		".DDLLFFF",
		"DLLLFFFF",
		"DLFFFFFF",
		"DLFFFFFF",
		"DLFFFFFF",
	};

	private final OsrsTheme theme;

	public StoneFrame(OsrsTheme theme)
	{
		this.theme = theme;
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h)
	{
		g.setColor(theme.edgeDark);
		g.fillRect(x + CORNER, y, w - 2 * CORNER, 1);
		g.fillRect(x + CORNER, y + h - 1, w - 2 * CORNER, 1);
		g.fillRect(x, y + CORNER, 1, h - 2 * CORNER);
		g.fillRect(x + w - 1, y + CORNER, 1, h - 2 * CORNER);
		g.setColor(theme.edgeLight);
		g.fillRect(x + CORNER, y + 1, w - 2 * CORNER, 1);
		g.fillRect(x + CORNER, y + h - 2, w - 2 * CORNER, 1);
		g.fillRect(x + 1, y + CORNER, 1, h - 2 * CORNER);
		g.fillRect(x + w - 2, y + CORNER, 1, h - 2 * CORNER);

		// pre-rendered corner stamps, the StoneBorder recipe (2026-07-20
		// audit — this was still a ~256-fillRect pixel loop per paint).
		// ARGB: 'F' pixels stay transparent so the panel's own fill shows.
		java.awt.image.BufferedImage[] stamps =
			stampCache.computeIfAbsent(outside(c), this::renderStamps);
		g.drawImage(stamps[0], x, y, null);
		g.drawImage(stamps[1], x + w - CORNER, y, null);
		g.drawImage(stamps[2], x, y + h - CORNER, null);
		g.drawImage(stamps[3], x + w - CORNER, y + h - CORNER, null);
	}

	private final java.util.Map<Color, java.awt.image.BufferedImage[]> stampCache =
		new java.util.HashMap<>();

	/** TL, TR, BL, BR corner images for one outside colour. */
	private java.awt.image.BufferedImage[] renderStamps(Color outside)
	{
		java.awt.image.BufferedImage[] out = new java.awt.image.BufferedImage[4];
		for (int i = 0; i < 4; i++)
		{
			out[i] = new java.awt.image.BufferedImage(CORNER, CORNER,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		}
		for (int row = 0; row < CORNER; row++)
		{
			for (int col = 0; col < CORNER; col++)
			{
				char token = STAMP[row].charAt(col);
				if (token == 'F')
				{
					continue; // transparent — the panel's own fill covers it
				}
				int rgb = (token == 'D' ? theme.edgeDark
					: token == 'L' ? theme.edgeLight : outside).getRGB();
				out[0].setRGB(col, row, rgb);
				out[1].setRGB(CORNER - 1 - col, row, rgb);
				out[2].setRGB(col, CORNER - 1 - row, rgb);
				out[3].setRGB(CORNER - 1 - col, CORNER - 1 - row, rgb);
			}
		}
		return out;
	}

	/** The chamfer cuts through to whatever hosts the frame. */
	private Color outside(Component c)
	{
		Component parent = c.getParent();
		return parent != null && parent.isOpaque() ? parent.getBackground() : UiTokens.PANEL_BG;
	}

	@Override
	public Insets getBorderInsets(Component c)
	{
		return new Insets(4, 4, 4, 4);
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets)
	{
		insets.set(4, 4, 4, 4);
		return insets;
	}
}
