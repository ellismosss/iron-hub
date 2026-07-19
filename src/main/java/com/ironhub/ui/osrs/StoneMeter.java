package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;

/** The thin progress bar: a few pixels of trough and fill, no text. */
public class StoneMeter extends JComponent
{
	private static final int HEIGHT = 5;

	private final OsrsTheme theme;
	private final Color fill;
	private double fraction;
	private int segments = 1;

	public StoneMeter(OsrsTheme theme, Color fill, double fraction)
	{
		this.theme = theme;
		this.fill = fill;
		this.fraction = Math.max(0, Math.min(1, fraction));
	}

	/** Divide the bar into {@code n} segments with subtle notches (n-1 marks).
	 *  Used to show the number of tasks in a Goal (Luke). */
	public StoneMeter segments(int n)
	{
		this.segments = Math.max(1, n);
		return this;
	}

	public void setFraction(double fraction)
	{
		this.fraction = Math.max(0, Math.min(1, fraction));
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(0, HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth(), h = getHeight();
		g.setColor(theme.recess);
		g.fillRect(0, 0, w, h);
		g.setColor(fill);
		g.fillRect(1, 1, (int) Math.round((w - 2) * fraction), h - 2);
		// subtle task-count notches dividing the bar into `segments` (n-1 marks);
		// the lighter bevel colour so they read on the dark empty trough too
		if (segments > 1)
		{
			g.setColor(theme.edgeLight);
			for (int i = 1; i < segments; i++)
			{
				int x = 1 + (int) Math.round((w - 2) * (i / (double) segments));
				g.fillRect(x, 1, 1, h - 2);
			}
		}
		OsrsSkin.outline(g, theme.edgeDark, 0, 0, w, h);
	}
}
