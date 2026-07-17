package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * The square stone button — the game's side-panel tab stone. Reserved for
 * NAVIGATION (per Luke: elsewhere a notched box reads cleaner). Selected
 * lifts the fill and brightens the bevel, exactly as the pack's
 * tab_stone_middle_selected sprite does.
 *
 * <p>Geometry follows that sprite (33x36): a chunky bevel (dark, then light)
 * with a 4px stepped corner, and the game's full-size tab icon centered — the
 * 18px Character Summary icons read as toys at this weight (Luke, 2026-07-16).
 */
public class StoneNavButton extends JComponent
{
	private static final int SIZE = 33;
	private static final int CORNER = 4;

	private final OsrsTheme theme;
	private final Icon icon;
	private final Runnable onClick;
	private final int width;
	private final int height;
	private boolean selected;
	private boolean hover;

	public StoneNavButton(OsrsTheme theme, Icon icon, boolean selected, Runnable onClick)
	{
		this(theme, icon, selected, onClick, SIZE, SIZE + 3);
	}

	/**
	 * A narrower stone — seven blocks must share the 225px panel, which is
	 * the same squeeze the game's own resizable tab row makes.
	 */
	public StoneNavButton(OsrsTheme theme, Icon icon, boolean selected, Runnable onClick,
		int width, int height)
	{
		this.theme = theme;
		this.icon = icon;
		this.selected = selected;
		this.onClick = onClick;
		this.width = width;
		this.height = height;
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

	public void setSelected(boolean selected)
	{
		this.selected = selected;
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(width, height);
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
		Color fill = selected ? theme.selectFill : hover ? theme.hoverFill : theme.boxFill;
		Color bevel = selected ? theme.selectEdge : theme.edgeLight;

		g2.setColor(theme.background);
		g2.fillRect(0, 0, w, h);

		// stepped corners: clip the bevel box in by CORNER px diagonally
		for (int inset = 0; inset < 2; inset++)
		{
			g2.setColor(inset == 0 ? theme.edgeDark : bevel);
			paintRing(g2, inset, w, h);
		}
		g2.setColor(fill);
		fillBody(g2, 2, w, h);

		if (icon != null)
		{
			icon.paintIcon(this, g2, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
		}
	}

	/**
	 * The slab grammar shared with status tiles (Luke, 2026-07-17: the farm
	 * overview and dailies tiles wear the nav stone's chamfered slab, keeping
	 * their own status bevel colour): dark outer ring, coloured bevel ring,
	 * chamfered fill. No background clear — callers own what shows through
	 * the notched corners.
	 */
	public static void paintSlab(Graphics2D g2, OsrsTheme theme, int w, int h,
		Color fill, Color bevel)
	{
		g2.setColor(theme.edgeDark);
		paintRing(g2, 0, w, h);
		g2.setColor(bevel);
		paintRing(g2, 1, w, h);
		g2.setColor(fill);
		fillBody(g2, 2, w, h);
	}

	/** The whole chamfered silhouette in one colour — dim/bare tiles. */
	public static void paintSilhouette(Graphics2D g2, int w, int h, Color fill)
	{
		g2.setColor(fill);
		fillBody(g2, 0, w, h);
	}

	/**
	 * The outer ring's pixels in clockwise order from the top centre — the
	 * path a perimeter-progress trace follows so it hugs the chamfer instead
	 * of cutting across the notched corners.
	 */
	public static java.util.List<java.awt.Point> ringPath(int inset, int w, int h)
	{
		int c = CORNER - inset;
		java.util.List<java.awt.Point> path = new java.util.ArrayList<>();
		int cx = w / 2;
		for (int x = cx; x <= w - 1 - inset - c; x++) // top edge, right half
		{
			path.add(new java.awt.Point(x, inset));
		}
		for (int step = c - 1; step >= 0; step--) // top-right chamfer, downward
		{
			path.add(new java.awt.Point(w - 1 - inset - step, inset + c - step));
		}
		for (int y = inset + c + 1; y <= h - 1 - inset - c; y++) // right edge
		{
			path.add(new java.awt.Point(w - 1 - inset, y));
		}
		for (int step = 0; step < c; step++) // bottom-right chamfer
		{
			path.add(new java.awt.Point(w - 1 - inset - step, h - 1 - inset - c + step));
		}
		for (int x = w - 1 - inset - c; x >= inset + c; x--) // bottom edge
		{
			path.add(new java.awt.Point(x, h - 1 - inset));
		}
		for (int step = c - 1; step >= 0; step--) // bottom-left chamfer, upward
		{
			path.add(new java.awt.Point(inset + step, h - 1 - inset - c + step));
		}
		for (int y = h - 1 - inset - c - 1; y >= inset + c; y--) // left edge
		{
			path.add(new java.awt.Point(inset, y));
		}
		for (int step = 0; step < c; step++) // top-left chamfer, upward
		{
			path.add(new java.awt.Point(inset + step, inset + c - step));
		}
		for (int x = inset + c; x < cx; x++) // top edge, left half
		{
			path.add(new java.awt.Point(x, inset));
		}
		return path;
	}

	/** One 1px ring of the chamfered box, `inset` px in from the edge. */
	private static void paintRing(Graphics2D g2, int inset, int w, int h)
	{
		int c = CORNER - inset;
		g2.fillRect(inset + c, inset, w - 2 * (inset + c), 1);
		g2.fillRect(inset + c, h - 1 - inset, w - 2 * (inset + c), 1);
		g2.fillRect(inset, inset + c, 1, h - 2 * (inset + c));
		g2.fillRect(w - 1 - inset, inset + c, 1, h - 2 * (inset + c));
		for (int step = 0; step < c; step++)
		{
			int dx = inset + step, dy = inset + c - step;
			g2.fillRect(dx, dy, 1, 1);
			g2.fillRect(w - 1 - dx, dy, 1, 1);
			g2.fillRect(dx, h - 1 - dy, 1, 1);
			g2.fillRect(w - 1 - dx, h - 1 - dy, 1, 1);
		}
	}

	private static void fillBody(Graphics2D g2, int inset, int w, int h)
	{
		int c = Math.max(0, CORNER - inset);
		g2.fillRect(inset, inset + c, w - 2 * inset, h - 2 * (inset + c));
		for (int step = 0; step < c; step++)
		{
			int y1 = inset + step;
			int pad = c - step;
			g2.fillRect(inset + pad, y1, w - 2 * (inset + pad), 1);
			g2.fillRect(inset + pad, h - 1 - y1, w - 2 * (inset + pad), 1);
		}
	}
}
