package com.ironhub.modules.collectionlog;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Tokens;
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
 * uses: the tab's emblem over its slot count over a thin fill bar, on the
 * CARD art (Luke, 2026-07-27 — and cards carry no hover tooltip; the tab's
 * name shows in the header of the list it opens).
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
	private final V2ProgressBar bar;

	ClogTabTile(OsrsTheme theme, Image icon, int obtained, int total, boolean selected,
		Runnable onClick)
	{
		this.theme = theme;
		this.icon = icon;
		this.obtained = obtained;
		this.total = total;
		this.selected = selected;
		this.bar = new V2ProgressBar(theme, V2ProgressBar.Size.METER);
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
		// the CARD art, matching the page grid's card tiles (Luke,
		// 2026-07-27); the wash goes flat-inset like V2Tile.card()
		V2Tokens.card().paint(g2, theme, 0, 0, w, h);
		if (hover || selected)
		{
			g2.setColor(V2Tokens.HIGHLIGHT);
			g2.fillRect(2, 2, w - 4, h - 4);
		}

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

		// the METER atom across the foot, painted in place
		bar.fill(complete() ? V2Tokens.BAR_FILL : V2Tokens.BAR_BLUE)
			.fraction(total == 0 ? 0 : obtained / (double) total);
		int barH = bar.getPreferredSize().height;
		int barY = h - barH - V2Tokens.TIGHT;
		bar.setBounds(V2Tokens.ROW, barY, w - 2 * V2Tokens.ROW, barH);
		g2.translate(V2Tokens.ROW, barY);
		bar.paint(g2);
		g2.translate(-V2Tokens.ROW, -barY);
	}

	private boolean complete()
	{
		return total > 0 && obtained >= total;
	}
}
