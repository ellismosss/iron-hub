package com.ironhub.ui.components;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

/**
 * The Design-lab StoneMeter, drawn on the canvas: a 5px recess trough with
 * a 1px-inset fill and a 1px dark outline — the exact thin bar the sidebar
 * uses (Luke, 2026-07-24: overlay bars were too thick). Theme-matched so it
 * tracks the player's skin. Extracted from the goals overlay 2026-08-03 so
 * every overlay's bar is the SAME small bar (R10).
 */
public final class OverlayStoneBar implements LayoutableRenderableEntity
{
	private static final int HEIGHT = 5;

	private final Rectangle bounds = new Rectangle();
	private final double fraction;
	private final com.ironhub.ui.osrs.OsrsTheme theme;
	private Point location = new Point();
	private int width;

	public OverlayStoneBar(double fraction, com.ironhub.ui.osrs.OsrsTheme theme, int width)
	{
		this.fraction = Math.min(1, Math.max(0, fraction));
		this.theme = theme;
		this.width = width;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		int y = location.y + 2;
		// StoneMeter.paintComponent, pixel-for-pixel: recess fill, the
		// semantic fill inset 1px (crisp, no AA), then the dark outline
		graphics.setColor(theme.recess);
		graphics.fillRect(location.x, y, width, HEIGHT);
		graphics.setColor(com.ironhub.ui.osrs.OsrsSkin.PROGRESS_BLUE);
		graphics.fillRect(location.x + 1, y + 1,
			(int) Math.round((width - 2) * fraction), HEIGHT - 2);
		graphics.setColor(theme.edgeDark);
		graphics.drawRect(location.x, y, width - 1, HEIGHT - 1);

		Dimension dimension = new Dimension(width, HEIGHT + 4);
		bounds.setLocation(location);
		bounds.setSize(dimension);
		return dimension;
	}

	@Override
	public Rectangle getBounds()
	{
		return bounds;
	}

	@Override
	public void setPreferredLocation(Point position)
	{
		this.location = position;
	}

	@Override
	public void setPreferredSize(Dimension dimension)
	{
		if (dimension != null && dimension.width > 0)
		{
			this.width = dimension.width;
		}
	}
}
