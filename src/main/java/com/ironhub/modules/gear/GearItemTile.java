package com.ironhub.modules.gear;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneNavButton;
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
 * One item in the Gear library's grid: the item's sprite over its name in
 * the small font, on the nav stone's chamfered slab. The bevel encodes
 * state — green when owned, orange when tracked as a goal — and the fill
 * lifts when selected (its detail card is open below).
 */
class GearItemTile extends JComponent
{
	static final int WIDTH = 52;
	static final int HEIGHT = 56;
	private static final int ICON_BAND = 32;
	private static final int LINE = 10;

	private final OsrsTheme theme;
	private final String name;
	private final Image icon;
	private final boolean owned;
	private final boolean tracked;
	private final boolean selected;

	GearItemTile(OsrsTheme theme, String name, Image icon, boolean owned, boolean tracked,
		boolean selected, String tooltip, Runnable onClick, java.util.function.Consumer<MouseEvent> onRight)
	{
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
		return new Dimension(WIDTH, HEIGHT);
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
		Color bevel = owned ? OsrsSkin.VALUE.darker()
			: tracked ? OsrsSkin.TITLE : theme.edgeLight;
		StoneNavButton.paintSlab(g2, theme, w, h, selected ? theme.selectFill : theme.boxFill, bevel);

		if (icon != null)
		{
			g2.drawImage(icon, (w - icon.getWidth(null)) / 2,
				Math.max(1, (ICON_BAND - icon.getHeight(null)) / 2 + 1), null);
		}

		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(OsrsSkin.smallFont());
		FontMetrics fm = g2.getFontMetrics();
		Color textColour = owned ? OsrsSkin.VALUE : selected ? OsrsSkin.TITLE : OsrsSkin.MUTED;
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
		// ellipsize the final line if the whole name did not fit
		String joined = String.join(" ", lines);
		if (!joined.equals(text) && !lines.isEmpty())
		{
			String last = lines.get(lines.size() - 1);
			while (last.length() > 1 && fm.stringWidth(last + "…") > width)
			{
				last = last.substring(0, last.length() - 1);
			}
			lines.set(lines.size() - 1, last + "…");
		}
		return lines;
	}
}
