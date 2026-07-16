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

	public StoneMeter(OsrsTheme theme, Color fill, double fraction)
	{
		this.theme = theme;
		this.fill = fill;
		this.fraction = Math.max(0, Math.min(1, fraction));
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
		OsrsSkin.outline(g, theme.edgeDark, 0, 0, w, h);
	}
}
