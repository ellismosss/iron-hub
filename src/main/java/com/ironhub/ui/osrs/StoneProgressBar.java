package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * The tall progress bar: a sunken stone trough with a flat fill and three
 * labels inside it (left / center / right), per Luke's reference — which is
 * RuneLite's own ProgressBar (its layout: 16px tall, white left/center/right
 * labels over the fill, foreground supplied by the caller). Painted here in
 * skin tokens with the game's font and shadow, and one row taller so the
 * RuneScape font's ink fits.
 *
 * <p>The trough and its border are sampled; the fill is semantic and belongs
 * to the caller (the game draws no bar like this — it has no text-in-bar).
 */
public class StoneProgressBar extends JComponent
{
	private static final int HEIGHT = 20;

	private final OsrsTheme theme;
	private final Color fill;
	private double fraction;
	private String left = "";
	private String center = "";
	private String right = "";

	public StoneProgressBar(OsrsTheme theme, Color fill, double fraction)
	{
		this.theme = theme;
		this.fill = fill;
		this.fraction = Math.max(0, Math.min(1, fraction));
	}

	public StoneProgressBar labels(String left, String center, String right)
	{
		this.left = left == null ? "" : left;
		this.center = center == null ? "" : center;
		this.right = right == null ? "" : right;
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
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int w = getWidth(), h = getHeight();

		g2.setColor(theme.recess);
		g2.fillRect(0, 0, w, h);
		g2.setColor(fill);
		g2.fillRect(1, 1, (int) Math.round((w - 2) * fraction), h - 2);
		g2.setColor(theme.edgeDark);
		g2.drawRect(0, 0, w - 1, h - 1);

		g2.setFont(OsrsSkin.font());
		FontMetrics fm = g2.getFontMetrics();
		int baseline = (h + 9) / 2;
		draw(g2, left, 4, baseline);
		draw(g2, center, (w - fm.stringWidth(center)) / 2, baseline);
		draw(g2, right, w - 4 - fm.stringWidth(right), baseline);
	}

	private void draw(Graphics2D g2, String text, int x, int y)
	{
		if (text.isEmpty())
		{
			return;
		}
		g2.setColor(OsrsSkin.TEXT_SHADOW);
		g2.drawString(text, x + 1, y + 1);
		g2.setColor(OsrsSkin.BAR_TEXT);
		g2.drawString(text, x, y);
	}
}
