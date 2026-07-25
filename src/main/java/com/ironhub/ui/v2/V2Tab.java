package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * A tab with an emblem on it. Three styles, all from the game (Luke added
 * them 2026-07-25 so he can pick a favourite):
 *
 * <ul>
 * <li>{@link Style#TAB} — the bank tab, 41x40, with rest / hover / selected /
 * empty art
 * <li>{@link Style#TAG} — the bank TAG tab, 39x40, rest and active
 * <li>{@link Style#STONE} — the side-panel nav stone, 33x36, rest and
 * selected. This is also the Checklist's border art.
 * </ul>
 *
 * <p>Each style offers exactly the states its own sprites have: TAB is the
 * only one with a distinct hover, so the other two take the highlight wash.
 */
public class V2Tab extends JComponent
{
	/** Which family of tab art to wear. */
	public enum Style
	{
		TAB("ui/tabs/tab", "ui/tabs/tab_selected", "ui/tabs/tab_hovered", "ui/tabs/tab_empty"),
		TAG("ui/tabs/tag_tab", "ui/tabs/tag_tab_active", null, null),
		STONE("ui/tabs/tab_stone_middle", "ui/tabs/tab_stone_middle_selected", null, null);

		final String rest;
		final String active;
		final String hovered;
		final String empty;

		Style(String rest, String active, String hovered, String empty)
		{
			this.rest = rest;
			this.active = active;
			this.hovered = hovered;
			this.empty = empty;
		}
	}

	private final OsrsTheme theme;
	private final Style style;
	private final BufferedImage emblem;
	private boolean active;
	private boolean empty;
	private boolean hover;

	public V2Tab(OsrsTheme theme, BufferedImage emblem, Runnable onPress)
	{
		this(theme, Style.TAB, emblem, onPress);
	}

	public V2Tab(OsrsTheme theme, Style style, BufferedImage emblem, Runnable onPress)
	{
		this.theme = theme;
		this.style = style;
		this.emblem = emblem;
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

	public V2Tab active(boolean active)
	{
		this.active = active;
		repaint();
		return this;
	}

	/** The bank tab's "no items yet" art. Only Style.TAB has it. */
	public V2Tab empty(boolean empty)
	{
		this.empty = empty && style.empty != null;
		repaint();
		return this;
	}

	public boolean isActive()
	{
		return active;
	}

	public Style style()
	{
		return style;
	}

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
		String key = active ? style.active
			: empty ? style.empty
			: hover && style.hovered != null ? style.hovered
			: style.rest;
		BufferedImage art = V2Sprites.get(theme, key);
		g.drawImage(art, 0, 0, null);
		if (hover && !active && style.hovered == null)
		{
			g.setColor(V2Tokens.HIGHLIGHT);
			g.fillRect(0, 0, art.getWidth(), art.getHeight());
		}
		if (emblem != null)
		{
			g.drawImage(emblem, (art.getWidth() - emblem.getWidth()) / 2,
				(art.getHeight() - emblem.getHeight()) / 2, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		V2Sprites.Meta meta = V2Sprites.meta(style.rest);
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
