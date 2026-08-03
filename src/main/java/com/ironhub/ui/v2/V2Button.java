package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/**
 * The text button: the metal frame at rest, the filled Card on hover, and the
 * metal frame's OWN pressed art while held or selected.
 *
 * <p>Held used to be the chip surface, a third family borrowed from
 * {@code button.png}. It read as a different control mid-press and its rounded
 * bottom edge fell outside the 28px button, so the lower border simply was not
 * there (Luke, 2026-07-25: "uses a different sprite and clips so no lower half
 * is showing"). The metal family ships {@code _hovered} art, and per
 * {@code V2Tokens.HIGHLIGHT} the curated {@code _hovered} sprites ARE the
 * pressed look — so the pressed state is now the rest state's own, which is
 * both the system's rule and a silhouette that cannot clip.
 *
 * <p>This replaced {@code regular_large}, which has no second state of its
 * own in the set — a button with no feedback at all was the honest answer to
 * the art available, but not a good one on a whole tab.
 */
public class V2Button extends JPanel
{
	private final OsrsTheme theme;
	private final NineSlice rest = V2Tokens.metal();
	private final NineSlice hovered = V2Tokens.card();
	private final NineSlice pressed = V2Tokens.metal().variant("_hovered");
	private final OsrsLabel label;
	private boolean hover;
	private boolean down;

	public V2Button(OsrsTheme theme, String text, Runnable onPress)
	{
		this.theme = theme;
		this.label = V2Label.centred(text);
		setOpaque(false);
		setLayout(new BorderLayout());
		setAlignmentX(LEFT_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add(label, BorderLayout.CENTER);
		addMouseListener(new MouseAdapter()
		{
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
				down = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				down = true;
				repaint();
				if (onPress != null)
				{
					onPress.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				down = false;
				repaint();
			}
		});
	}

	/** Recolour the label — a saved-green confirmation, a blocked warning.
	 *  Status colours only; the button art itself never changes. */
	public V2Button labelColor(java.awt.Color color)
	{
		label.setColor(color);
		return this;
	}

	public String text()
	{
		return label.text();
	}

	/** Held down, or acting as a selected toggle. */
	public void setPressed(boolean pressed)
	{
		if (down != pressed)
		{
			down = pressed;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		NineSlice art = down ? pressed : hover ? hovered : rest;
		art.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
		super.paintComponent(g);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(label.getPreferredSize().width + 4 * V2Tokens.PAD,
			V2Tokens.BUTTON_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, V2Tokens.BUTTON_HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(2 * rest.inset(), V2Tokens.BUTTON_HEIGHT);
	}
}
