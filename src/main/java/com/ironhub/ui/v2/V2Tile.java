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
		(selected || hover ? lit : plain).paint(g2, theme, 0, 0, getWidth(), getHeight());

		int captionHeight = caption == null ? 0 : V2Tokens.LINE_PITCH;
		if (emblem != null)
		{
			g2.drawImage(emblem, (getWidth() - emblem.getWidth()) / 2,
				(getHeight() - captionHeight - emblem.getHeight()) / 2, null);
		}
		if (caption != null)
		{
			caption.setSize(getWidth() - 2 * V2Tokens.TIGHT, captionHeight + 5);
			g2.translate(V2Tokens.TIGHT, getHeight() - captionHeight - V2Tokens.ROW);
			caption.setColor(selected ? V2Tokens.HEADING : V2Tokens.TEXT);
			caption.paint(g2);
			g2.translate(-V2Tokens.TIGHT, -(getHeight() - captionHeight - V2Tokens.ROW));
		}
		if (owned)
		{
			BufferedImage tick = V2Sprites.get(theme, TICK);
			g2.drawImage(tick, getWidth() - tick.getWidth() - V2Tokens.TIGHT, V2Tokens.TIGHT, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(size, size);
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
