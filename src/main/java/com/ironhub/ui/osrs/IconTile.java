package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * One tile in a stonework grid: an icon over a caption in the small font, on
 * the nav stone's chamfered slab. The bevel encodes state — orange when
 * tracked, plain otherwise — a green corner tick marks it owned/built, a
 * top-left badge counts its members, and the fill lifts when selected (its
 * detail card is open below).
 *
 * <p>Shared across the Gear library and the Build modules (House, Boats):
 * a generic presentation atom — an image, a caption, three state booleans,
 * a badge count, a size flag, and two click callbacks — with no module type.
 */
public class IconTile extends JComponent
{
	public static final int WIDTH = 52;
	public static final int HEIGHT = 56;
	/** Set/room tiles can be twice as wide (two across). */
	public static final int WIDTH_LARGE = 106;
	private static final int ICON_BAND = 32;
	private static final int LINE = 10;

	private final OsrsTheme theme;
	private final String name;
	private final Image icon;
	private final boolean owned;
	private final boolean tracked;
	private final boolean selected;
	/** >1 when this tile stands for a group of members (a corner badge). */
	private final int variantCount;
	private final boolean large;

	public IconTile(OsrsTheme theme, String name, Image icon, boolean owned, boolean tracked,
		boolean selected, int variantCount, boolean large, String tooltip, Runnable onClick,
		java.util.function.Consumer<MouseEvent> onRight)
	{
		this.variantCount = variantCount;
		this.large = large;
		this.theme = theme;
		this.name = name;
		this.icon = icon;
		this.owned = owned;
		this.tracked = tracked;
		this.selected = selected;
		setToolTipText(tooltip);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					onRight.accept(e);
				}
				else if (javax.swing.SwingUtilities.isLeftMouseButton(e) && onClick != null)
				{
					onClick.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					onRight.accept(e);
				}
			}
		});
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(large ? WIDTH_LARGE : WIDTH, HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
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
		int w = getWidth();
		int h = getHeight();
		// tracked keeps its orange bevel cue; ownership is a corner tick now
		// (Luke: green name/bevel was too loud), so an owned tile is plain
		Color bevel = tracked ? OsrsSkin.TITLE : theme.edgeLight;
		StoneNavButton.paintSlab(g2, theme, w, h, selected ? theme.selectFill : theme.boxFill, bevel);

		if (icon != null)
		{
			g2.drawImage(icon, (w - icon.getWidth(null)) / 2,
				Math.max(1, (ICON_BAND - icon.getHeight(null)) / 2 + 1), null);
		}

		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(large ? OsrsSkin.font() : OsrsSkin.smallFont());
		FontMetrics fm = g2.getFontMetrics();
		Color textColour = selected ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		List<String> lines = wrap(name, fm, w - 4, 2);
		int y = ICON_BAND + LINE;
		for (String line : lines)
		{
			int x = (w - fm.stringWidth(line)) / 2;
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(line, x + 1, y + 1);
			g2.setColor(textColour);
			g2.drawString(line, x, y);
			y += LINE;
		}

		// a small green tick in the top-right marks an owned/built tile
		if (owned)
		{
			paintCheck(g2, w - 10, 3);
		}
		// a member-count badge in the top-left for a grouped tile — the light
		// colour (Luke), not orange
		if (variantCount > 1)
		{
			g2.setFont(OsrsSkin.smallFont());
			String badge = String.valueOf(variantCount);
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(badge, 4, 11);
			g2.setColor(OsrsSkin.MUTED);
			g2.drawString(badge, 3, 10);
		}
	}

	/** A 6px green check mark, painted (the fonts have no tick glyph). */
	private static void paintCheck(Graphics2D g2, int x, int y)
	{
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(OsrsSkin.TEXT_SHADOW);
		g2.drawLine(x, y + 3, x + 2, y + 5);
		g2.drawLine(x + 2, y + 5, x + 6, y + 1);
		g2.setColor(OsrsSkin.VALUE);
		g2.drawLine(x, y + 2, x + 2, y + 4);
		g2.drawLine(x + 2, y + 4, x + 6, y);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
	}

	/** Greedy word-wrap to at most {@code maxLines}, ellipsizing the last. */
	private static List<String> wrap(String text, FontMetrics fm, int width, int maxLines)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) <= width || line.length() == 0)
			{
				line.setLength(0);
				line.append(candidate);
			}
			else
			{
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
				if (lines.size() == maxLines - 1)
				{
					break;
				}
			}
		}
		if (line.length() > 0 && lines.size() < maxLines)
		{
			lines.add(line.toString());
		}
		// ellipsize the final line if the whole name did not fit across lines
		String joined = String.join(" ", lines);
		if (!joined.equals(text) && !lines.isEmpty())
		{
			lines.set(lines.size() - 1, ellipsize(lines.get(lines.size() - 1), fm, width));
		}
		// and ellipsize any single line still wider than the tile (a long word
		// like "Achievement" that can't be split, so it would center-clip)
		for (int i = 0; i < lines.size(); i++)
		{
			if (fm.stringWidth(lines.get(i)) > width)
			{
				lines.set(i, ellipsize(lines.get(i), fm, width));
			}
		}
		return lines;
	}

	private static String ellipsize(String line, FontMetrics fm, int width)
	{
		if (fm.stringWidth(line) <= width)
		{
			return line;
		}
		while (line.length() > 1 && fm.stringWidth(line + "…") > width)
		{
			line = line.substring(0, line.length() - 1);
		}
		return line + "…";
	}
}
