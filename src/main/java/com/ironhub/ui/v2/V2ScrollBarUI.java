package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The game's own scrollbar, in every theme: its arrow buttons, its three-part
 * thumb (top cap, tiled middle, bottom cap) and its track (Luke, 2026-07-25 —
 * the art is in all_sprites under {@code scrollbar/}, so there was nothing to
 * draw).
 *
 * <p>Swing needs a {@code ScrollBarUI} — there is no drawing your own and
 * having the viewport cooperate — so this is the one atom that plugs into a
 * Swing delegate rather than being a component.
 */
public class V2ScrollBarUI extends BasicScrollBarUI
{
	private static final String ARROW_UP = "ui/scrollbar/arrow_up";
	private static final String ARROW_DOWN = "ui/scrollbar/arrow_down";
	private static final String THUMB_TOP = "ui/scrollbar/thumb_top";
	private static final String THUMB_MIDDLE = "ui/scrollbar/thumb_middle";
	private static final String THUMB_BOTTOM = "ui/scrollbar/thumb_bottom";
	private static final String TRACK = "ui/scrollbar/transparent_thumb_background";

	private static final int WIDTH = 16;

	private final OsrsTheme theme;

	public V2ScrollBarUI(OsrsTheme theme)
	{
		this.theme = theme;
	}

	/** Dress a scroll pane's vertical bar. Horizontal scrolling doesn't
	 *  exist in this panel — 225px, one column. */
	public static void install(JScrollPane pane, OsrsTheme theme)
	{
		JScrollBar bar = pane.getVerticalScrollBar();
		bar.setUI(new V2ScrollBarUI(theme));
		// the track art is partly transparent, so an opaque scrollbar would
		// fill it with the look-and-feel's own white first
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(WIDTH, 0));
		bar.setUnitIncrement(V2Tokens.ROW_HEIGHT);
		bar.setBlockIncrement(V2Tokens.ROW_HEIGHT * 5);
		pane.setBorder(null);
		pane.getViewport().setOpaque(false);
		pane.setOpaque(false);
	}

	@Override
	protected void paintTrack(Graphics g, JComponent c, Rectangle bounds)
	{
		BufferedImage track = V2Sprites.get(theme, TRACK);
		for (int y = 0; y < bounds.height; y += track.getHeight())
		{
			int h = Math.min(track.getHeight(), bounds.height - y);
			g.drawImage(track, bounds.x, bounds.y + y, bounds.x + bounds.width,
				bounds.y + y + h, 0, 0, track.getWidth(), h, null);
		}
	}

	@Override
	protected void paintThumb(Graphics g, JComponent c, Rectangle bounds)
	{
		if (bounds.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}
		BufferedImage top = V2Sprites.get(theme, THUMB_TOP);
		BufferedImage mid = V2Sprites.get(theme, THUMB_MIDDLE);
		BufferedImage bottom = V2Sprites.get(theme, THUMB_BOTTOM);
		int capped = Math.max(0, bounds.height - top.getHeight() - bottom.getHeight());
		for (int y = 0; y < capped; y += mid.getHeight())
		{
			int h = Math.min(mid.getHeight(), capped - y);
			g.drawImage(mid, bounds.x, bounds.y + top.getHeight() + y,
				bounds.x + bounds.width, bounds.y + top.getHeight() + y + h,
				0, 0, mid.getWidth(), h, null);
		}
		g.drawImage(top, bounds.x, bounds.y, null);
		g.drawImage(bottom, bounds.x, bounds.y + bounds.height - bottom.getHeight(), null);
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return arrow(ARROW_DOWN);
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return arrow(ARROW_UP);
	}

	private JButton arrow(String key)
	{
		JButton button = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				BufferedImage art = V2Sprites.get(theme, key);
				g.drawImage(art, (getWidth() - art.getWidth()) / 2,
					(getHeight() - art.getHeight()) / 2, null);
			}
		};
		V2Sprites.Meta meta = V2Sprites.meta(key);
		button.setPreferredSize(new Dimension(WIDTH, meta.height()));
		button.setBorder(null);
		button.setContentAreaFilled(false);
		button.setFocusable(false);
		return button;
	}

	/** The thumb never shrinks below a grabbable size. */
	@Override
	protected Dimension getMinimumThumbSize()
	{
		return new Dimension(WIDTH, V2Tokens.BLOCK);
	}
}
