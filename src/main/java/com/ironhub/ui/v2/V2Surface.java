package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * A container drawn from one of the curated slice families — the Card
 * (filled) and the Well (border only, content on the panel backing).
 *
 * <p>Content sits {@code SLICE_INSET + PAD} from the outer edge: the 9px art
 * inset is structural (it is where the bevel lives) and PAD is the spacing on
 * top of it. That sum is the same on every surface in the plugin, which is
 * the whole point — a Card in Goals and a Card in Bank have identical
 * geometry without either module deciding anything.
 *
 * <p>Hover is offered only where the art has a {@code _hovered} sprite, and
 * both families do. A surface that isn't interactive never asks for it.
 */
public class V2Surface extends JPanel
{
	private final OsrsTheme theme;
	private final NineSlice slice;
	/** Set instead of {@link #slice} for the well, whose art is not a
	 *  nine-slice — it is the field texture, gridded in both directions. */
	private final V2Well wellArt;
	private NineSlice hovered;
	private boolean hover;
	/** Painted by the nav row's own painter rather than from a slice. */
	private boolean navTile;
	/** Painted by a Swing Border rather than from a slice — the Frame and the
	 *  Slab. */
	private javax.swing.border.Border frameBorder;
	/** The Slab's interior, painted under {@link #frameBorder}. Null for the
	 *  Frame, which is border-only. */
	private java.awt.Paint slabFill;
	/**
	 * A slice cannot render below twice its corner size — the corners overlap
	 * and the edges get a negative span. The panel frame's corners are 32px,
	 * so an inventory frame shorter than 64 draws as rubble, which is what
	 * "the inventory frame is still completely busted" was (Luke, 2026-07-25:
	 * it had been handed 47px). Every surface now refuses to go below its own
	 * art's floor.
	 */
	private final int minimumHeight;

	/**
	 * A NON-CLICKABLE surface — section headings, static blocks, anything the
	 * player cannot press. It is a NAV TILE, as wide as it is given (Luke,
	 * 2026-07-25), replacing the thin tan line it wore before.
	 *
	 * <p>It shares {@code StoneNavButton.paintSlab} with the nav row rather
	 * than copying its look, so the two can never drift apart. That painter is
	 * hand-drawn rather than sprite art, which is the one thing on this page
	 * V2 has not taken over yet — when the nav tile becomes art, this follows
	 * it for free, and that is exactly why it borrows instead of reproducing.
	 */
	public static V2Surface tile(OsrsTheme theme)
	{
		// vertical is TIGHTER than horizontal (Luke, 2026-07-25): the chamfer
		// eats the corners, not the top and bottom edges, so 12px of air above
		// and below a one-line row was padding nothing was asking for
		V2Surface surface = new V2Surface(theme, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD);
		surface.setBorder(new EmptyBorder(V2Tokens.ROW, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD,
			V2Tokens.ROW, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD));
		return surface;
	}

	/**
	 * The game's thin side-panel frame — what the "Iron Hub" header and the nav
	 * row sit inside in the real panel (Luke, 2026-07-25). Imported from
	 * {@code StoneFrame} rather than reproduced, on the same terms as the Tile:
	 * it is hand-painted from an 8x8 pixel stamp in theme colours, not sprite
	 * art, and when it becomes art this follows it.
	 *
	 * <p>Border only — the interior is left on the panel backing, and the 8px
	 * corner chamfer deliberately cuts through to whatever hosts it.
	 */
	public static V2Surface frame(OsrsTheme theme)
	{
		V2Surface surface = new V2Surface(theme,
			V2Tokens.STONE_FRAME_INSET + V2Tokens.PAD);
		surface.navTile = false;
		surface.frameBorder = new com.ironhub.ui.osrs.StoneFrame(theme,
			V2Tokens.dimEdge(theme));
		return surface;
	}

	/**
	 * The engraved, corner-notched box — {@code StoneButton}'s design, and the
	 * look every V1 stat box and stone panel wears (Luke, 2026-07-25). The one
	 * surface in the V1 skin that V2 had no equivalent for.
	 *
	 * <p>Fill AND border, which is what separates it from the Frame — that one
	 * is border-only. The fill is the SAME Card grain the Tile and the Card
	 * wear (Luke, 2026-07-25), so the three read as one family and only their
	 * edges differ: the Slab notches, the Tile chamfers, the Card is sprite
	 * art. A flat {@code boxFill} was the first pass and sat visibly apart
	 * from its neighbours.
	 *
	 * <p>Borrowed from {@code StoneBorder} rather than reproduced, on the same
	 * terms as the Tile and the Frame — it is hand-painted from theme colours,
	 * not sprite art, and when it becomes art this follows it for free.
	 *
	 * <p>Not to be confused with {@code V2Tokens.slab()}, which is the tan
	 * nine-slice a progress bar is framed in. Different thing, unlucky name —
	 * that one has no callers left and should take the {@code barFrame} name it
	 * is actually used under.
	 */
	public static V2Surface slab(OsrsTheme theme)
	{
		// the Tile's content inset, NOT StoneBorder's own 4x6 pad: a surface in
		// this set stands its content off by the same distance whatever edge it
		// wears (Luke, 2026-07-25), and the border's cramped pad made the Slab
		// read as a different system beside the Tile it sits next to
		V2Surface surface = new V2Surface(theme,
			V2Tokens.NAV_TILE_INSET + V2Tokens.PAD);
		// same split as the Tile: the engraved notch is a CORNER feature, so
		// the vertical inset can come back down (Luke, 2026-07-25)
		surface.setBorder(new EmptyBorder(V2Tokens.ROW, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD,
			V2Tokens.ROW, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD));
		surface.navTile = false;
		surface.slabFill = V2Sprites.grain(theme);
		surface.frameBorder = new com.ironhub.ui.osrs.StoneBorder(theme, theme.background);
		return surface;
	}

	/**
	 * The Tile's surface, painted straight onto a Graphics — for things that
	 * wear it without being containers, {@link V2Tile} above all. Shared rather
	 * than reproduced so the clickable tile and the static one can never drift
	 * apart, which is the entire point of this system.
	 *
	 * <p>Selected changes the BORDER, not the highlight: it keeps the same Card
	 * grain and takes the brighter bevel. A flat {@code selectFill} was the
	 * first attempt and read far too hot beside its neighbours (Luke,
	 * 2026-07-25, "the highlight is too strong when they are pressed" — and in
	 * his vocabulary the highlight is the fill). The lift comes from the wash
	 * instead, which is a fifth of the weight.
	 */
	public static void paintTile(Graphics2D g, OsrsTheme theme, int w, int h, boolean selected)
	{
		com.ironhub.ui.osrs.StoneNavButton.paintSlab(g, theme, w, h,
			V2Sprites.grain(theme),
			selected ? theme.selectEdge : V2Tokens.dimEdge(theme));
	}

	/**
	 * The pointer wash over a Tile, clipped to the chamfered silhouette.
	 *
	 * <p>A {@code fillRect} washes the square the tile is drawn in, so it
	 * spilled over all four notched corners onto the panel behind (Luke,
	 * 2026-07-25: "the hover highlight isn't clipping to the inside of the
	 * tiles"). {@code paintSilhouette} fills the tile's actual shape.
	 */
	public static void washTile(Graphics2D g, int w, int h)
	{
		com.ironhub.ui.osrs.StoneNavButton.paintSilhouette(g, w, h, V2Tokens.HIGHLIGHT);
	}

	/** The same clipped wash, dark — an unavailable Tile sinks into the panel
	 *  rather than merely wearing a grey edge. */
	public static void shadeTile(Graphics2D g, int w, int h)
	{
		com.ironhub.ui.osrs.StoneNavButton.paintSilhouette(g, w, h, V2Tokens.SHADOW);
	}

	/**
	 * The chip surface — {@code button.png} sliced, the rounded rectangle a
	 * {@link V2ChipRow} chip wears. For a one-off notice that needs to read as
	 * a small transient thing rather than as a section (Luke, 2026-07-25: the
	 * "Routes updated" banner). A row of choices is still {@code V2ChipRow};
	 * this is the surface on its own.
	 */
	public static V2Surface chip(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.chip());
	}

	/** Filled surface: cards, tooltips, and the button's hovered state. */
	public static V2Surface card(OsrsTheme theme)
	{
		V2Surface surface = new V2Surface(theme, V2Tokens.card());
		// the 9px art inset is STRUCTURAL — it is where the bevel lives, and
		// content inside it overlaps the art. What comes off is the spacing on
		// top of it: PAD to TIGHT vertically, ROW horizontally (Luke,
		// 2026-07-25). The Tile and Slab took the same trim.
		surface.setBorder(new EmptyBorder(V2Tokens.SLICE_INSET + V2Tokens.TIGHT,
			V2Tokens.SLICE_INSET + V2Tokens.ROW,
			V2Tokens.SLICE_INSET + V2Tokens.TIGHT,
			V2Tokens.SLICE_INSET + V2Tokens.ROW));
		return surface;
	}

	/** The sunken well — framed lists, fields, tables. The same texture the
	 *  game puts behind its own search boxes. */
	public static V2Surface well(OsrsTheme theme)
	{
		V2Surface surface = new V2Surface(theme, null, V2Tokens.well(),
			V2Well.CAP + V2Tokens.PAD);
		return surface;
	}

	/**
	 * The game's stone-bar frame. Reserved for the Inventory view under Gear
	 * &amp; Combat (Luke, 2026-07-25) — it is the frame the game itself puts
	 * around an inventory, and using it for ordinary sections made every
	 * section look like one. Its pieces are 32px canvases with the bar in
	 * rows 14..19 and transparent padding around it, so content clears the
	 * ART at 20px, not at the 32px slice inset.
	 */
	public static V2Surface inventoryFrame(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.panel(), V2Tokens.PANEL_FRAME_INSET);
	}

	public V2Surface(OsrsTheme theme, NineSlice slice)
	{
		this(theme, slice, slice.inset() + V2Tokens.PAD);
	}

	public V2Surface(OsrsTheme theme, NineSlice slice, int contentInset)
	{
		this(theme, slice, null, contentInset);
	}

	/** The Tile: no slice and no well, painted by the nav row's own painter.
	 *  See {@link #tile}. */
	private V2Surface(OsrsTheme theme, int contentInset)
	{
		this(theme, null, null, contentInset);
		this.navTile = true;
	}

	private V2Surface(OsrsTheme theme, NineSlice slice, V2Well wellArt, int contentInset)
	{
		this.minimumHeight = slice == null ? 0 : 2 * slice.inset();
		this.theme = theme;
		this.slice = slice;
		this.wellArt = wellArt;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(new EmptyBorder(contentInset, contentInset, contentInset, contentInset));
	}

	/** Light up on hover, using the art's own hovered state. Throws if the
	 *  family has none — the system never invents one (§8). */
	public V2Surface hoverable()
	{
		hovered = slice == null ? null : slice.variant("_hovered");
		listenForHover(this);
		return this;
	}

	/**
	 * Hover as the SUBTLE inset wash instead of the art's hovered state —
	 * for a pressable surface whose hovered art means "pressed in", so the
	 * pointer alone must not light it (Luke, 2026-07-27, the clog's
	 * Easiest-next-slots card: wash on hover, {@link #setLit} while open).
	 */
	public V2Surface washHoverable()
	{
		washOnHover = true;
		listenForHover(this);
		return this;
	}

	private boolean washOnHover;

	/**
	 * Watch this component and everything inside it, now and later.
	 *
	 * <p>A listener on the surface alone is not enough: Swing sends the
	 * container a {@code mouseExited} the instant the pointer crosses onto a
	 * child, so a Tile carrying a label — which every tile button is — goes
	 * dark under the pointer. Every descendant reports instead, and an exit is
	 * believed only once the pointer has left the surface's own bounds.
	 *
	 * <p>The {@code ContainerListener} covers children added AFTER
	 * {@code hoverable()}, so a caller never has to build in a particular order
	 * to get a working hover.
	 */
	private void listenForHover(Component target)
	{
		target.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				if (!hover)
				{
					hover = true;
					repaint();
				}
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				java.awt.Point p = javax.swing.SwingUtilities.convertPoint(
					e.getComponent(), e.getPoint(), V2Surface.this);
				if (contains(p))
				{
					return; // onto a child, still inside
				}
				hover = false;
				repaint();
			}
		});
		if (!(target instanceof java.awt.Container))
		{
			return;
		}
		java.awt.Container container = (java.awt.Container) target;
		for (Component child : container.getComponents())
		{
			listenForHover(child);
		}
		container.addContainerListener(new java.awt.event.ContainerAdapter()
		{
			@Override
			public void componentAdded(java.awt.event.ContainerEvent e)
			{
				listenForHover(e.getChild());
			}
		});
	}

	/** Paint the hovered art regardless of the pointer — how a selected chip
	 *  reads, alongside its label going HEADING orange (§8). Lit is its OWN
	 *  flag, not the pointer's: a hover listener's exit must never unlight a
	 *  lit surface, and a lit surface can still take the pointer wash
	 *  (Luke, 2026-07-27). */
	public void setLit(boolean lit)
	{
		if (hovered == null && slice != null)
		{
			hovered = slice.variant("_hovered");
		}
		if (this.lit != lit)
		{
			this.lit = lit;
			repaint();
		}
	}

	private boolean lit;

	/**
	 * A Tile that presses — a button wearing the Tile surface rather than the
	 * metal frame (Luke, 2026-07-25). Nothing here is new art: the surface, the
	 * chamfer and the pointer wash are the Tile's own, and this adds the hand
	 * cursor, the hover and the click.
	 *
	 * @param compact true trims the Tile's content inset to its chamfer, for a
	 *                button that is one line of text rather than a block
	 */
	public V2Surface pressable(Runnable onPress)
	{
		return pressable(onPress, false);
	}

	public V2Surface pressable(Runnable onPress, boolean compact)
	{
		if (compact)
		{
			int inset = V2Tokens.NAV_TILE_INSET;
			setBorder(new EmptyBorder(V2Tokens.TIGHT, inset, V2Tokens.TIGHT, inset));
		}
		hoverable();
		// without the relay a press on the surface's own LABEL never reaches
		// the listener below — deepest-component dispatch (MouseRelay)
		MouseRelay.install(this);
		setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		addMouseListener(new java.awt.event.MouseAdapter()
		{
			// mousePressed, not mouseClicked: a click that drifts a pixel
			// between press and release never fires
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				if (onPress != null)
				{
					onPress.run();
				}
			}
		});
		return this;
	}

	/** Add a child and the standard gap beneath it. */
	public V2Surface stack(Component child, int gapBelow)
	{
		add(child);
		add(V2Layout.gap(gapBelow));
		return this;
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
		if (frameBorder != null)
		{
			if (slabFill != null)
			{
				// fill first, border second: the border's corner stamps paint
				// the notches through to the backing, so a fill drawn after
				// would square them off again
				((Graphics2D) g).setPaint(slabFill);
				g.fillRect(0, 0, getWidth(), getHeight());
				if (hover)
				{
					// BETWEEN the two, which is what clips it: the wash lands
					// on the fill, then the border repaints the four notches
					// over it (§8 — a wash never spills past the art)
					g.setColor(V2Tokens.HIGHLIGHT);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
			}
			frameBorder.paintBorder(this, g, 0, 0, getWidth(), getHeight());
		}
		else if (navTile)
		{
			// the nav tile's BORDER, with no HIGHLIGHT — its body is left on
			// the panel backing (Luke, 2026-07-25). In Luke's vocabulary the
			// highlight is a surface's internal fill and the border is its
			// outline; this surface has the second and not the first.
			// HIGHLIGHT (fill) lifted half way to the nav stone's, BORDER the
			// same dimmed edge as the Frame (Luke, 2026-07-25) — the two
			// surfaces stack, so a brighter border on the Tile read as two
			// different systems, while a flat fill left it sunk into the Frame
			paintTile((Graphics2D) g, theme, getWidth(), getHeight(), false);
			if (hover)
			{
				washTile((Graphics2D) g, getWidth(), getHeight());
			}
		}
		else if (wellArt != null)
		{
			wellArt.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
		}
		else
		{
			NineSlice art = (lit || hover) && hovered != null ? hovered : slice;
			art.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
			// the wash rides the POINTER, on plain and lit art alike — a
			// lit (pressed-in) surface still shows it under the pointer
			// (Luke, 2026-07-27)
			if (washOnHover && hover)
			{
				g.setColor(V2Tokens.HIGHLIGHT);
				g.fillRect(2, 2, getWidth() - 4, getHeight() - 4);
			}
		}
		super.paintComponent(g);
	}

	/**
	 * A surface is as tall as its content and as wide as it is given. Without
	 * this a BoxLayout stretches the last child to fill the scroll viewport,
	 * which is how a Card ends up 400px tall with 20px of text in it.
	 */
	@Override
	public Dimension getPreferredSize()
	{
		Dimension pref = super.getPreferredSize();
		return new Dimension(pref.width, Math.max(pref.height, minimumHeight));
	}

	@Override
	public Dimension getMinimumSize()
	{
		Dimension min = super.getMinimumSize();
		return new Dimension(min.width, Math.max(min.height, minimumHeight));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
