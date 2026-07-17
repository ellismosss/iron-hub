package com.ironhub.ui.osrs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import javax.swing.JComponent;

/**
 * A small status tile: an item sprite in a stone setting whose inner bevel
 * carries a status colour (green = go, per the farm/dailies scale). Wears
 * the nav stone's chamfered slab (Luke, 2026-07-17 — superseding the
 * earlier flat-rectangle rule; the status bevel colour survives on the
 * slab's inner ring).
 *
 * <p>A dimmed tile (excluded, or locked) sinks: the bare chamfered
 * silhouette in recess, no engraving, ghosted icon — "not part of your
 * routine", the same reading as the farm overview's nothing-planted tiles.
 */
public class StoneTile extends JComponent
{
	public static final int WIDTH = 34;
	public static final int HEIGHT = 30;
	public static final int ICON = 24;

	private final OsrsTheme theme;
	private final Color statusBevel; // null = the plain engraved bevel
	private final boolean dim;
	private Image icon;

	public StoneTile(OsrsTheme theme, Color statusBevel, boolean dim, String tooltip)
	{
		this.theme = theme;
		this.statusBevel = statusBevel;
		this.dim = dim;
		setToolTipText(tooltip);
	}

	public void setIconImage(Image image)
	{
		this.icon = image;
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
		int w = getWidth(), h = getHeight();
		if (dim)
		{
			StoneNavButton.paintSilhouette(g2, w, h, theme.recess);
		}
		else
		{
			StoneNavButton.paintSlab(g2, theme, w, h, theme.boxFill,
				statusBevel != null ? statusBevel : theme.edgeLight);
		}
		if (icon != null)
		{
			if (dim)
			{
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
			}
			g2.drawImage(icon, (w - ICON) / 2, (h - ICON) / 2, null);
			g2.setComposite(AlphaComposite.SrcOver);
		}
	}
}
