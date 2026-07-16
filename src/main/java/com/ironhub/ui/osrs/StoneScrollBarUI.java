package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The game's scrollbar: a sunken track, a bevelled thumb, and a square
 * arrow button at each end. 16px wide in both sources.
 *
 * <p>Anatomy sampled from the wiki's File:Settings_interface.png (thumb and
 * arrow buttons over a #252019 track) and the pack's scrollbar/*.png
 * (thumb_top/middle/bottom: #141414 outer, #383838 bevel, #2B2B2B fill —
 * the same grammar, so it paints from theme tokens). Vanilla's thumb carries
 * a vertical gradient; we flatten it to its mean, since flat fills are the
 * house rule and the bevel is what reads.
 */
public class StoneScrollBarUI extends BasicScrollBarUI
{
	/**
	 * Named THICKNESS, not WIDTH: Component implements ImageObserver, whose
	 * WIDTH=1 constant silently shadows an outer class's WIDTH inside any
	 * inner Component subclass — which laid the arrow buttons out 1px tall.
	 */
	public static final int THICKNESS = 16;

	private final OsrsTheme theme;

	public StoneScrollBarUI(OsrsTheme theme)
	{
		this.theme = theme;
	}

	/** Style a scrollbar in one call — the migration path for real panes. */
	public static JScrollBar skin(JScrollBar bar, OsrsTheme theme)
	{
		bar.setUI(new StoneScrollBarUI(theme));
		bar.setPreferredSize(new Dimension(THICKNESS, bar.getPreferredSize().height));
		bar.setUnitIncrement(com.ironhub.ui.UiTokens.SCROLL_UNIT);
		return bar;
	}

	@Override
	protected void paintTrack(Graphics g, JComponent c, Rectangle bounds)
	{
		g.setColor(theme.scrollTrough);
		g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	@Override
	protected void paintThumb(Graphics g, JComponent c, Rectangle b)
	{
		if (b.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}
		paintStone(g, b.x, b.y, b.width, b.height,
			isDragging ? theme.selectFill : isThumbRollover() ? theme.hoverFill : theme.scrollThumb);
	}

	/** The shared box: 1px dark outer, 1px light bevel, flat fill. */
	private void paintStone(Graphics g, int x, int y, int w, int h, Color fill)
	{
		g.setColor(theme.edgeDark);
		g.fillRect(x, y, w, h);
		g.setColor(theme.edgeLight);
		g.fillRect(x + 1, y + 1, w - 2, h - 2);
		g.setColor(fill);
		g.fillRect(x + 2, y + 2, w - 4, h - 4);
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return new ArrowButton(true);
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return new ArrowButton(false);
	}

	/** A stone square with the game's solid triangle glyph. */
	private class ArrowButton extends JButton
	{
		private final boolean up;

		ArrowButton(boolean up)
		{
			this.up = up;
			setBorder(null);
			setFocusable(false);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(THICKNESS, THICKNESS);
		}

		@Override
		public void paint(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			int w = getWidth(), h = getHeight();
			paintStone(g2, 0, 0, w, h, getModel().isPressed() ? theme.pressFill
				: getModel().isRollover() ? theme.hoverFill : theme.scrollThumb);

			g2.setColor(theme.edgeDark);
			int cx = w / 2, cy = h / 2, size = 3;
			for (int row = 0; row <= size; row++)
			{
				int width = 1 + 2 * row;
				int y = up ? cy - size / 2 + row : cy + size / 2 - row;
				g2.fillRect(cx - row, y, width, 1);
			}
		}
	}
}
