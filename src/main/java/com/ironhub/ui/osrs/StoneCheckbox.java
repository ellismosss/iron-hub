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

	private final OsrsTheme theme;
	private boolean checked;

	public StoneCheckbox(OsrsTheme theme, boolean checked)
	{
		this.theme = theme;
		this.checked = checked;
	}

	public void setChecked(boolean checked)
	{
		this.checked = checked;
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
		g2.setColor(theme.edgeDark);
		g2.drawRect(0, 0, SIZE - 1, SIZE - 1);
		g2.setColor(theme.edgeLight);
		g2.drawRect(1, 1, SIZE - 3, SIZE - 3);
		g2.setColor(theme.recess);
		g2.fillRect(2, 2, SIZE - 4, SIZE - 4);

		if (!checked)
		{
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
