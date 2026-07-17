package com.ironhub.modules.gear;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * One chart node: the item's game sprite in a stone setting (the StoneTile
 * grammar — engraved edge pair whose inner bevel carries the status).
 * Green bevel = obtained, orange bevel = targeted in the goal planner.
 * Left-click toggles targeting, right-click opens the context menu,
 * hover shows the tooltip supplied by the tab.
 */
class ItemTile extends JComponent
{
	static final int W = 38;
	static final int H = 34;

	private final OsrsTheme theme;
	private final String name;
	private final boolean obtained;
	private final boolean targeted;
	private final boolean ready; // requirements met, not yet obtained
	private final boolean boostReady; // met only with an available temporary boost
	private BufferedImage icon;

	ItemTile(OsrsTheme theme, String name, boolean obtained, boolean targeted, boolean ready,
		boolean boostReady, String tooltip, Runnable onClick, Consumer<MouseEvent> onContext)
	{
		this.theme = theme;
		this.name = name;
		this.obtained = obtained;
		this.targeted = targeted;
		this.ready = ready;
		this.boostReady = boostReady;
		setToolTipText(tooltip);
		Dimension size = new Dimension(W, H);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
				{
					onContext.accept(e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					onClick.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					onContext.accept(e);
				}
			}
		});
	}

	/** Sprite arrives async; repaint when it lands. */
	void setIcon(BufferedImage icon)
	{
		this.icon = icon;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		// StoneTile grammar: engraved edge pair, the inner bevel is the status
		OsrsSkin.outline(g2, theme.edgeDark, 0, 0, W, H);
		Color bevel = obtained ? OsrsSkin.VALUE.darker()
			: targeted ? OsrsSkin.TITLE.darker() : theme.edgeLight;
		OsrsSkin.outline(g2, bevel, 1, 1, W - 2, H - 2);
		g2.setColor(theme.boxFill);
		g2.fillRect(2, 2, W - 4, H - 4);

		if (icon != null)
		{
			// scale down to fit (wiki object icons vary in size); never scale up
			int w = icon.getWidth();
			int h = icon.getHeight();
			double fit = Math.min(1.0, Math.min((W - 6) / (double) w, (H - 6) / (double) h));
			w = (int) Math.round(w * fit);
			h = (int) Math.round(h * fit);
			g2.drawImage(icon, (W - w) / 2, (H - h) / 2, w, h, null);
		}
		else
		{
			// headless / cache-less fallback: two-letter code in the game font
			g2.setFont(OsrsSkin.font());
			FontMetrics fm = g2.getFontMetrics();
			String code = code(name);
			int x = (W - fm.stringWidth(code)) / 2;
			int y = (H + fm.getAscent()) / 2 - 2;
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(code, x + 1, y + 1);
			g2.setColor(OsrsSkin.MUTED);
			g2.drawString(code, x, y);
		}

		if ((ready || boostReady) && !obtained)
		{
			// corner triangle: bright = requirements met outright,
			// dark = reachable with a temporary boost you have access to
			g2.setColor(ready ? OsrsSkin.TITLE : OsrsSkin.TITLE.darker());
			g2.fillPolygon(new int[]{2, 8, 2}, new int[]{2, 2, 8}, 3);
		}
		g2.dispose();
	}

	private static String code(String name)
	{
		String[] words = name.split("\\s+");
		return (words.length > 1
			? "" + words[0].charAt(0) + words[1].charAt(0)
			: name.substring(0, Math.min(2, name.length()))).toUpperCase(Locale.ROOT);
	}
}
