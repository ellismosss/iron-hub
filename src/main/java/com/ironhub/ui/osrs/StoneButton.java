package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;

/**
 * The default button: the same corner-notched box the stat boxes use, with
 * hover and press fills so it answers the pointer (per Luke — square stone
 * buttons are reserved for navigation, everything else is a notched box).
 *
 * <p>The game has no pointer and therefore no hover art to sample: MYSTIC's
 * hover is the pack's own sprite (flattened from its top-lit gradient), while
 * STONE's is derived from its sampled fill. See OsrsTheme.
 */
public class StoneButton extends StonePanel
{
	private final Runnable onClick;
	private final OsrsLabel label;
	private boolean hover;
	private boolean pressed;

	public StoneButton(OsrsTheme theme, String text, Runnable onClick)
	{
		this(theme, theme.background, text, onClick);
	}

	public StoneButton(OsrsTheme theme, Color outside, String text, Runnable onClick)
	{
		super(theme, outside);
		this.onClick = onClick;
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add(Box.createHorizontalGlue());
		label = OsrsLabel.label(text);
		add(label);
		add(Box.createHorizontalGlue());

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				repaintFill();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				pressed = false;
				repaintFill();
			}

			// mousePressed, never mouseClicked — clicked drops the event if
			// the pointer drifts a pixel between press and release
			@Override
			public void mousePressed(MouseEvent e)
			{
				pressed = true;
				repaintFill();
				if (onClick != null)
				{
					onClick.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				pressed = false;
				repaintFill();
			}
		});
	}

	/** Recolour the button's text (e.g. green = saved) — chainable. */
	public StoneButton labelColor(Color color)
	{
		label.setColor(color);
		return this;
	}

	/** Test seam: the fill a given pointer state paints. */
	public Color fillFor(boolean hovering, boolean down)
	{
		return down ? theme.pressFill : hovering ? theme.hoverFill : theme.boxFill;
	}

	private void repaintFill()
	{
		setBackground(fillFor(hover, pressed));
		repaint();
	}

	@Override
	public Dimension getMaximumSize()
	{
		// full-width by default, but an explicitly-set maximum must win —
		// overriding the getter unconditionally silently ate setMaximumSize
		return isMaximumSizeSet() ? super.getMaximumSize()
			: new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
