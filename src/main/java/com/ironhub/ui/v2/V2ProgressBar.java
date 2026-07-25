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
	/**
	 * ROW and METER are the V1 atoms THEMSELVES, not a copy of them (Luke,
	 * 2026-07-25: "import them EXACTLY"). They were previously reproduced line
	 * for line here, and the copy had already drifted — it filled with the
	 * status green instead of V1's own bar green, which is the whole of why
	 * V1's looked nicer. Holding the real components makes that class of drift
	 * impossible; null for FULL, which is the one weight made of sprites.
	 */
	private final com.ironhub.ui.osrs.StoneProgressBar rowBar;
	private final com.ironhub.ui.osrs.StoneMeter meterBar;

	/** Side breathing room, so the trough does not hug the text. */
	private static final int SIDE_PAD = 6;
	/** Measured ink per font+string: {top relative to the baseline, height}. */
	private static final java.util.Map<String, int[]> INK =
		new java.util.concurrent.ConcurrentHashMap<>();
	private double fraction;
	private String left = "";
	private String centre = "";
	private String right = "";
	private int segments = 1;

	public V2ProgressBar(OsrsTheme theme)
	{
		this(theme, Size.FULL);
	}

	public V2ProgressBar(OsrsTheme theme, Size size)
	{
		this.theme = theme;
		this.size = size;
		this.fraction = Double.NaN;
		this.rowBar = size == Size.ROW
			// theme.recess for the trough: the METER's own, so the two drawn
			// weights sit on the same background (Luke, 2026-07-25)
			? new com.ironhub.ui.osrs.StoneProgressBar(theme, V2Tokens.BAR_FILL, Double.NaN,
				theme.recess)
			: null;
		this.meterBar = size == Size.METER
			? new com.ironhub.ui.osrs.StoneMeter(theme, V2Tokens.BAR_FILL, Double.NaN)
			: null;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/**
	 * Recolour the fill. The default is {@link V2Tokens#BAR_FILL}; Goals asks
	 * for {@link V2Tokens#BAR_BLUE} on routes and tasks, which is V1's own
	 * split between plan progress and possession. §6 rule 4 still holds — the
	 * bar carries no STATUS either way.
	 */
	public V2ProgressBar fill(java.awt.Color fill)
	{
		if (rowBar != null)
		{
			rowBar.setFill(fill);
		}
		if (meterBar != null)
		{
			meterBar.setFill(fill);
		}
		repaint();
		return this;
	}

	/** 0..1. NaN leaves the trough empty — unknown is not zero. */
	public V2ProgressBar fraction(double fraction)
	{
		this.fraction = fraction;
		if (rowBar != null)
		{
			rowBar.setFraction(fraction);
		}
		if (meterBar != null)
		{
			meterBar.setFraction(fraction);
		}
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
		if (rowBar != null)
		{
			rowBar.labels(this.left, this.centre, this.right);
		}
		repaint();
		return this;
	}

	/** The V1 meter's task-count notches. METER size only. */
	public V2ProgressBar segments(int segments)
	{
		this.segments = Math.max(1, segments);
		if (meterBar != null)
		{
			meterBar.segments(this.segments);
		}
		repaint();
		return this;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (size != Size.FULL)
		{
			JComponent v1 = size == Size.ROW ? rowBar : meterBar;
			v1.setBounds(0, 0, getWidth(), getHeight());
			v1.paint(g);
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
		paintLabels(g2, barY, sh);
	}

	/**
	 * The three labels drawn OVER the sprite bar — the Hero's value rides on
	 * its own bar (Luke, 2026-07-25). FULL is sprite art, so unlike ROW there
	 * is nothing in the picture to carry text and it has to go on top.
	 *
	 * <p>Placed by MEASURED ink, as {@code StoneProgressBar} does — FontMetrics
	 * reads high for these pixel fonts and would sit the text low. Unlike V1
	 * the offset is measured from the font itself rather than hardcoded at 7,
	 * because that 7 was the SMALL font's and this draws in the body font
	 * (Luke, 2026-07-25). A constant would have to be re-measured every time
	 * the role changed; the glyph's own pixel bounds never go stale.
	 *
	 * <p>Centred on the BAR, not on the component. The trough is drawn as a
	 * centred window of its sprite, so it is shorter than the component box the
	 * frame occupies — centring on the box put the text below the bar's middle
	 * (Luke, 2026-07-25: "the text is sitting far too low").
	 *
	 * @param barY the trough's first row
	 * @param barH the trough's drawn height, which is NOT {@code getHeight()}
	 */
	private void paintLabels(Graphics2D g2, int barY, int barH)
	{
		if (left.isEmpty() && centre.isEmpty() && right.isEmpty())
		{
			return;
		}
		g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(V2Tokens.bodyFont());
		java.awt.FontMetrics fm = g2.getFontMetrics();
		// measured from the STRING that will be drawn, not from a sample glyph:
		// "1,482 / 1,706" has a slash that rides high and commas that descend,
		// so a digit's ink is not this string's ink
		int[] ink = ink(g2.getFont(), centre.isEmpty() ? left + right : centre);
		int baseline = barY + (barH - ink[1]) / 2 - ink[0];
		int w = getWidth();
		label(g2, left, SIDE_PAD, baseline);
		label(g2, centre, (w - fm.stringWidth(centre)) / 2, baseline);
		label(g2, right, w - SIDE_PAD - fm.stringWidth(right), baseline);
	}

	/**
	 * Where a string's ink actually lands, by RASTERISING it and looking:
	 * {top relative to the baseline, height}.
	 *
	 * <p>Glyph metrics were the first attempt and they measured something the
	 * client did not draw — the bar looked centred in the test renderer and sat
	 * low in the real client (Luke, 2026-07-25, twice). Metrics can describe a
	 * substituted font, or carry padding this font's bitmap glyphs do not use.
	 * Drawing the glyph and scanning for ink cannot disagree with the screen,
	 * because it IS the screen's answer. Once per font, then cached.
	 */
	private static int[] ink(java.awt.Font font, String text)
	{
		return INK.computeIfAbsent(font.getFontName() + "/" + font.getSize() + "/" + text, k ->
		{
			java.awt.Font f = font;
			int box = Math.max(64, f.getSize() * 8);
			int baseline = box / 2;
			BufferedImage probe = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = probe.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(f);
			g.setColor(java.awt.Color.WHITE); // v2-exempt: an offscreen ruler, never shown
			g.drawString(text, 2, baseline);
			g.dispose();
			int top = -1, bottom = -1;
			for (int y = 0; y < box; y++)
			{
				for (int x = 0; x < box; x++)
				{
					if ((probe.getRGB(x, y) >>> 24) != 0)
					{
						top = top < 0 ? y : top;
						bottom = y;
						break;
					}
				}
			}
			return top < 0 ? new int[]{-f.getSize(), f.getSize()}
				: new int[]{top - baseline, bottom - top + 1};
		});
	}

	private void label(Graphics2D g2, String text, int x, int y)
	{
		if (text.isEmpty())
		{
			return;
		}
		g2.setColor(com.ironhub.ui.osrs.OsrsSkin.TEXT_SHADOW);
		g2.drawString(text, x + 1, y + 1);
		g2.setColor(V2Tokens.STRONG);
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
