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
 * <p>Built on the Card slice rather than the game's fixed 40px squares, so
 * one atom covers every grid the plugin lays out. The fixed squares are
 * still reachable as {@link V2SpriteButton}; what they cannot do is be 50px
 * wide, and every real grid in this panel is sized by its column count.
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
		PLAIN(null),
		/** Done, built, owned. */
		DONE(V2Tokens.DONE),
		/** Actionable now. */
		READY(V2Tokens.ACTION),
		/** Locked or missing. */
		BLOCKED(V2Tokens.BLOCKED);

		final java.awt.Color edge;

		Status(java.awt.Color edge)
		{
			this.edge = edge;
		}
	}

	private static final String TICK = "ui/ticks/checkmark_small";

	private final OsrsTheme theme;
	private final NineSlice plain = V2Tokens.card();
	private final NineSlice lit = V2Tokens.card().variant("_hovered");
	private final BufferedImage emblem;
	private final OsrsLabel caption;
	private final int size;
	private boolean selected;
	private boolean owned;
	private boolean hover;
	private Status status = Status.PLAIN;

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
		(selected ? lit : plain).paint(g2, theme, 0, 0, getWidth(), size);
		if (hover && !selected)
		{
			g2.setColor(V2Tokens.HIGHLIGHT);
			g2.fillRect(0, 0, getWidth(), size);
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
			g2.setColor(status.edge);
			// no sprite in the set carries a status edge, and the V1 status
			// tile Luke asked to keep is defined by exactly this
			g2.drawRect(0, 0, getWidth() - 1, size - 1); // v2-exempt: status edge
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
