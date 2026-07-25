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
	 * A NON-CLICKABLE surface — the game's notched slab. Section headings,
	 * static blocks, anything the player cannot press (Luke, 2026-07-25).
	 * Keeping it visually distinct from the button family is the point: a
	 * surface that looks pressable and isn't is the worst kind of drift.
	 */
	public static V2Surface slab(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.slab());
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
		if (wellArt != null)
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
