package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * Game-style text: the RuneScape font drawn crisp (no antialiasing) with the
 * +1,+1 black drop shadow every in-game string carries. Multi-line ('\n')
 * with each line centered, at the game's own tight 12px line pitch — Swing's
 * FontMetrics height (16) is 4px looser than the interface actually draws.
 *
 * The vertical numbers are measured, not FontMetrics: RuneLite's RuneScape
 * TTF draws its ink floating high in the em box — caps/digits span
 * baseline-12 to baseline-2, descenders reach baseline+1. The game centers
 * the visible INK in its boxes (wiki 1x: box, icon and text centers all
 * equal), so the baseline sits at line bottom minus 2, which lands the
 * common caps/digits mass dead-center; positioning by nominal baseline read
 * 2px high (Luke, in-client 2026-07-16).
 */
public class OsrsLabel extends JComponent
{
	private static final int LINE_PITCH = 12;
	/**
	 * First-line baseline offset from the text block top, and the block's
	 * rows beyond the pitch rows. Derived from the measured ink (caps span
	 * baseline-12..baseline-2, descenders reach baseline+1): a 12n+5 block
	 * with the first baseline at 15 centers the 11-row caps INK exactly —
	 * the +1,+1 shadow is black on near-black and plays no part in the
	 * optical center (Luke read the shadow-balanced version as still a
	 * touch high) — and keeps descender ink inside the component.
	 */
	private static final int BASELINE = 15;
	private static final int DESCENT = 5;

	private final String[] lines;
	private Color color;
	private boolean leftAligned;

	public static OsrsLabel title(String text)
	{
		return new OsrsLabel(text, OsrsSkin.TITLE, OsrsSkin.boldFont());
	}

	public static OsrsLabel label(String text)
	{
		return new OsrsLabel(text, OsrsSkin.LABEL, OsrsSkin.font());
	}

	public static OsrsLabel value(String text)
	{
		return new OsrsLabel(text, OsrsSkin.VALUE, OsrsSkin.font());
	}

	/** Multi-line label greedily word-wrapped at a pixel width — the skin's
	 *  wrapping text (html measurement and the pixel font disagree). */
	public static OsrsLabel wrapped(String text, int width, Color color, Font font)
	{
		FontMetrics fm = new javax.swing.JLabel().getFontMetrics(font);
		StringBuilder out = new StringBuilder();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > width && line.length() > 0)
			{
				out.append(line).append('\n');
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		return new OsrsLabel(out.append(line).toString(), color, font);
	}

	public OsrsLabel(String text, Color color, Font font)
	{
		this.lines = text.split("\n");
		this.color = color;
		setFont(font);
		setOpaque(false);
		setAlignmentX(CENTER_ALIGNMENT);
	}

	/** The label's text, newline-joined — render tests read it. */
	public String text()
	{
		return String.join("\n", lines);
	}

	/** Recolour in place — status changes must not rebuild the row. */
	public void setColor(Color color)
	{
		this.color = color;
		repaint();
	}

	/** Left-align the ink — for a label given a whole row's width. Also
	 *  claims LEFT_ALIGNMENT: one centre-aligned child in a vertical
	 *  BoxLayout drifts every left-aligned sibling off the edge. */
	public OsrsLabel leftAligned()
	{
		this.leftAligned = true;
		setAlignmentX(LEFT_ALIGNMENT);
		return this;
	}

	@Override
	public Dimension getPreferredSize()
	{
		FontMetrics fm = getFontMetrics(getFont());
		int width = 0;
		for (String line : lines)
		{
			width = Math.max(width, fm.stringWidth(line));
		}
		return new Dimension(width + 1, LINE_PITCH * lines.length + DESCENT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	/**
	 * A UI-less JComponent's default minimum is its CURRENT size — 0x0
	 * before first layout — and BoxLayout derives a row's cross-axis
	 * alignment from child minimums: an all-zero-minimum row degenerates to
	 * alignment 0 and cuts every child to half height, top-anchored. A
	 * pixel-art label must never shrink below its natural size by default;
	 * an EXPLICIT minimum (squeezable) still wins.
	 */
	@Override
	public Dimension getMinimumSize()
	{
		return isMinimumSizeSet() ? super.getMinimumSize() : getPreferredSize();
	}

	/** Let a row squeeze this label below its text width (it ellipsizes at
	 *  paint); the minimum HEIGHT stays honest for BoxLayout alignment. */
	public OsrsLabel squeezable()
	{
		setMinimumSize(new Dimension(0, getPreferredSize().height));
		return this;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(getFont());
		FontMetrics fm = g2.getFontMetrics();
		// center the text block in whatever height layout assigned, so an
		// allocation quirk shifts the text instead of clipping it mid-glyph
		int top = (getHeight() - (LINE_PITCH * lines.length + DESCENT)) / 2;
		for (int i = 0; i < lines.length; i++)
		{
			// a line wider than the assigned width ellipsizes at paint time —
			// hard-clipped glyphs read as broken, and only paint knows the
			// real width (a hub-hosted tab is narrower than the 225px panel)
			String line = lines[i];
			if (fm.stringWidth(line) + 1 > getWidth())
			{
				line = ellipsize(line, fm, getWidth() - 1);
			}
			int x = leftAligned ? 0 : Math.max(0, (getWidth() - fm.stringWidth(line)) / 2);
			int y = top + BASELINE + LINE_PITCH * i;
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(line, x + 1, y + 1);
			g2.setColor(color);
			g2.drawString(line, x, y);
		}
	}

	private static String ellipsize(String text, FontMetrics fm, int width)
	{
		String out = text;
		while (out.length() > 1 && fm.stringWidth(out + "…") > width)
		{
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}
}
