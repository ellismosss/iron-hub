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
 * The text button, in three states drawn from three curated families
 * (Luke, 2026-07-25): the Well recess at rest, the filled Card on hover, and
 * the chip surface while held or selected. It reads as rising out of the
 * panel as you engage with it, and every state is real art.
 *
 * <p>This replaced {@code regular_large}, which has no second state of its
 * own in the set — a button with no feedback at all was the honest answer to
 * the art available, but not a good one on a whole tab.
 */
public class V2Button extends JPanel
{
	private final OsrsTheme theme;
	private final NineSlice rest = V2Tokens.well();
	private final NineSlice hovered = V2Tokens.card();
	private final NineSlice pressed = V2Tokens.chip();
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
