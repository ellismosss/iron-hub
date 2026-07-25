package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * A selectable tile: an emblem, an optional caption under it, and an
 * optional corner tick for "you own this". Hub tiles, gear tiles, category
 * tiles — the grid unit.
 *
 * <p>Wears the Tile surface — the same chamfered stone, Card grain and dimmed
 * edge as {@code V2Surface.tile}, through the one painter both call. It takes
 * any size: every real grid in this panel is sized by its column count, so a
 * fixed 40px square could never have served them all.
 *
 * <p>Selected reads exactly as a chip does: lit art plus a HEADING-orange
 * caption. Owned is a painted corner tick from the curated checkmark, which
 * is what Luke asked for on the gear library — green text on the name read
 * as a status word rather than a possession.
 */
public class V2Tile extends JComponent
{
	/**
	 * The V1 status tile's four readings, ported (Luke, 2026-07-25). The
	 * status is a 1px edge inside the tile rather than a fill, so the emblem
	 * keeps its own colours and a grid of them still scans as a grid.
	 */
	public enum Status
	{
		/** Nothing to say. */
		PLAIN(null, false),
		/** Done, built, owned. */
		DONE(V2Tokens.DONE, true),
		/** Actionable now. */
		READY(V2Tokens.ACTION, true),
		/**
		 * Locked, missing, not yet reachable — greyed out rather than red
		 * (Luke, 2026-07-25). Red is for something WRONG; a tile you cannot
		 * use yet is not an error, and a grid of red rings read as a wall of
		 * failures. It recedes instead, which is also what {@code FAINT} means
		 * everywhere else in the system.
		 */
		UNAVAILABLE(V2Tokens.FAINT, true);

		final java.awt.Color edge;
		/**
		 * Whether the edge needs pulling back toward the panel.
		 *
		 * <p>UNAVAILABLE went false for one round, when its ring was the only
		 * thing marking it and dimming a muted colour twice left nothing to
		 * see. It is back to true now that the tile also carries the dark
		 * {@code SHADOW} fill: the fill does the work, so a bright ring on top
		 * of it just reads as an outline round a hole (Luke, 2026-07-25).
		 */
		final boolean dim;

		Status(java.awt.Color edge, boolean dim)
		{
			this.edge = edge;
			this.dim = dim;
		}
	}

	private static final String TICK = "ui/ticks/checkmark_small";

	private final OsrsTheme theme;
	private final BufferedImage emblem;
	private final OsrsLabel caption;
	private final int size;
	private boolean selected;
	private boolean owned;
	private boolean hover;
	private Status status = Status.PLAIN;
	/** How far the status edge wraps, clockwise from the top centre. 1 is the
	 *  whole way round, which is a plain status edge. */
	private double progress = 1;

	public V2Tile(OsrsTheme theme, BufferedImage emblem, String caption, int size,
		Runnable onPress)
	{
		this.theme = theme;
		this.emblem = emblem;
		this.caption = caption == null ? null : V2Label.centred(caption);
		this.size = size;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onPress != null)
				{
					onPress.run();
				}
			}

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
		});
	}

	public V2Tile selected(boolean selected)
	{
		this.selected = selected;
		repaint();
		return this;
	}

	/** The V1 status-tile edge. */
	public V2Tile status(Status status)
	{
		this.status = status;
		repaint();
		return this;
	}

	/**
	 * How far the status edge wraps the tile, 0..1, clockwise from the top
	 * centre — 8 of 12 patches ready, 3 of 5 rooms built. Needs a
	 * {@link #status} to have a colour to draw in.
	 */
	public V2Tile progress(double fraction)
	{
		this.progress = fraction;
		repaint();
		return this;
	}

	/** A corner tick — owned, built, complete. */
	public V2Tile owned(boolean owned)
	{
		this.owned = owned;
		repaint();
		return this;
	}

	public boolean isSelected()
	{
		return selected;
	}

	/** V2 tooltips wear the Card (§9) — Swing routes them through here. */
	@Override
	public javax.swing.JToolTip createToolTip()
	{
		V2Tooltip tip = new V2Tooltip(theme);
		tip.setComponent(this);
		return tip;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		// the Tile SURFACE, shared with V2Surface.tile — this used to be
		// V2Tokens.navStone(), which is RuneLite's dark grey tab stone and
		// nothing to do with the tile we built (Luke, 2026-07-25: "some weird
		// dark sprites")
		V2Surface.paintTile(g2, theme, getWidth(), size, selected);
		// selected carries the wash permanently, so a picked tile stays lifted
		// whether or not the pointer is on it — the bevel says WHICH is picked
		if (hover || selected)
		{
			V2Surface.washTile(g2, getWidth(), size);
		}


		if (emblem != null)
		{
			// dead centre of the tile: the caption lives OUTSIDE the art now
			// (Luke, 2026-07-25 — text on the tile pushed the icon off centre)
			g2.drawImage(emblem, (getWidth() - emblem.getWidth()) / 2,
				(size - emblem.getHeight()) / 2, null);
		}
		if (caption != null)
		{
			caption.setSize(getWidth(), V2Tokens.LINE_PITCH + 5);
			g2.translate(0, size + V2Tokens.TIGHT);
			caption.setColor(selected ? V2Tokens.HEADING : V2Tokens.TEXT);
			caption.paint(g2);
			g2.translate(0, -(size + V2Tokens.TIGHT));
		}
		if (status.edge != null)
		{
			// Traced around the chamfer, not drawn as a rectangle: a drawRect
			// cuts straight across all four notched corners (Luke, 2026-07-25).
			//
			// ONE ring, at inset 1 — the bevel, INSIDE the slab's dark outer
			// ring. This is V1's status tile exactly (StoneTile hands the
			// status colour to paintSlab as the bevel), and Luke preferred it:
			// tracing insets 0 and 1 painted over the dark border, so the tile
			// lost its outline and the colour became the whole edge.
			//
			// ringPath is an ORDERED walk from the top centre, so stopping
			// partway round is what draws progress. A plain status edge is
			// just progress = 1.
			g2.setColor(status.dim ? V2Tokens.statusEdge(theme, status.edge) : status.edge);
			java.util.List<java.awt.Point> ring =
				com.ironhub.ui.osrs.StoneNavButton.ringPath(1, getWidth(), size);
			int covered = (int) Math.round(Math.max(0, Math.min(1, progress)) * ring.size());
			for (int i = 0; i < covered; i++)
			{
				java.awt.Point p = ring.get(i);
				g2.fillRect(p.x, p.y, 1, 1);
			}
		}
		// unavailable sinks LAST, so the shade falls on the ring and the emblem
		// too. Drawn before them it left a bright outline round a dark hole
		// (Luke, 2026-07-25: "reduce the light border around the unavailable
		// tile") — the whole tile has to recede, not just its middle.
		if (status == Status.UNAVAILABLE)
		{
			V2Surface.shadeTile(g2, getWidth(), size);
		}
		if (owned)
		{
			BufferedImage tick = V2Sprites.get(theme, TICK);
			g2.drawImage(tick, getWidth() - tick.getWidth() - V2Tokens.TIGHT, V2Tokens.TIGHT, null);
		}
		// the tile art stops at `size`; anything below it is the caption
	}

	@Override
	public Dimension getPreferredSize()
	{
		// the caption sits UNDER the art, so it costs height, not centring
		return new Dimension(size, caption == null ? size
			: size + V2Tokens.TIGHT + V2Tokens.LINE_PITCH + V2Tokens.ROW);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}
}
