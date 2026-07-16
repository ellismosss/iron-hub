package com.ironhub.ui.osrs;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * The game's tab strip: tabs sit in a recess, and the selected one lifts to
 * the content's own fill and drops its bottom edge so it reads as merged with
 * the panel below. Both halves of that idiom are sampled — vanilla's strip in
 * File:Character_Summary.png (recess #28251E, selected = the backing) and the
 * pack's tab sprites (base flat, selected lighter with no bottom line).
 *
 * <p>Kept deliberately small: per Luke most surfaces will not want tabs, so
 * this exists to be judged, not assumed.
 */
public class StoneTabStrip extends JComponent
{
	private static final int HEIGHT = 22;

	private final OsrsTheme theme;
	private final List<String> tabs;
	private final Consumer<Integer> onSelect;
	private int selected;

	public StoneTabStrip(OsrsTheme theme, List<String> tabs, int selected, Consumer<Integer> onSelect)
	{
		this.theme = theme;
		this.tabs = tabs;
		this.selected = selected;
		this.onSelect = onSelect;
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				int index = tabAt(e.getX());
				if (index >= 0)
				{
					setSelectedTab(index);
					if (onSelect != null)
					{
						onSelect.accept(index);
					}
				}
			}
		});
	}

	public void setSelectedTab(int index)
	{
		this.selected = index;
		repaint();
	}

	/** Test seam. */
	public int getSelectedTab()
	{
		return selected;
	}

	int tabAt(int x)
	{
		int width = tabWidth();
		int index = x / Math.max(1, width);
		return index >= 0 && index < tabs.size() ? index : -1;
	}

	private int tabWidth()
	{
		return Math.max(1, getWidth() / Math.max(1, tabs.size()));
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
		g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int w = getWidth(), h = getHeight();
		int width = tabWidth();

		g2.setColor(theme.background);
		g2.fillRect(0, 0, w, h);

		for (int i = 0; i < tabs.size(); i++)
		{
			int x = i * width;
			int tw = i == tabs.size() - 1 ? w - x : width;
			boolean active = i == selected;

			g2.setColor(active ? theme.boxFill : theme.recess);
			g2.fillRect(x, 0, tw, h);
			// top + sides; the selected tab keeps no bottom line, which is
			// what merges it into the content below
			g2.setColor(theme.edgeDark);
			g2.fillRect(x, 0, tw, 1);
			g2.fillRect(x, 0, 1, h);
			g2.fillRect(x + tw - 1, 0, 1, h);
			g2.setColor(active ? theme.selectEdge : theme.edgeLight);
			g2.fillRect(x + 1, 1, tw - 2, 1);
			if (!active)
			{
				g2.setColor(theme.edgeDark);
				g2.fillRect(x, h - 1, tw, 1);
			}

			g2.setFont(OsrsSkin.font());
			String text = tabs.get(i);
			int tx = x + (tw - g2.getFontMetrics().stringWidth(text)) / 2;
			int ty = (h + 9) / 2;
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(text, tx + 1, ty + 1);
			g2.setColor(active ? OsrsSkin.TITLE : OsrsSkin.MUTED);
			g2.drawString(text, tx, ty);
		}
	}
}
