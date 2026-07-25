package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Progress, and only progress. The curated set ships one fill and it is
 * green, so a V2 bar carries no status: blocked or short is said in the text
 * beside it, in BLOCKED red.
 *
 * <p>The bar sits inside the game's own thin frame (Luke, 2026-07-25 — the
 * unframed bar read as a flat block against the in-game one), and comes in
 * three heights so a hero, a row and a meter can each use the right weight:
 *
 * <ul>
 * <li>{@link Size#FULL} 27px — the art's own height, the in-game bar
 * <li>{@link Size#ROW} 18px — the V1 StoneProgressBar weight, for list rows
 * <li>{@link Size#METER} 5px — the V1 StoneMeter, a hairline under a title
 * </ul>
 *
 * <p>The two smaller sizes take a WINDOW of the 27px gradient rather than
 * scaling it (rule one), centred so the fill keeps the shading that makes it
 * read as a bar.
 *
 * <p>An unknown fraction renders as an empty trough, never as zero.
 */
public class V2ProgressBar extends JComponent
{
	private static final String TROUGH = "ui/progress_bar/progress_bar_grey";
	private static final String FILL = "ui/progress_bar/progress_bar_green_new";

	/** The three weights. */
	public enum Size
	{
		FULL(27), ROW(18), METER(5);

		final int height;

		Size(int height)
		{
			this.height = height;
		}
	}

	private final OsrsTheme theme;
	private final Size size;
	private final NineSlice frame = V2Tokens.barFrame();
	private double fraction;

	public V2ProgressBar(OsrsTheme theme)
	{
		this(theme, Size.FULL);
	}

	public V2ProgressBar(OsrsTheme theme, Size size)
	{
		this.theme = theme;
		this.size = size;
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

	public Size barSize()
	{
		return size;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		BufferedImage trough = V2Sprites.get(theme, TROUGH);
		BufferedImage fill = V2Sprites.get(theme, FILL);
		// the frame's ink hugs the outside, so the bar itself starts one pixel in
		int inset = 1;
		int barX = inset;
		int barY = inset;
		int barW = Math.max(0, getWidth() - 2 * inset);
		int barH = Math.max(0, getHeight() - 2 * inset);
		// a window of the gradient, centred — never a squashed copy of it
		int sy = Math.max(0, (trough.getHeight() - barH) / 2);
		int sh = Math.min(barH, trough.getHeight());
		for (int x = 0; x < barW; x++)
		{
			g2.drawImage(trough, barX + x, barY, barX + x + 1, barY + sh, 0, sy, 1, sy + sh, null);
		}
		if (!Double.isNaN(fraction) && fraction > 0)
		{
			int filled = (int) Math.round(Math.min(1, fraction) * barW);
			for (int x = 0; x < filled; x++)
			{
				g2.drawImage(fill, barX + x, barY, barX + x + 1, barY + sh, 0, sy, 1, sy + sh, null);
			}
		}
		frame.paint(g2, theme, 0, 0, getWidth(), getHeight());
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, size.height + 2);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, size.height + 2);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, size.height + 2);
	}
}
