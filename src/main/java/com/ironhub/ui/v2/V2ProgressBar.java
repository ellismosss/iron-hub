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
 * <p>Three weights, and only the largest is made of sprites (Luke,
 * 2026-07-25). The other two are the V1 atoms ported as they were — they
 * draw themselves, which is what lets them be 18px and 5px tall at all:
 *
 * <ul>
 * <li>{@link Size#FULL} 27px — the game's sprites inside its own thin frame
 * <li>{@link Size#ROW} 18px — StoneProgressBar, with its three inside labels
 * <li>{@link Size#METER} 5px — StoneMeter, with its segment notches
 * </ul>
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
	private String left = "";
	private String centre = "";
	private String right = "";
	private int segments = 1;

	/** V1's measurement: the small font's caps ink sits 6.5 rows above the
	 *  baseline, so +7 from the trough's centre row lands it dead centre. */
	private static final int INK_CENTRE_TO_BASELINE = 7;
	/** V1's side breathing room, so the trough does not hug the text. */
	private static final int SIDE_PAD = 6;

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

	/** The three labels the V1 bar draws inside itself. ROW size only. */
	public V2ProgressBar labels(String left, String centre, String right)
	{
		this.left = left == null ? "" : left;
		this.centre = centre == null ? "" : centre;
		this.right = right == null ? "" : right;
		repaint();
		return this;
	}

	/** The V1 meter's task-count notches. METER size only. */
	public V2ProgressBar segments(int segments)
	{
		this.segments = Math.max(1, segments);
		repaint();
		return this;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (size != Size.FULL)
		{
			paintDrawn((Graphics2D) g);
			return;
		}
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

	/**
	 * The two smaller weights are the V1 atoms, ported line for line (Luke,
	 * 2026-07-25: "import EXACTLY the medium and small progress bars from
	 * Atoms V1, which draw themselves and aren't using sprites"). ROW is
	 * StoneProgressBar — a sunken trough with left/centre/right labels placed
	 * by MEASURED ink, since the small font's caps span baseline-10..-3 and
	 * FontMetrics reads high. METER is StoneMeter, with its notches.
	 */
	private void paintDrawn(Graphics2D g2)
	{
		g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int w = getWidth();
		int h = getHeight();
		double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));

		g2.setColor(size == Size.ROW ? com.ironhub.ui.osrs.OsrsSkin.BAR_TROUGH : theme.recess);
		g2.fillRect(0, 0, w, h);
		g2.setColor(V2Tokens.DONE);
		g2.fillRect(1, 1, (int) Math.round((w - 2) * f), h - 2);
		if (size == Size.METER && segments > 1)
		{
			g2.setColor(theme.edgeLight);
			for (int i = 1; i < segments; i++)
			{
				g2.fillRect(1 + (int) Math.round((w - 2) * (i / (double) segments)), 1, 1, h - 2);
			}
		}
		com.ironhub.ui.osrs.OsrsSkin.outline(g2, theme.edgeDark, 0, 0, w, h);
		if (size != Size.ROW)
		{
			return;
		}
		g2.setFont(V2Tokens.detailFont());
		java.awt.FontMetrics fm = g2.getFontMetrics();
		int baseline = (h - 1) / 2 + INK_CENTRE_TO_BASELINE;
		label(g2, left, SIDE_PAD, baseline);
		label(g2, centre, (w - fm.stringWidth(centre)) / 2, baseline);
		label(g2, right, w - SIDE_PAD - fm.stringWidth(right), baseline);
	}

	private void label(Graphics2D g2, String text, int x, int y)
	{
		if (text.isEmpty())
		{
			return;
		}
		g2.setColor(com.ironhub.ui.osrs.OsrsSkin.TEXT_SHADOW);
		g2.drawString(text, x + 1, y + 1);
		g2.setColor(com.ironhub.ui.osrs.OsrsSkin.BAR_TEXT);
		g2.drawString(text, x, y);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, height());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, height());
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, height());
	}

	/** FULL carries the frame's two extra pixels; the drawn sizes are exactly
	 *  the heights V1 used. */
	private int height()
	{
		return size == Size.FULL ? size.height + 2 : size.height;
	}
}
