package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The scrollbar, composed (§9): the Well recess as the trough, the Card
 * slice as the thumb, and the curated 16px arrows as the end buttons.
 *
 * <p>Swing needs a {@code ScrollBarUI} — there is no drawing our own and
 * having the viewport cooperate — so this is the one atom that plugs into a
 * Swing delegate rather than being a component. Everything it paints still
 * comes from the curated set.
 */
public class V2ScrollBarUI extends BasicScrollBarUI
{
	private static final int WIDTH = 16;

	private final OsrsTheme theme;
	private final NineSlice trough = V2Tokens.well();
	private final NineSlice thumbArt = V2Tokens.card();

	public V2ScrollBarUI(OsrsTheme theme)
	{
		this.theme = theme;
	}

	/** Dress a scroll pane's vertical bar. Horizontal scrolling doesn't
	 *  exist in this panel — 225px, one column (§7). */
	public static void install(JScrollPane pane, OsrsTheme theme)
	{
		JScrollBar bar = pane.getVerticalScrollBar();
		bar.setUI(new V2ScrollBarUI(theme));
		// the trough is a BORDER, so its middle is transparent: an opaque
		// scrollbar fills that middle with the look-and-feel's own white
		// before we ever paint (the render caught it)
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
		trough.paint((Graphics2D) g, theme, bounds.x, bounds.y, bounds.width, bounds.height);
	}

	@Override
	protected void paintThumb(Graphics g, JComponent c, Rectangle bounds)
	{
		if (bounds.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}
		thumbArt.paint((Graphics2D) g, theme, bounds.x, bounds.y, bounds.width, bounds.height);
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return arrow(V2SpriteButton.ARROW_DOWN);
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return arrow(V2SpriteButton.ARROW_UP);
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
		button.setPreferredSize(new Dimension(WIDTH, WIDTH));
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
