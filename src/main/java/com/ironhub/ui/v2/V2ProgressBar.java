package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Progress, and only progress. The curated set ships exactly two fills — a
 * grey trough and a green bar — so a V2 bar carries no status (§6 rule 4):
 * blocked or short is said in the text beside it, in BLOCKED red. That is
 * deliberate rather than a limitation. A bar that turns amber is a second
 * status channel that has to be kept in step with the first, and it never is.
 *
 * <p>Both sprites are 1px wide and 27 tall — a vertical gradient meant to be
 * repeated along the bar — so the bar's height is the art's own 27px and its
 * width is whatever the row gives it. Cropping the gradient to make it
 * thinner would throw away the shading that makes it read as a bar.
 *
 * <p>An unknown fraction renders as an empty trough, never as zero: "we
 * don't know" and "none" are different statements.
 */
public class V2ProgressBar extends JComponent
{
	private static final String TROUGH = "ui/progress_bar/progress_bar_grey";
	private static final String FILL = "ui/progress_bar/progress_bar_green";

	private final OsrsTheme theme;
	private double fraction;

	public V2ProgressBar(OsrsTheme theme)
	{
		this.theme = theme;
		this.fraction = Double.NaN;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** 0..1. NaN leaves the trough empty — unknown is not zero. */
	public V2ProgressBar fraction(double fraction)
	{
		this.fraction = fraction;
		repaint();
		return this;
	}

	public double fraction()
	{
		return fraction;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		BufferedImage trough = V2Sprites.get(theme, TROUGH);
		BufferedImage fill = V2Sprites.get(theme, FILL);
		int h = Math.min(getHeight(), trough.getHeight());
		for (int x = 0; x < getWidth(); x++)
		{
			g.drawImage(trough, x, 0, x + 1, h, 0, 0, 1, h, null);
		}
		if (Double.isNaN(fraction) || fraction <= 0)
		{
			return;
		}
		int filled = (int) Math.round(Math.min(1, fraction) * getWidth());
		for (int x = 0; x < filled; x++)
		{
			g.drawImage(fill, x, 0, x + 1, h, 0, 0, 1, h, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, V2Sprites.meta(TROUGH).height());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, V2Sprites.meta(TROUGH).height());
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, V2Sprites.meta(TROUGH).height());
	}
}
