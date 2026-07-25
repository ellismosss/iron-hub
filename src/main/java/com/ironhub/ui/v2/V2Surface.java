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
	/** Painted by a Swing Border rather than from a slice — the Frame. */
	private javax.swing.border.Border frameBorder;
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
		return new V2Surface(theme, V2Tokens.NAV_TILE_INSET + V2Tokens.PAD);
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
		java.awt.image.BufferedImage grain = V2Sprites.cardInterior(theme);
		com.ironhub.ui.osrs.StoneNavButton.paintSlab(g, theme, w, h,
			new java.awt.TexturePaint(grain,
				new java.awt.Rectangle(0, 0, grain.getWidth(), grain.getHeight())),
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

	/** Filled surface: cards, tooltips, and the button's hovered state. */
	public static V2Surface card(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.card());
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
		addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				hover = false;
				repaint();
			}
		});
		return this;
	}

	/** Paint the hovered art regardless of the pointer — how a selected chip
	 *  reads, alongside its label going HEADING orange (§8). */
	public void setLit(boolean lit)
	{
		if (hovered == null && slice != null)
		{
			hovered = slice.variant("_hovered");
		}
		if (hover != lit)
		{
			hover = lit;
			repaint();
		}
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
		}
		else if (wellArt != null)
		{
			wellArt.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
		}
		else
		{
			NineSlice art = hover && hovered != null ? hovered : slice;
			art.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
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
