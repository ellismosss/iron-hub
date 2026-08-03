package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import java.awt.Color;
import java.awt.Font;

/**
 * Text, in the system's five roles and nothing else (DESIGN-SYSTEM-V2 §5).
 *
 * <p>The roles are what V2 owns; the pixel-font mechanics underneath are
 * {@link OsrsLabel}'s, and are deliberately not reimplemented here — its
 * baseline, line pitch, ellipsis and minimum-size behaviour are measured
 * values that took several in-client rounds to get right, and a second
 * implementation of them is precisely the drift this system exists to stop.
 *
 * <p>Every V2 label is LEFT-aligned: the system has one left edge per
 * section (§7), and a single centre-aligned child in a vertical BoxLayout
 * drags every left-aligned sibling off it.
 */
public final class V2Label
{
	private V2Label()
	{
	}

	/** Section titles and hero names. Heading position only — orange in a row
	 *  means "actionable now", which is a different statement. */
	public static OsrsLabel heading(String text)
	{
		return make(text, V2Tokens.HEADING, V2Tokens.headingFont());
	}

	/** The default. Row labels, prose. */
	public static OsrsLabel body(String text)
	{
		return make(text, V2Tokens.TEXT, V2Tokens.bodyFont());
	}

	/** The number or state a row exists to report. */
	public static OsrsLabel value(String text)
	{
		return make(text, V2Tokens.STRONG, V2Tokens.bodyFont());
	}

	/** A secondary line under a row. */
	public static OsrsLabel detail(String text)
	{
		return make(text, V2Tokens.TEXT, V2Tokens.detailFont());
	}

	/** Provenance, disabled, "as of" lines. */
	public static OsrsLabel faint(String text)
	{
		return make(text, V2Tokens.FAINT, V2Tokens.detailFont());
	}

	/** A body line in a status colour — {@code DONE}, {@code ACTION} or
	 *  {@code BLOCKED}, and never any other colour (§6). */
	public static OsrsLabel status(String text, Color status)
	{
		if (status != V2Tokens.DONE && status != V2Tokens.ACTION && status != V2Tokens.BLOCKED)
		{
			throw new IllegalArgumentException(
				"status text takes DONE, ACTION or BLOCKED — colour is never decoration");
		}
		return make(text, status, V2Tokens.bodyFont());
	}

	/** The one centred role: a button's own label, which is centred in its
	 *  art rather than aligned to the section's left edge. */
	public static OsrsLabel centred(String text)
	{
		return new OsrsLabel(text, V2Tokens.TEXT, V2Tokens.bodyFont());
	}

	/** Body text word-wrapped to a pixel width. Never html: the html view
	 *  measures with its parse-time font and clips the pixel font mid-word. */
	public static OsrsLabel wrapped(String text, int width)
	{
		return OsrsLabel.wrapped(text, width, V2Tokens.TEXT, V2Tokens.bodyFont()).leftAligned();
	}

	/** Wrapped and CENTRED — a tile caption, which has a column's width to
	 *  work with and a name that rarely fits one line of it. */
	public static OsrsLabel wrappedCentred(String text, int width)
	{
		return OsrsLabel.wrapped(text, width, V2Tokens.TEXT, V2Tokens.detailFont());
	}

	/** A wrapped detail line — the tooltip and provenance shape. */
	public static OsrsLabel wrappedDetail(String text, int width)
	{
		return OsrsLabel.wrapped(text, width, V2Tokens.TEXT, V2Tokens.detailFont()).leftAligned();
	}

	/** Measured ink per font+string: {top relative to the baseline, height}.
	 *  Package-private so the bound is pinnable. */
	static final java.util.Map<String, int[]> INK =
		new java.util.concurrent.ConcurrentHashMap<>();

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
	static int[] ink(java.awt.Font font, String text)
	{
		if (INK.size() > 512)
		{
			// live progress bars feed value strings through here, one entry
			// each — dump and remeasure rather than grow without bound
			// ponytail: clear-at-cap; LRU if remeasure churn ever shows
			INK.clear();
		}
		return INK.computeIfAbsent(font.getFontName() + "/" + font.getSize() + "/" + text, k ->
		{
			java.awt.Font f = font;
			int box = Math.max(64, f.getSize() * 8);
			int baseline = box / 2;
			java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(box, box, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = probe.createGraphics();
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

	private static OsrsLabel make(String text, Color color, Font font)
	{
		return new OsrsLabel(text, color, font).leftAligned();
	}
}
