package com.ironhub.ui.osrs;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A row of selectable text chips — the skin's segmented control (view
 * switches, budget chips, layout toggles). Each option is a notched stone
 * chip; the selected one wears the theme's select fill with title-orange
 * text, the same "you are here" reading as the checklist highlight band.
 *
 * <p>stretch=true divides the full width evenly between the chips (a view
 * switch spans the panel); stretch=false leaves them at natural width.
 */
public class StoneChipRow extends JPanel
{
	private final List<Chip> chips = new ArrayList<>();
	private int selected;
	private IntConsumer onChange;

	public StoneChipRow(OsrsTheme theme, boolean stretch, String... options)
	{
		if (stretch)
		{
			setLayout(new GridLayout(1, options.length, 3, 0));
		}
		else
		{
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		}
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		for (int i = 0; i < options.length; i++)
		{
			final int index = i;
			Chip chip = new Chip(theme, options[i], () -> select(index, true));
			chips.add(chip);
			add(chip);
			if (!stretch && i < options.length - 1)
			{
				add(Box.createHorizontalStrut(3));
			}
		}
		select(0, false);
	}

	public void onChange(IntConsumer onChange)
	{
		this.onChange = onChange;
	}

	public int getSelected()
	{
		return selected;
	}

	/** Programmatic selection — never fires onChange (the caller acts). */
	public void setSelected(int index)
	{
		select(index, false);
	}

	/** Pick a chip exactly as a click does, onChange included (test seam). */
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
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/** A subtle cue on one chip: its label reads VALUE-green while not
	 *  selected (the DPS chip when the calc beats your current gear).
	 *  Indexes the chip list, never getComponents() — non-stretch rows
	 *  interleave spacer struts there (the render caught the off-by-strut). */
	public void highlight(int index, boolean on)
	{
		if (index >= 0 && index < chips.size())
		{
			chips.get(index).setHighlighted(on);
		}
	}

	private static class Chip extends StonePanel
	{
		private final OsrsTheme theme;
		private final OsrsLabel label;
		private boolean selected;
		private boolean hover;
		private boolean highlighted;

		Chip(OsrsTheme theme, String text, Runnable onPress)
		{
			super(theme);
			this.theme = theme;
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(Box.createHorizontalGlue());
			label = new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font());
			add(label);
			add(Box.createHorizontalGlue());
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					refresh();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					refresh();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					onPress.run();
				}
			});
		}

		void setSelected(boolean value)
		{
			selected = value;
			refresh();
		}

		void setHighlighted(boolean value)
		{
			highlighted = value;
			refresh();
		}

		private void refresh()
		{
			setBackground(selected ? theme.selectFill : hover ? theme.hoverFill : theme.boxFill);
			label.setColor(selected ? OsrsSkin.TITLE
				: highlighted ? OsrsSkin.VALUE : OsrsSkin.MUTED);
			repaint();
		}
	}
}
