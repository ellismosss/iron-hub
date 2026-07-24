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
 * A tab in the game's bank-tag shape: fixed 39x40 art with an emblem on it,
 * active or not. Its second state is named {@code _active} rather than
 * {@code _selected}, which is why it isn't a {@link V2SpriteButton} — the
 * probe there is deliberately literal, since guessing at state names is how
 * a missing sprite turns into a silently dead control.
 */
public class V2Tab extends JComponent
{
	private static final String PLAIN = "ui/buttons/tag_tab";
	private static final String ACTIVE = "ui/buttons/tag_tab_active";

	private final OsrsTheme theme;
	private final BufferedImage emblem;
	private boolean active;

	public V2Tab(OsrsTheme theme, BufferedImage emblem, Runnable onPress)
	{
		this.theme = theme;
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
		});
	}

	public V2Tab active(boolean active)
	{
		this.active = active;
		repaint();
		return this;
	}

	public boolean isActive()
	{
		return active;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		BufferedImage art = V2Sprites.get(theme, active ? ACTIVE : PLAIN);
		g.drawImage(art, 0, 0, null);
		if (emblem != null)
		{
			g.drawImage(emblem, (art.getWidth() - emblem.getWidth()) / 2,
				(art.getHeight() - emblem.getHeight()) / 2, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		V2Sprites.Meta meta = V2Sprites.meta(PLAIN);
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
