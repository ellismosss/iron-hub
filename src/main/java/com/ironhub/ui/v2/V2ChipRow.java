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

	private static class Chip extends JPanel
	{
		private final OsrsTheme theme;
		private final NineSlice plain = V2Tokens.card();
		private final NineSlice lit = V2Tokens.card().variant("_hovered");
		private final OsrsLabel label;
		private boolean selected;
		private boolean hover;

		Chip(OsrsTheme theme, String text, Runnable onPress)
		{
			this.theme = theme;
			this.label = V2Label.centred(text);
			setOpaque(false);
			setLayout(new java.awt.BorderLayout());
			add(label, java.awt.BorderLayout.CENTER);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					onPress.run();
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

		void setSelected(boolean selected)
		{
			this.selected = selected;
			label.setColor(selected ? V2Tokens.HEADING : V2Tokens.TEXT);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			(selected || hover ? lit : plain)
				.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
			super.paintComponent(g);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(label.getPreferredSize().width + 2 * V2Tokens.SECTION,
				V2Tokens.CONTROL_HEIGHT);
		}
	}
}
