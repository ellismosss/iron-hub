package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * The game's own section divider: the stone bar it draws between panel
 * sections, tiled to width.
 *
 * <p>The sprite is a 32x32 canvas with the bar sitting in rows 14..19 and
 * transparent padding around it (that padding is how the game centres the bar
 * on a boundary). Only the bar is drawn here, so the divider costs its own
 * 6px and no more.
 */
public class V2Divider extends JComponent
{
	private static final String SPRITE = "ui/borders/bottom_line_mode_side_panel_edge_horizontal";
	static final int BAR_TOP = 14;
	static final int BAR_HEIGHT = 6;

	private final OsrsTheme theme;

	public V2Divider(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		BufferedImage bar = V2Sprites.get(theme, SPRITE);
		for (int x = 0; x < getWidth(); x += bar.getWidth())
		{
			int w = Math.min(bar.getWidth(), getWidth() - x);
			g.drawImage(bar, x, 0, x + w, BAR_HEIGHT,
				0, BAR_TOP, w, BAR_TOP + BAR_HEIGHT, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, BAR_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, BAR_HEIGHT);
	}

	/** A UI-less JComponent's default minimum is its current size — 0x0 before
	 *  layout — and BoxLayout derives row alignment from child minimums. */
	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, BAR_HEIGHT);
	}
}
