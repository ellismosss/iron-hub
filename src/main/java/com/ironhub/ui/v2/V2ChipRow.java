package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * The segmented control: view switches, filters, either/or choices. Built on
 * the Card slice, so it takes any width and wears the art's own hovered
 * state.
 *
 * <p><b>Selected reads two ways at once</b> (Luke's call, 2026-07-24): the
 * chip wears the hovered art AND its label goes HEADING orange. The art's
 * brighten alone is genuinely hard to spot on a three-chip row — the colour
 * does the shouting and the art does the rest.
 *
 * <p>Stretch divides the full width evenly; natural width is for a row of
 * two or three short labels that would otherwise read as one wide bar.
 */
public class V2ChipRow extends JPanel
{
	private final List<Chip> chips = new ArrayList<>();
	private int selected;
	private IntConsumer onChange;

	public V2ChipRow(OsrsTheme theme, boolean stretch, String... options)
	{
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		if (stretch)
		{
			setLayout(new GridLayout(1, options.length, V2Tokens.ROW, 0));
		}
		else
		{
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		}
		for (int i = 0; i < options.length; i++)
		{
			final int index = i;
			Chip chip = new Chip(theme, options[i], () -> select(index, true));
			chips.add(chip);
			add(chip);
			if (!stretch && i < options.length - 1)
			{
				add(javax.swing.Box.createHorizontalStrut(V2Tokens.ROW));
			}
		}
		select(0, false);
	}

	public V2ChipRow onChange(IntConsumer onChange)
	{
		this.onChange = onChange;
		return this;
	}

	public int selected()
	{
		return selected;
	}

	/** Programmatic selection — never fires onChange (the caller is acting). */
	public void setSelected(int index)
	{
		select(index, false);
	}

	/** Enable or grey a chip (§8 Disabled: FAINT label, no hover, presses
	 *  ignored) — the Recommended chip with no monster selected (GC9). */
	public void setChipEnabled(int index, boolean enabled)
	{
		chips.get(index).setChipEnabled(enabled);
	}

	/** Pick a chip exactly as a press does. Test seam. */
	public void pick(int index)
	{
		select(index, true);
	}

	private void select(int index, boolean fire)
	{
		selected = index;
		for (int i = 0; i < chips.size(); i++)
		{
			chips.get(i).setSelected(i == index);
		}
		if (fire && onChange != null)
		{
			onChange.accept(index);
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, V2Tokens.CONTROL_HEIGHT);
	}

	/**
	 * A cue on one chip that is not selected: its label reads DONE-green, which
	 * is the one status colour that means "this is the good one" (§6). Ported
	 * from {@code StoneChipRow} for the DPS chip, which wears it when the calc
	 * beats the gear you have on (Luke).
	 *
	 * <p>Indexes the chip LIST, never {@code getComponents()} — a non-stretch
	 * row interleaves spacer struts there, and the V1 version was caught
	 * off-by-a-strut doing exactly that.
	 */
	public void highlight(int index, boolean on)
	{
		if (index >= 0 && index < chips.size())
		{
			chips.get(index).setHighlighted(on);
		}
	}

	/**
	 * ONE chip, standing alone, that fires and does not latch — an inline
	 * action like Slayer's "Route" or "Open DPS calc" (Luke, 2026-07-25).
	 *
	 * <p>It returns the very same {@code Chip} the rows are built from, which
	 * is the point: hand-rolling a chip-shaped thing out of the chip SURFACE
	 * produced something visibly different — wrong height, wrong padding, no
	 * hover wash — and "the Route chip looks different to the other chips" is
	 * exactly what §9 means by never hand-rolling an atom's job.
	 *
	 * <p>Wrapped in a flow holder because {@code Chip} reports full width to a
	 * BoxLayout, and an action chip must stay at its own size.
	 */
	public static JPanel action(OsrsTheme theme, String text, Runnable onPress)
	{
		return action(theme, text, null, null, onPress);
	}

	/**
	 * The same, with the label in a given colour and/or led by an icon — the
	 * DPS style row, whose chips read green for the best figure and carry a
	 * protect-prayer glyph.
	 *
	 * @param labelColor null for the chip's own TEXT colour
	 * @param icon       null for text alone
	 */
	public static JPanel action(OsrsTheme theme, String text, java.awt.Color labelColor,
		javax.swing.Icon icon, Runnable onPress)
	{
		return action(theme, text, labelColor, icon, null, onPress);
	}

	/**
	 * The same, in a given font — DETAIL for a chip that sits among detail
	 * rows, so it does not shout over the text it belongs to (Luke,
	 * 2026-07-25, Slayer's Route).
	 *
	 * @param font null for the chip's own body font
	 */
	public static JPanel action(OsrsTheme theme, String text, java.awt.Color labelColor,
		javax.swing.Icon icon, java.awt.Font font, Runnable onPress)
	{
		return action(theme, text, labelColor, icon, font, false, onPress);
	}

	/**
	 * The same, optionally FILLING its cell — a grid of action chips (the
	 * DPS style row) must split its row evenly like a {@code V2ChipRow}
	 * does, not hug each label (GC5, 2026-08-03).
	 */
	public static JPanel action(OsrsTheme theme, String text, java.awt.Color labelColor,
		javax.swing.Icon icon, java.awt.Font font, boolean stretch, Runnable onPress)
	{
		JPanel holder = stretch ? new JPanel(new java.awt.BorderLayout())
			: new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		Chip chip = new Chip(theme, text, onPress);
		chip.decorate(labelColor, icon, font);
		holder.add(chip);
		return holder;
	}

	/**
	 * ONE chip that LATCHES — an on/off filter standing on its own ("Hide
	 * complete"). Distinct from {@link #action}, which fires and never holds a
	 * state, and from a row, which is one-of-many.
	 *
	 * <p>It exists because the Gear chart had built this out of a
	 * {@code StonePanel} and re-implemented the chip's fills, hover and label
	 * colouring by hand — §14's mistake, in the one shape the atom did not yet
	 * cover (Luke's Progression pass, 2026-07-26).
	 */
	public static JPanel toggle(OsrsTheme theme, String text, boolean on,
		java.util.function.Consumer<Boolean> onToggle)
	{
		return toggle(theme, text, null, on, false, onToggle);
	}

	/**
	 * The same, optionally led by an icon and optionally filling its cell — the
	 * money-making filter strip is a three-column grid whose third choice is a
	 * heart glyph rather than a word.
	 *
	 * @param icon    null for text alone
	 * @param stretch true to fill the space the layout gives it, false to keep
	 *                the chip's own width
	 */
	public static JPanel toggle(OsrsTheme theme, String text, javax.swing.Icon icon,
		boolean on, boolean stretch, java.util.function.Consumer<Boolean> onToggle)
	{
		return toggle(theme, text, icon, null, on, stretch, onToggle);
	}

	/** The same, in a given font — DETAIL for a grid of chips whose labels are
	 *  longer than a three-across cell can hold at body weight. */
	public static JPanel toggle(OsrsTheme theme, String text, javax.swing.Icon icon,
		java.awt.Font font, boolean on, boolean stretch,
		java.util.function.Consumer<Boolean> onToggle)
	{
		JPanel holder = stretch ? new JPanel(new java.awt.BorderLayout())
			: new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		boolean[] state = {on};
		Chip[] chip = new Chip[1];
		chip[0] = new Chip(theme, text, () ->
		{
			state[0] = !state[0];
			chip[0].setSelected(state[0]);
			onToggle.accept(state[0]);
		});
		chip[0].decorate(null, icon, font);
		chip[0].setSelected(on);
		holder.add(chip[0]);
		return holder;
	}

	private static class Chip extends JPanel
	{
		private final OsrsTheme theme;
		private final NineSlice plain = V2Tokens.chip();
		/** SELECTED wears the art's own second state. */
		private final NineSlice picked = V2Tokens.chip().variant("_hovered");
		/** HOVER wears the pointer wash — not the pressed sprite (Luke,
		 *  2026-07-25: the chips "show their Pressed sprite on hover"). */
		private final NineSlice lit = V2Tokens.chip().highlighted();
		private final OsrsLabel label;
		private boolean selected;
		private boolean hover;
		/** A cue on an UNSELECTED chip — see {@link V2ChipRow#highlight}. */
		private boolean highlighted;
		/** Greyed and inert (§8 Disabled) — see {@link #setChipEnabled}. */
		private boolean chipDisabled;
		/** Set by {@link #decorate}: an action chip's own label colour. */
		private java.awt.Color fixedColor;

		Chip(OsrsTheme theme, String text, Runnable onPress)
		{
			this.theme = theme;
			// squeezable: a chip narrower than its text ELLIPSIZES rather than
			// hard-clipping at the art's edge (Luke's Bank pass, 2026-07-26 —
			// the money-making category grid cut "Collecting" to "Collectin")
			this.label = V2Label.centred(text).squeezable();
			setOpaque(false);
			setLayout(new java.awt.BorderLayout());
			add(label, java.awt.BorderLayout.CENTER);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					// left only: rows relay presses to themselves for their
					// right-click menus — a chip must not also fire on those
					if (!javax.swing.SwingUtilities.isLeftMouseButton(e)
						|| onPress == null || chipDisabled)
					{
						return; // null onPress: a display-only chip ("—" style)
					}
					onPress.run();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = !chipDisabled;
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

		/**
		 * An action chip's own look: a fixed label colour, and an icon before
		 * the text. Both are the caller's, so {@link #setSelected} leaves them
		 * alone — an action chip never latches, so it has no selected state to
		 * fight over.
		 */
		void decorate(java.awt.Color labelColor, javax.swing.Icon icon, java.awt.Font font)
		{
			if (font != null)
			{
				label.font(font);
			}
			if (labelColor != null)
			{
				fixedColor = labelColor;
				label.setColor(labelColor);
			}
			if (icon != null)
			{
				remove(label);
				JPanel line = V2Layout.row();
				line.add(V2Layout.glue());
				line.add(new javax.swing.JLabel(icon)); // v2-exempt: an icon holder, not text
				line.add(V2Layout.hgap(V2Tokens.ROW));
				line.add(label);
				line.add(V2Layout.glue());
				add(line, java.awt.BorderLayout.CENTER);
			}
		}

		void setSelected(boolean selected)
		{
			this.selected = selected;
			resolveColor();
		}

		void setChipEnabled(boolean enabled)
		{
			chipDisabled = !enabled;
			if (chipDisabled)
			{
				hover = false;
			}
			setCursor(Cursor.getPredefinedCursor(
				chipDisabled ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
			resolveColor();
		}

		private void resolveColor()
		{
			if (chipDisabled)
			{
				label.setColor(V2Tokens.FAINT);
			}
			else if (fixedColor == null)
			{
				label.setColor(selected ? V2Tokens.HEADING
					: highlighted ? V2Tokens.DONE : V2Tokens.TEXT);
			}
			else
			{
				label.setColor(fixedColor);
			}
			repaint();
		}

		void setHighlighted(boolean highlighted)
		{
			this.highlighted = highlighted;
			setSelected(selected); // re-resolves the label colour
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			(selected ? picked : hover ? lit : plain)
				.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
			super.paintComponent(g);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(label.getPreferredSize().width + 2 * V2Tokens.SECTION,
				V2Tokens.CONTROL_HEIGHT);
		}

		/** A chip never shrinks below its label + end caps: BoxLayout
		 *  squeezes children toward their MINIMUM when a row runs tight,
		 *  and the label's squeezable minimum let the chip collapse until
		 *  its text hung outside the art (the Hunter Route/Show-bank
		 *  clipping, H1/H4 2026-08-03). The squeezable NEIGHBOUR (a name
		 *  label) is what gives way instead. */
		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}
	}
}
