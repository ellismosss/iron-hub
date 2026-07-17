package com.ironhub.ui.osrs;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.swing.JComponent;

/**
 * The game's checkbox: a dark-outlined box with a light bevel, a sunken
 * interior and a bright tick. Anatomy sampled from the wiki's
 * File:Settings_interface.png (black outline, #8A7757 bevel, #1A1A1A
 * interior, #00FE00 tick) and the pack's options/square_check_box*.png
 * (#141414 / #383838 / #212121 / #65C772) — the same grammar in both, so it
 * paints from theme tokens.
 *
 * <p>15px, not the sources' 16/17: an ODD box centers exactly inside the odd
 * row height the RuneScape font's ink demands, and the tick is centered from
 * its own measured bounds rather than hand-placed (it read low in-client
 * otherwise — Luke, 2026-07-16).
 */
public class StoneCheckbox extends JComponent
{
	private static final int SIZE = 15;
	/** The tick's pixel path — 2x2 dabs: short arm down-right, long arm up. */
	private static final int[][] TICK = {
		{0, 2}, {1, 3}, {2, 4}, {3, 5}, {4, 4}, {5, 3}, {6, 2}, {7, 1}, {8, 0},
	};
	private static final Rectangle TICK_BOUNDS = tickBounds();
	/** The rest glyph's pixel path — a small 'x' of 1px dabs. */
	private static final int[][] CROSS = {
		{0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 4}, {4, 0}, {3, 1}, {1, 3}, {0, 4},
	};

	private final OsrsTheme theme;
	private final boolean restCross;
	private boolean checked;
	private boolean dimmed;

	public StoneCheckbox(OsrsTheme theme, boolean checked)
	{
		this(theme, checked, false);
	}

	/**
	 * restCross paints a small grey 'x' while UNCHECKED, for boxes whose
	 * purpose is exclusion — readable at rest without a hover (Luke,
	 * 2026-07-17: the alch view's exclude box).
	 */
	public StoneCheckbox(OsrsTheme theme, boolean checked, boolean restCross)
	{
		this.theme = theme;
		this.checked = checked;
		this.restCross = restCross;
	}

	public void setChecked(boolean checked)
	{
		this.checked = checked;
		repaint();
	}

	/** Ghost the whole box — an inapplicable control (still 15px). */
	public void setDimmed(boolean dimmed)
	{
		this.dimmed = dimmed;
		repaint();
	}

	public boolean isChecked()
	{
		return checked;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SIZE, SIZE);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		if (dimmed)
		{
			g2.setComposite(java.awt.AlphaComposite.getInstance(
				java.awt.AlphaComposite.SRC_OVER, 0.4f));
		}
		OsrsSkin.outline(g2, theme.edgeDark, 0, 0, SIZE, SIZE);
		OsrsSkin.outline(g2, theme.edgeLight, 1, 1, SIZE - 2, SIZE - 2);
		g2.setColor(theme.recess);
		g2.fillRect(2, 2, SIZE - 4, SIZE - 4);

		if (!checked)
		{
			if (restCross)
			{
				g2.setColor(OsrsSkin.FAINT);
				for (int[] p : CROSS)
				{
					g2.fillRect(p[0] + 5, p[1] + 5, 1, 1);
				}
			}
			return;
		}
		// center the tick's own ink in the box; hand-placed offsets read low
		int dx = (SIZE - TICK_BOUNDS.width) / 2 - TICK_BOUNDS.x;
		int dy = (SIZE - TICK_BOUNDS.height) / 2 - TICK_BOUNDS.y;
		for (int[] p : TICK)
		{
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.fillRect(p[0] + dx + 1, p[1] + dy + 1, 2, 2);
		}
		for (int[] p : TICK)
		{
			g2.setColor(theme.checkMark);
			g2.fillRect(p[0] + dx, p[1] + dy, 2, 2);
		}
	}

	private static Rectangle tickBounds()
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
		for (int[] p : TICK)
		{
			minX = Math.min(minX, p[0]);
			minY = Math.min(minY, p[1]);
			maxX = Math.max(maxX, p[0] + 1);
			maxY = Math.max(maxY, p[1] + 1);
		}
		return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
