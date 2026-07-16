package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A group of check rows inside ONE notched stone frame (per Luke — rows do
 * not each get their own box). Rows highlight under the pointer and toggle on
 * press; ticked rows go green, matching the skin's "done" reading.
 */
public class StoneChecklist extends StonePanel
{
	public StoneChecklist(OsrsTheme theme)
	{
		super(theme);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
	}

	public StoneChecklist row(String text, boolean checked)
	{
		if (getComponentCount() > 0)
		{
			add(Box.createVerticalStrut(2));
		}
		add(new Row(theme, text, checked));
		return this;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/** One checkbox + label line; the whole row is the hit target. */
	private static class Row extends JPanel
	{
		private final OsrsTheme theme;
		private final StoneCheckbox box;
		private final OsrsLabel label;
		private boolean checked;

		Row(OsrsTheme theme, String text, boolean checked)
		{
			this.theme = theme;
			this.checked = checked;
			this.box = new StoneCheckbox(theme, checked);
			this.label = new OsrsLabel(text, checked ? OsrsSkin.VALUE : OsrsSkin.LABEL, OsrsSkin.font());
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
			setOpaque(true);
			setBackground(theme.boxFill);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			box.setAlignmentY(Component.CENTER_ALIGNMENT);
			label.setAlignmentY(Component.CENTER_ALIGNMENT);
			add(box);
			add(Box.createHorizontalStrut(6));
			add(label);
			add(Box.createHorizontalGlue());

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(theme.hoverFill);
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(theme.boxFill);
					repaint();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					toggle();
				}
			});
		}

		private void toggle()
		{
			checked = !checked;
			box.setChecked(checked);
			label.setColor(checked ? OsrsSkin.VALUE : OsrsSkin.LABEL);
			repaint();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** Test seam: the fill a row paints when hovered. */
	public Color hoverFill()
	{
		return theme.hoverFill;
	}
}
