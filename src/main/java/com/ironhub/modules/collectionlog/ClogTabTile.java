package com.ironhub.modules.collectionlog;

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
import javax.swing.JComponent;

/**
 * One of the log's five tab buttons, in the shape the game's overview screen
 * uses: the tab's emblem over its slot count over a thin fill bar. Five sit
 * across the 225px panel, so the tab's NAME lives in the tooltip and in the
 * header of the list it opens.
 */
class ClogTabTile extends JComponent
{
	static final int WIDTH = 41;
	static final int HEIGHT = 52;
	private static final int ICON_BAND = 32;
	private static final int METER = 3;

	private final OsrsTheme theme;
	private final Image icon;
	private final int obtained;
	private final int total;
	private boolean selected;
	private boolean hover;

	ClogTabTile(OsrsTheme theme, Image icon, int obtained, int total, boolean selected,
		String tooltip, Runnable onClick)
	{
		this.theme = theme;
		this.icon = icon;
		this.obtained = obtained;
		this.total = total;
		this.selected = selected;
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
		StoneNavButton.paintSlab(g2, theme, w, h, fill,
			selected ? theme.selectEdge : theme.edgeLight);

		if (icon != null)
		{
			g2.drawImage(icon, (w - icon.getWidth(null)) / 2,
				Math.max(2, (ICON_BAND - icon.getHeight(null)) / 2 + 2), null);
		}

		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(OsrsSkin.smallFont());
		FontMetrics fm = g2.getFontMetrics();
		String text = obtained + "/" + total;
		int x = (w - fm.stringWidth(text)) / 2;
		int y = h - METER - 5;
		g2.setColor(OsrsSkin.TEXT_SHADOW);
		g2.drawString(text, x + 1, y + 1);
		g2.setColor(complete() ? OsrsSkin.VALUE : selected ? OsrsSkin.TITLE : OsrsSkin.MUTED);
		g2.drawString(text, x, y);

		// the fill bar, in the thin StoneMeter grammar (trough, inset fill)
		int barY = h - METER - 2;
		int barX = 4;
		int barW = w - 8;
		g2.setColor(OsrsSkin.BAR_TROUGH);
		g2.fillRect(barX, barY, barW, METER);
		if (total > 0 && obtained > 0)
		{
			int filled = Math.max(1, Math.round((barW - 2) * (float) obtained / total));
			g2.setColor(complete() ? OsrsSkin.VALUE : OsrsSkin.PROGRESS_BLUE);
			g2.fillRect(barX + 1, barY + 1, filled, METER - 2);
		}
	}

	private boolean complete()
	{
		return total > 0 && obtained >= total;
	}
}
