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
	/**
	 * Any {@link java.awt.Image}, not just a {@code BufferedImage} — the
	 * module tabs draw item sprites through {@code SpriteCache}, which hands
	 * back {@code getScaledInstance}'s result. Widened rather than converted:
	 * a copy per tile per rebuild, to satisfy a type, on the panel's busiest
	 * path (Luke's Dailies pass, 2026-07-26).
	 */
	private java.awt.Image emblem;
	/**
	 * The caption text, kept UNWRAPPED. It is word-wrapped at paint time
	 * against the tile's real width, because a grid sizes its tiles by its
	 * column count and the constructor cannot know that yet (the Gear library
	 * and the Build modules both need two lines — "Achievement diaries" is one
	 * word too long for 52px).
	 */
	private final String captionText;
	/** The wrapped label, rebuilt whenever the width it was wrapped at moves. */
	private OsrsLabel caption;
	private int captionWidth = -1;
	/** How many lines the caption may take. */
	private int captionLines = 1;
	private final int size;
	/** The tile's width, when it is not the square the height implies. */
	private int width;
	/** >1 when this tile stands for a group of members — a corner count. */
	private int badge;
	private boolean selected;
	private boolean owned;
	private boolean hover;
	private Status status = Status.PLAIN;
	/** How far the status edge wraps, clockwise from the top centre. 1 is the
	 *  whole way round, which is a plain status edge. */
	private double progress = 1;

	public V2Tile(OsrsTheme theme, java.awt.Image emblem, String caption, int size,
		Runnable onPress)
	{
		this.theme = theme;
		this.emblem = emblem;
		this.captionText = caption;
		this.size = size;
		this.width = size;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					right(e);
				}
				else if (javax.swing.SwingUtilities.isLeftMouseButton(e) && onPress != null)
				{
					onPress.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				// popupTrigger lands on press on some platforms and release on
				// others; the context menu has to answer on both
				if (e.isPopupTrigger())
				{
					right(e);
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

	/** A context menu on this tile. */
	public V2Tile onRightClick(java.util.function.Consumer<MouseEvent> onRight)
	{
		this.onRight = onRight;
		return this;
	}

	private java.util.function.Consumer<MouseEvent> onRight;

	private void right(MouseEvent e)
	{
		if (onRight != null)
		{
			onRight.accept(e);
		}
	}

	/**
	 * Wider than it is tall — a set tile spanning two grid columns. Without
	 * this a tile is the square its {@code size} implies.
	 */
	public V2Tile width(int width)
	{
		this.width = width;
		return this;
	}

	/** Let the caption wrap to at most this many lines. */
	public V2Tile captionLines(int lines)
	{
		this.captionLines = lines;
		return this;
	}

	/**
	 * Caption painted ON the art band — bottom-anchored, BOLD — instead of
	 * under the tile, so the tile is exactly {@code size} tall (the clog
	 * page grid; Luke, 2026-07-27). This coexists with the 2025 outside-
	 * caption ruling rather than reversing it: the emblem centres in the
	 * band the caption leaves free, so it is not pushed off centre, which
	 * was that ruling's whole complaint.
	 */
	public V2Tile captionInside()
	{
		this.captionInside = true;
		this.caption = null; // rebuilt bold on next paint
		return this;
	}

	private boolean captionInside;

	/**
	 * A short detail-font note in the top-right corner — "12/24". Takes the
	 * owned tick's spot: when both are set the corner text wins, the edge
	 * status already says "done".
	 */
	public V2Tile corner(String text)
	{
		this.corner = text == null || text.isEmpty() ? null : V2Label.detail(text);
		return this;
	}

	private OsrsLabel corner;

	/**
	 * The caption in a status colour — the clog grid's orange-until-done,
	 * green-when-complete. {@code DONE}, {@code ACTION} or {@code BLOCKED}
	 * only, the same guard as {@link V2Label#status} (§6 — colour is never
	 * decoration). Null returns the caption to its plain colours.
	 */
	public V2Tile captionStatus(java.awt.Color colour)
	{
		if (colour != null && colour != V2Tokens.DONE && colour != V2Tokens.ACTION
			&& colour != V2Tokens.BLOCKED)
		{
			throw new IllegalArgumentException(
				"captionStatus takes DONE, ACTION or BLOCKED — colour is never decoration");
		}
		this.captionStatus = colour;
		repaint();
		return this;
	}

	private java.awt.Color captionStatus;

	/**
	 * A member count in the top-left — "this tile stands for 4 variants".
	 * Drawn in {@code TEXT}, not a status colour: it is a quantity, and Luke
	 * asked for the light colour rather than orange when the Gear library
	 * first grew grouped tiles.
	 */
	public V2Tile badge(int count)
	{
		this.badge = count;
		badgeLabel.setText(String.valueOf(count));
		return this;
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

	/** The emblem arrives async (an {@code AsyncBufferedImage} landing after
	 *  the tile is built) — swap it in without rebuilding the grid. */
	public V2Tile emblem(java.awt.Image emblem)
	{
		this.emblem = emblem;
		repaint();
		return this;
	}

	/**
	 * What to draw when there is no emblem: a short code, centred. An honest
	 * "the art has not arrived" rather than an empty stone (§8) — the gear
	 * chart's own fallback, kept when its bespoke tile was retired.
	 */
	public V2Tile placeholder(String code)
	{
		this.placeholder = V2Label.centred(code);
		return this;
	}

	private OsrsLabel placeholder;

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


		// captionInside gives the emblem the band above the caption; the
		// outside caption keeps the whole art to itself (Luke, 2026-07-25 —
		// text on the tile pushed the icon off centre, so the icon's band
		// always excludes the text's)
		int artBand = captionInside && captionText != null
			? size - captionHeight() - V2Tokens.TIGHT : size;
		if (emblem != null)
		{
			g2.drawImage(emblem, (getWidth() - emblem.getWidth(null)) / 2,
				Math.max(V2Tokens.TIGHT, (artBand - emblem.getHeight(null)) / 2), null);
		}
		else if (placeholder != null)
		{
			placeholder.setSize(getWidth(), artBand);
			placeholder.paint(g2);
		}
		OsrsLabel text = caption();
		if (text != null)
		{
			int top = captionInside ? size - captionHeight() - V2Tokens.TIGHT
				: size + V2Tokens.TIGHT;
			text.setSize(getWidth(), captionHeight());
			g2.translate(0, top);
			text.setColor(captionStatus != null ? captionStatus
				: selected ? V2Tokens.HEADING : V2Tokens.TEXT);
			text.paint(g2);
			g2.translate(0, -top);
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
		if (corner != null)
		{
			Dimension ink = corner.getPreferredSize();
			corner.setSize(ink);
			g2.translate(getWidth() - ink.width - V2Tokens.TIGHT, V2Tokens.TIGHT);
			corner.paint(g2);
			g2.translate(-(getWidth() - ink.width - V2Tokens.TIGHT), -V2Tokens.TIGHT);
		}
		else if (owned)
		{
			BufferedImage tick = V2Sprites.get(theme, TICK);
			g2.drawImage(tick, getWidth() - tick.getWidth() - V2Tokens.TIGHT, V2Tokens.TIGHT, null);
		}
		if (badge > 1)
		{
			badgeLabel.setSize(getWidth(), V2Tokens.LINE_PITCH + V2Tokens.ROW);
			g2.translate(V2Tokens.ROW, V2Tokens.TIGHT);
			badgeLabel.paint(g2);
			g2.translate(-V2Tokens.ROW, -V2Tokens.TIGHT);
		}
		// the tile art stops at `size`; anything below it is the caption
	}

	/** Lazily wrapped against the width the grid actually gave this tile. */
	private OsrsLabel caption()
	{
		if (captionText == null)
		{
			return null;
		}
		int width = Math.max(1, getWidth() - 2 * V2Tokens.TIGHT);
		if (caption == null || captionWidth != width)
		{
			if (captionInside)
			{
				// on the art the caption is BOLD, or the stone eats it
				caption = OsrsLabel.wrapped(clamp(captionText, width), width,
					V2Tokens.TEXT, V2Tokens.headingFont());
			}
			else
			{
				caption = captionLines > 1
					? V2Label.wrappedCentred(clamp(captionText, width), width)
					: V2Label.centred(captionText);
			}
			captionWidth = width;
		}
		return caption;
	}

	/**
	 * Wrap, then keep at most {@link #captionLines} lines, ellipsizing the
	 * last. Without the clamp a long name wrapped to three or four lines and
	 * PAINTED all of them — the label draws what it holds, while the tile only
	 * reserves height for two, so the tail bled over the row beneath (caught in
	 * the Gear library render, 2026-07-26).
	 */
	private String clamp(String text, int width)
	{
		// measure with the font that will paint — bold wraps sooner
		OsrsLabel measured = captionInside
			? OsrsLabel.wrapped(text, width, V2Tokens.TEXT, V2Tokens.headingFont())
			: V2Label.wrappedCentred(text, width);
		String[] lines = measured.text().split("\n");
		if (lines.length <= captionLines)
		{
			return text;
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < captionLines; i++)
		{
			out.append(i > 0 ? "\n" : "").append(lines[i]);
		}
		// the label ellipsizes a line that is too WIDE; too MANY lines is this
		// method's problem, so the mark is added here
		return out.append("…").toString();
	}

	/** How tall the caption band is — one line, or as many as it wraps to. */
	private int captionHeight()
	{
		OsrsLabel text = caption();
		if (text == null)
		{
			return 0;
		}
		int lines = Math.min(captionLines, text.text().split("\n").length);
		return lines * V2Tokens.LINE_PITCH + 5;
	}

	/** Left-aligned so the count sits in the corner, not the middle. */
	private final OsrsLabel badgeLabel = V2Label.detail("").leftAligned();

	@Override
	public Dimension getPreferredSize()
	{
		// the caption sits UNDER the art, so it costs height, not centring —
		// unless it is INSIDE, where the tile is exactly its art
		return new Dimension(width, captionText == null || captionInside ? size
			: size + V2Tokens.TIGHT + captionLines * V2Tokens.LINE_PITCH + V2Tokens.ROW);
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
