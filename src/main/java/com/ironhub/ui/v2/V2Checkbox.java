package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The checkbox — one family for the whole plugin (Luke's pick), the bordered
 * 18px one, because it is the only curated family carrying the states this
 * plugin actually needs: <b>locked</b> (a gated diary task) and
 * <b>disabled</b> (a run superseded by a bigger one) alongside off and on.
 *
 * <p>The whole row is the press target, label included. A 18px hit area is a
 * miss waiting to happen at this panel width, and a label you can't click
 * reads as decoration.
 *
 * <p><b>The highlight is the box's alone</b> (Luke, 2026-07-25). The hit area
 * and the lit area are deliberately different sizes: lighting the box from
 * anywhere in the row made a list of them flicker as the pointer crossed it.
 * So the box carries its own hover listener — and its own press listener too,
 * because a child with a MouseListener stops forwarding to its parent, and
 * without that the one place you would actually aim at is the one place that
 * would not toggle.
 */
public class V2Checkbox extends JPanel
{
	private static final String BOX = "ui/checkbox/square_bordered_checkbox";

	/** What the box is saying. LOCKED and DISABLED are not clickable. */
	public enum State
	{
		OFF(""),
		ON("_checked"),
		/** Gated — the reason belongs in the tooltip. */
		LOCKED("_locked"),
		DISABLED("_disabled"),
		DISABLED_ON("_disabled_checked");

		private final String suffix;

		State(String suffix)
		{
			this.suffix = suffix;
		}
	}

	private final Box box;
	/** Null when the caller asked for the box alone. */
	private final OsrsLabel label;
	private final Runnable onToggle;
	private State state;
	private boolean hover;

	/**
	 * @param text the row's label, or null for the BOX ALONE — a row that
	 *             lays itself out (the farm-run picker positions arrows, box,
	 *             sprite and name by hand) wants the art without the row
	 */
	public V2Checkbox(OsrsTheme theme, String text, boolean checked, Runnable onToggle)
	{
		this.state = checked ? State.ON : State.OFF;
		this.onToggle = onToggle;
		this.box = new Box(theme);
		this.label = text == null ? null : V2Label.body(text);
		setOpaque(false);
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		add(box);
		if (label != null)
		{
			add(javax.swing.Box.createHorizontalStrut(V2Tokens.TIGHT + V2Tokens.ROW));
			add(label);
			add(javax.swing.Box.createHorizontalGlue());
		}
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// the row presses but never lights
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				press();
			}
		});
		// the box lights, and presses on its own behalf
		box.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				box.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				box.repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				press();
			}
		});
	}

	/** Toggle, unless the state says otherwise. */
	private void press()
	{
		if (onToggle != null && state != State.LOCKED
			&& state != State.DISABLED && state != State.DISABLED_ON)
		{
			onToggle.run();
		}
	}

	/** Test seam: is the box lit? */
	public boolean highlighted()
	{
		return hover;
	}

	/**
	 * Colour the label as a STATUS the caller owns — the dailies scale (green
	 * claimable, orange short, faint done). {@link #state} keeps its own
	 * colouring for the plain case; call this after it, not before.
	 */
	public V2Checkbox labelColor(java.awt.Color color)
	{
		if (label != null)
		{
			label.setColor(color);
		}
		return this;
	}

	/** A trailing icon after the label — the dailies wilderness skull. */
	public V2Checkbox badge(javax.swing.Icon icon)
	{
		javax.swing.JLabel holder = new javax.swing.JLabel(icon); // v2-exempt: an icon holder, not text
		holder.setAlignmentY(CENTER_ALIGNMENT);
		// before the trailing glue, which only exists when there is a label
		add(holder, label == null ? getComponentCount() : getComponentCount() - 1);
		return this;
	}

	public V2Checkbox state(State state)
	{
		this.state = state;
		if (label != null)
		{
			label.setColor(state == State.OFF || state == State.ON
				? V2Tokens.TEXT : V2Tokens.FAINT);
		}
		setCursor(Cursor.getPredefinedCursor(state == State.LOCKED
			|| state == State.DISABLED || state == State.DISABLED_ON
			? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
		box.repaint();
		return this;
	}

	public State state()
	{
		return state;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, rowHeight());
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, rowHeight());
	}

	/** The art's own height, not {@code ROW_HEIGHT}'s 20 — the extra pixel
	 *  above and below only showed as slack in a list (Luke, 2026-07-25). */
	private int rowHeight()
	{
		return label == null ? box.getPreferredSize().height
			: Math.max(box.getPreferredSize().height, label.getPreferredSize().height);
	}

	/** The 18px art itself — its own component so the row can lay out around
	 *  it without BoxLayout's aligned-span rounding shifting it a pixel. */
	private class Box extends JComponent
	{
		private final OsrsTheme theme;

		Box(OsrsTheme theme)
		{
			this.theme = theme;
			setOpaque(false);
			setAlignmentY(Component.CENTER_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			String key = BOX + state.suffix;
			boolean live = state == State.OFF || state == State.ON;
			BufferedImage art = hover && live
				? V2Sprites.highlighted(theme, key) : V2Sprites.get(theme, key);
			g.drawImage(art, 0, (getHeight() - art.getHeight()) / 2, null);
		}

		@Override
		public Dimension getPreferredSize()
		{
			V2Sprites.Meta meta = V2Sprites.meta(BOX);
			return new Dimension(meta.width(), meta.height());
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return getPreferredSize();
		}
	}
}
