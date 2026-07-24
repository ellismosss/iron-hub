package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * A display-only icon: the tick, the cross, the padlock, a chevron, a sort
 * arrow, a star. No press, no hover — a glyph reports, it does not act. The
 * moment one needs to be clickable it is a {@link V2SpriteButton}, which is
 * the atom that knows about states.
 *
 * <p>Status glyphs carry their meaning in the art: the tick IS green and the
 * cross IS red, and those are the two colours §6 sampled its DONE and
 * BLOCKED from. A glyph therefore never needs tinting, and cannot drift away
 * from the text beside it.
 */
public class V2Glyph extends JComponent
{
	public static final String TICK = "ui/ticks/checkmark_small";
	public static final String TICK_LARGE = "ui/ticks/checkmark_large";
	public static final String CROSS = "ui/ticks/red_cross_small";
	public static final String CROSS_LARGE = "ui/ticks/red_cross_large";
	public static final String LOCK = "icons/padlock";
	public static final String STAR = "icons/star/star";
	public static final String STAR_MEMBER = "icons/star/star_member";
	public static final String SORT_ASCENDING = "ui/arrows/list_sorting_arrow_ascending";
	public static final String SORT_DESCENDING = "ui/arrows/list_sorting_arrow_descending";

	/** Collapsed — press to open. */
	public static final String CHEVRON_CLOSED = "icons/chevron/gray_right_single";
	/** Expanded. */
	public static final String CHEVRON_OPEN = "icons/chevron/gray_down_single";

	private final OsrsTheme theme;
	private String key;

	public V2Glyph(OsrsTheme theme, String key)
	{
		this.theme = theme;
		this.key = key;
		if (!V2Sprites.has(key))
		{
			throw new IllegalArgumentException("no V2 sprite '" + key + "'");
		}
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Swap the art in place — a row's status changing must not rebuild it. */
	public void setGlyph(String key)
	{
		this.key = key;
		repaint();
	}

	/**
	 * A chevron by colour and direction: {@code chevron("green", "down")}.
	 * The curated set carries gray, green, red and yellow in four directions
	 * and three weights (single, double, stop) — a chevron that isn't in it
	 * throws rather than rendering nothing.
	 */
	public static String chevron(String colour, String direction)
	{
		return "icons/chevron/" + colour + "_" + direction + "_single";
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		BufferedImage art = V2Sprites.get(theme, key);
		g.drawImage(art, 0, (getHeight() - art.getHeight()) / 2, null);
	}

	@Override
	public Dimension getPreferredSize()
	{
		V2Sprites.Meta meta = V2Sprites.meta(key);
		return new Dimension(meta.width(), meta.height());
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
