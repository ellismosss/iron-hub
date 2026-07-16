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
 * FontMetrics height (16) is 4px looser than the interface actually draws,
 * and the RuneScape font's real ink is only 12px above / 1px below baseline.
 */
public class OsrsLabel extends JComponent
{
	private static final int LINE_PITCH = 12;
	private static final int DESCENT = 2; // 1px descender ink + 1px shadow

	private final String[] lines;
	private final Color color;

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

	public OsrsLabel(String text, Color color, Font font)
	{
		this.lines = text.split("\n");
		this.color = color;
		setFont(font);
		setOpaque(false);
		setAlignmentX(CENTER_ALIGNMENT);
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

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(getFont());
		FontMetrics fm = g2.getFontMetrics();
		for (int i = 0; i < lines.length; i++)
		{
			int x = (getWidth() - fm.stringWidth(lines[i])) / 2;
			int y = LINE_PITCH * (i + 1);
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(lines[i], x + 1, y + 1);
			g2.setColor(color);
			g2.drawString(lines[i], x, y);
		}
	}
}
