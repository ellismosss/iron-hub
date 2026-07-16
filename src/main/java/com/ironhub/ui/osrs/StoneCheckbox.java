package com.ironhub.ui.osrs;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/**
 * The game's checkbox: a dark-outlined box with a light bevel, a sunken
 * interior and a bright tick. Anatomy sampled from the wiki's
 * File:Settings_interface.png (black outline, #8A7757 bevel, #1A1A1A
 * interior, #00FE00 tick) and the pack's options/square_check_box*.png
 * (#141414 / #383838 / #212121 / #65C772) — the same grammar in both, so it
 * paints from theme tokens.
 */
public class StoneCheckbox extends JComponent
{
	private static final int SIZE = 14;
	// the tick's own pixel path, from the sampled art: down-right, then up
	private static final int[][] TICK = {
		{3, 7}, {4, 8}, {5, 9}, {6, 10}, {7, 9}, {8, 8}, {9, 7}, {10, 6}, {11, 5},
	};

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
		for (int[] p : TICK)
		{
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.fillRect(p[0] + 1, p[1] + 1, 2, 2);
		}
		for (int[] p : TICK)
		{
			g2.setColor(theme.checkMark);
			g2.fillRect(p[0], p[1], 2, 2);
		}
	}
}
