package com.ironhub.ui.v2;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Rows whose columns line up down the whole list.
 *
 * <p>This atom exists because every tab was doing it by hand — each row
 * laying itself out with its own struts and glue, so a name column wandered
 * a few pixels between one list and the next. A table measures each column
 * once across every row and gives them all the same widths. It is the single
 * biggest source of the "things don't line up" reading Luke described.
 *
 * <p>One column is the FLEX column: it absorbs the leftover width and is the
 * one allowed to ellipsize (the atoms do that at paint). Everything else
 * takes its natural width, so counts and status stay hard against the right
 * edge where the eye can compare them.
 */
public class V2Table extends JPanel
{
	private final int flexColumn;
	private final List<Component[]> rows = new ArrayList<>();

	/** @param flexColumn the column that absorbs leftover width, usually the name */
	public V2Table(int flexColumn)
	{
		this.flexColumn = flexColumn;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new TableLayout());
	}

	/** One row. Every row must have the same number of cells. */
	public V2Table row(Component... cells)
	{
		if (!rows.isEmpty() && rows.get(0).length != cells.length)
		{
			throw new IllegalArgumentException("every row needs " + rows.get(0).length
				+ " cells — a ragged table is what stops columns lining up");
		}
		rows.add(cells);
		for (Component cell : cells)
		{
			add(cell);
		}
		return this;
	}

	public int rowCount()
	{
		return rows.size();
	}

	/** The measured width of each column, after layout. Test seam. */
	public int[] columnWidths()
	{
		return ((TableLayout) getLayout()).widths(getWidth());
	}

	private class TableLayout implements LayoutManager
	{
		int[] widths(int available)
		{
			if (rows.isEmpty())
			{
				return new int[0];
			}
			int columns = rows.get(0).length;
			int[] widths = new int[columns];
			for (Component[] row : rows)
			{
				for (int c = 0; c < columns; c++)
				{
					widths[c] = Math.max(widths[c], row[c].getPreferredSize().width);
				}
			}
			int gaps = (columns - 1) * V2Tokens.PAD;
			int fixed = gaps;
			for (int c = 0; c < columns; c++)
			{
				if (c != flexColumn)
				{
					fixed += widths[c];
				}
			}
			widths[flexColumn] = Math.max(0, available - fixed);
			return widths;
		}

		@Override
		public void layoutContainer(Container parent)
		{
			int[] widths = widths(parent.getWidth());
			int y = 0;
			for (Component[] row : rows)
			{
				int height = rowHeight(row);
				int x = 0;
				for (int c = 0; c < row.length; c++)
				{
					row[c].setBounds(x, y, widths[c], height);
					x += widths[c] + V2Tokens.PAD;
				}
				y += height + V2Tokens.ROW;
			}
		}

		private int rowHeight(Component[] row)
		{
			int height = V2Tokens.ROW_HEIGHT;
			for (Component cell : row)
			{
				height = Math.max(height, cell.getPreferredSize().height);
			}
			return height;
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			int height = 0;
			for (Component[] row : rows)
			{
				height += rowHeight(row) + V2Tokens.ROW;
			}
			return new Dimension(V2Tokens.CONTENT_WIDTH, Math.max(0, height - V2Tokens.ROW));
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return preferredLayoutSize(parent);
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/**
	 * A cell pushed to the right of its column — for counts and values, so a
	 * column of numbers lines up on its last digit instead of its first. Left
	 * alignment puts 142 and 1,204 at the same x and their units three
	 * characters apart, which is the misalignment you actually notice.
	 */
	public static JComponent right(Component cell)
	{
		JPanel holder = new JPanel();
		holder.setOpaque(false);
		holder.setLayout(new javax.swing.BoxLayout(holder, javax.swing.BoxLayout.X_AXIS));
		holder.add(javax.swing.Box.createHorizontalGlue());
		holder.add(cell);
		// the glue has no preferred width, so the column still measures by
		// the cell itself
		holder.setPreferredSize(new Dimension(cell.getPreferredSize().width,
			cell.getPreferredSize().height));
		return holder;
	}

	/** A cell holding nothing — a column a given row has no value for. Never
	 *  a zero or a dash: absent and zero are different (§6 rule 5). */
	public static JComponent blank()
	{
		JComponent blank = new JPanel();
		blank.setOpaque(false);
		blank.setPreferredSize(new Dimension(0, 0));
		return blank;
	}
}
