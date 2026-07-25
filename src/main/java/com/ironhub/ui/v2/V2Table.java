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
	private final com.ironhub.ui.osrs.OsrsTheme theme;
	private final NineSlice surface;
	private int hoverRow = -1;

	/** @param flexColumn the column that absorbs leftover width, usually the name */
	public V2Table(int flexColumn)
	{
		this(null, flexColumn);
	}

	/**
	 * A framed table: the rows sit inside one notched slab with a highlight
	 * band under the pointer, the Checklist grammar from V1 (Luke,
	 * 2026-07-25). A null theme draws no surface — a bare column model.
	 */
	public V2Table(com.ironhub.ui.osrs.OsrsTheme theme, int flexColumn)
	{
		this.theme = theme;
		this.surface = theme == null ? null : V2Tokens.slab();
		this.flexColumn = flexColumn;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new TableLayout());
		if (theme != null)
		{
			int inset = V2Tokens.SLAB_INSET;
			setBorder(new javax.swing.border.EmptyBorder(inset, inset, inset, inset));
			addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
			{
				@Override
				public void mouseMoved(java.awt.event.MouseEvent e)
				{
					int row = rowAt(e.getY());
					if (row != hoverRow)
					{
						hoverRow = row;
						repaint();
					}
				}
			});
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					hoverRow = -1;
					repaint();
				}
			});
		}
	}

	/** Which row a y coordinate falls in, or -1. */
	private int rowAt(int y)
	{
		for (int i = 0; i < rows.size(); i++)
		{
			Component first = rows.get(i)[0];
			if (y >= first.getY() && y < first.getY() + first.getHeight())
			{
				return i;
			}
		}
		return -1;
	}

	@Override
	protected void paintComponent(java.awt.Graphics g)
	{
		if (surface != null)
		{
			surface.paint((java.awt.Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
			if (hoverRow >= 0 && hoverRow < rows.size())
			{
				// the band spans the whole surface, inset equally, exactly as
				// the V1 checklist highlight does
				Component first = rows.get(hoverRow)[0];
				g.setColor(V2Tokens.HIGHLIGHT);
				g.fillRect(V2Tokens.SLAB_INSET, first.getY() - V2Tokens.TIGHT,
					getWidth() - 2 * V2Tokens.SLAB_INSET,
					first.getHeight() + 2 * V2Tokens.TIGHT);
			}
		}
		super.paintComponent(g);
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
		java.awt.Insets insets = getInsets();
		return ((TableLayout) getLayout()).widths(getWidth() - insets.left - insets.right);
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
			java.awt.Insets insets = getInsets();
			int[] widths = widths(parent.getWidth() - insets.left - insets.right);
			int y = insets.top;
			for (Component[] row : rows)
			{
				int height = rowHeight(row);
				int x = insets.left;
				for (int c = 0; c < row.length; c++)
				{
					row[c].setBounds(x, y, widths[c], height);
					x += widths[c] + V2Tokens.PAD;
				}
				y += height + V2Tokens.ROW;
			}
		}

		/**
		 * EVERY row is the same height — the tallest cell in the whole table,
		 * not per row. Per-row heights made a table of 13px ticks and 17px
		 * ticks step up and down the list (Luke, 2026-07-25).
		 */
		private int rowHeight(Component[] row)
		{
			int height = V2Tokens.ROW_HEIGHT;
			for (Component[] any : rows)
			{
				for (Component cell : any)
				{
					height = Math.max(height, cell.getPreferredSize().height);
				}
			}
			return height;
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			java.awt.Insets insets = getInsets();
			int height = insets.top + insets.bottom;
			for (Component[] row : rows)
			{
				height += rowHeight(row) + V2Tokens.ROW;
			}
			return new Dimension(V2Tokens.CONTENT_WIDTH,
				Math.max(0, height - V2Tokens.ROW));
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
