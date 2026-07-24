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
	private NineSlice hovered;
	private boolean hover;

	/** Filled surface: sections, tiles, tooltips. */
	public static V2Surface card(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.card());
	}

	/** Border-only recess: framed lists, fields. Content reads on the panel
	 *  backing, so a Well never fights the content the way a second fill does. */
	public static V2Surface well(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.well());
	}

	/**
	 * The outer panel border — the game's stone bars. Its pieces are 32px
	 * canvases with the bar sitting in rows 14..19 and transparent padding
	 * around it, so content clears the ART at 20px, not at the 32px slice
	 * inset (which would eat a third of a 225px panel).
	 */
	public static V2Surface frame(OsrsTheme theme)
	{
		return new V2Surface(theme, V2Tokens.panel(), V2Divider.BAR_TOP + V2Divider.BAR_HEIGHT);
	}

	public V2Surface(OsrsTheme theme, NineSlice slice)
	{
		this(theme, slice, slice.inset() + V2Tokens.PAD);
	}

	public V2Surface(OsrsTheme theme, NineSlice slice, int contentInset)
	{
		this.theme = theme;
		this.slice = slice;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(new EmptyBorder(contentInset, contentInset, contentInset, contentInset));
	}

	/** Light up on hover, using the art's own hovered state. Throws if the
	 *  family has none — the system never invents one (§8). */
	public V2Surface hoverable()
	{
		hovered = slice.variant("_hovered");
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
		if (hovered == null)
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
		NineSlice art = hover && hovered != null ? hovered : slice;
		art.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
		super.paintComponent(g);
	}

	/**
	 * A surface is as tall as its content and as wide as it is given. Without
	 * this a BoxLayout stretches the last child to fill the scroll viewport,
	 * which is how a Card ends up 400px tall with 20px of text in it.
	 */
	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
