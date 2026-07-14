package com.ironhub.modules.gear;

import com.ironhub.ui.UiTokens;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
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
 * One chart node: the item's game sprite on a flat tile. Obtained =
 * green border + wash; targeted (added to the goal planner) = accent.
 * Left-click toggles targeting, right-click opens the context menu,
 * hover shows the tooltip supplied by the tab.
 */
class ItemTile extends JComponent
{
	static final int W = 38;
	static final int H = 34;
	private static final float WASH_ALPHA = 0.18f;

	private final String name;
	private final boolean obtained;
	private final boolean targeted;
	private final boolean ready; // requirements met, not yet obtained
	private BufferedImage icon;

	ItemTile(String name, boolean obtained, boolean targeted, boolean ready, String tooltip,
		Runnable onClick, Consumer<MouseEvent> onContext)
	{
		this.name = name;
		this.obtained = obtained;
		this.targeted = targeted;
		this.ready = ready;
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
		g2.setColor(UiTokens.TILE_BG_LOCKED);
		g2.fillRect(0, 0, W, H);

		if (icon != null)
		{
			// scale down to fit (wiki object icons vary in size); never scale up
			int w = icon.getWidth();
			int h = icon.getHeight();
			double fit = Math.min(1.0, Math.min((W - 4) / (double) w, (H - 4) / (double) h));
			w = (int) Math.round(w * fit);
			h = (int) Math.round(h * fit);
			g2.drawImage(icon, (W - w) / 2, (H - h) / 2, w, h, null);
		}
		else
		{
			// headless / cache-less fallback: two-letter code like GridTile
			g2.setColor(UiTokens.TEXT_MUTED);
			g2.setFont(getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_CODE));
			FontMetrics fm = g2.getFontMetrics();
			String code = code(name);
			g2.drawString(code, (W - fm.stringWidth(code)) / 2, (H + fm.getAscent()) / 2 - 2);
		}

		Color status = obtained ? UiTokens.STATUS_OWNED : targeted ? UiTokens.ACCENT : null;
		if (status != null)
		{
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, WASH_ALPHA));
			g2.setColor(status);
			g2.fillRect(0, 0, W, H);
			g2.setComposite(AlphaComposite.SrcOver);
		}
		g2.setColor(status != null ? status : UiTokens.BORDER_DIM);
		g2.drawRect(0, 0, W - 1, H - 1);

		if (ready && !obtained)
		{
			// small green corner triangle: requirements met, go get it
			g2.setColor(UiTokens.STATUS_OWNED);
			g2.fillPolygon(new int[]{0, 6, 0}, new int[]{0, 0, 6}, 3);
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
