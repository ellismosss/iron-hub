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
import javax.swing.JComponent;

/**
 * A labelled navigation tile — the grid a hub page uses instead of a stack
 * of name plates (Luke, 2026-07-24: the Progression hub's nine collapsible
 * headers became a 4x2 icon grid). Wears the nav stone's chamfered slab and
 * its selected/hover states, with the section's item sprite over a small
 * centred caption.
 *
 * <p>The caption paints here rather than through an {@link OsrsLabel} child
 * because the tile owns its own layout: same recipe though — antialiasing
 * off, +1/+1 black shadow, ellipsis at paint time when a caption outgrows
 * its tile.
 */
public class StoneHubTile extends JComponent
{
	/**
	 * Four across the 225px panel: the home's own 4px frame padding and the
	 * grid's 4px inset take 16, three 3px gaps take 9, leaving 200 — measured
	 * from the render, where a 52px tile ran the fourth one off the edge.
	 */
	public static final int WIDTH = 50;
	public static final int HEIGHT = 54;
	/** Caption band at the foot of the tile (12px pitch + 1px breathing). */
	private static final int CAPTION = 13;

	private final OsrsTheme theme;
	private final Image icon;
	private final String caption;
	private final Runnable onClick;
	private boolean selected;
	private boolean hover;

	public StoneHubTile(OsrsTheme theme, Image icon, String caption, String tooltip,
		boolean selected, Runnable onClick)
	{
		this.theme = theme;
		this.icon = icon;
		this.caption = caption;
		this.selected = selected;
		this.onClick = onClick;
		setToolTipText(tooltip);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onClick != null)
				{
					onClick.run();
				}
			}
		});
	}

	public void setSelected(boolean selected)
	{
		this.selected = selected;
		repaint();
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
		Color fill = selected ? theme.selectFill : hover ? theme.hoverFill : theme.boxFill;
		Color bevel = selected ? theme.selectEdge : theme.edgeLight;
		StoneNavButton.paintSlab(g2, theme, w, h, fill, bevel);

		if (icon != null)
		{
			int band = h - CAPTION;
			g2.drawImage(icon, (w - icon.getWidth(null)) / 2,
				Math.max(2, (band - icon.getHeight(null)) / 2), null);
		}

		if (caption != null && !caption.isEmpty())
		{
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setFont(OsrsSkin.font());
			FontMetrics fm = g2.getFontMetrics();
			String line = caption;
			if (fm.stringWidth(line) > w - 4)
			{
				while (line.length() > 1 && fm.stringWidth(line + "…") > w - 4)
				{
					line = line.substring(0, line.length() - 1);
				}
				line = line + "…";
			}
			int x = (w - fm.stringWidth(line)) / 2;
			int y = h - 4;
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(line, x + 1, y + 1);
			g2.setColor(selected ? OsrsSkin.TITLE : OsrsSkin.MUTED);
			g2.drawString(line, x, y);
		}
	}
}
