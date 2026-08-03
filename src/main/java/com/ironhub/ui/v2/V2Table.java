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
	private final V2Well surface;
	private int hoverRow = -1;

	/** @param flexColumn the column that absorbs leftover width, usually the name */
	public V2Table(int flexColumn)
	{
		this(null, flexColumn);
	}

	/**
	 * A framed table: the rows sit inside a WELL with a highlight band under
	 * the pointer, the Checklist grammar from V1. The well is the surface every
	 * recessed thing in the system wears — fields, dropdowns, lists — and a
	 * table is a list (Luke, 2026-07-25: "Tables and Checklists both need to be
	 * Wells. Currently I don't know what they are"). A null theme draws no
	 * surface — a bare column model.
	 */
	public V2Table(com.ironhub.ui.osrs.OsrsTheme theme, int flexColumn)
	{
		this.theme = theme;
		this.surface = theme == null ? null : V2Tokens.well();
		this.flexColumn = flexColumn;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new TableLayout());
		if (theme != null)
		{
			// the Checklist's inset, so the pair stays a pair
			int inset = V2Well.CAP + V2Tokens.TIGHT;
			setBorder(new javax.swing.border.EmptyBorder(inset, inset, inset, inset));
			// cells with tooltips/actions swallow the pointer (deepest-
			// component dispatch), so the band went dead over the text —
			// the relay makes their events reach the table too (MouseRelay)
			MouseRelay.install(this);
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
				g.fillRect(V2Well.CAP, first.getY() - V2Tokens.TIGHT,
					getWidth() - 2 * V2Well.CAP,
					first.getHeight() + 2 * V2Tokens.TIGHT);
			}
		}
		super.paintComponent(g);
	}


	/**
	 * Put every label in this surface into the DETAIL font (Luke, 2026-07-25).
	 * A table and a checklist are dense lists, and the body font at 16px made
	 * four rows fill the panel; the small font is what the game itself uses for
	 * list text. Colour is left alone, so a value stays STRONG white and a
	 * status stays its status colour — only the size changes.
	 *
	 * <p>Applied by the atom rather than asked of the caller: a row is built
	 * from whatever components a module hands over, and "remember to pass
	 * detail labels" is precisely the instruction that gets forgotten.
	 */
	static void detailFont(java.awt.Component component)
	{
		if (component instanceof com.ironhub.ui.osrs.OsrsLabel)
		{
			((com.ironhub.ui.osrs.OsrsLabel) component).font(V2Tokens.detailFont());
		}
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				detailFont(child);
			}
		}
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
			detailFont(cell);
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
			int height = rowHeight();
			for (Component[] row : rows)
			{
				int x = insets.left;
				for (int c = 0; c < row.length; c++)
				{
					row[c].setBounds(x, y, widths[c], height);
					x += widths[c] + V2Tokens.PAD;
				}
				y += height;
			}
		}

		/**
		 * EVERY row is the same height — the tallest cell in the whole table,
		 * not per row. Per-row heights made a table of 13px ticks and 17px
		 * ticks step up and down the list (Luke, 2026-07-25).
		 */
		private int rowHeight()
		{
			// no ROW_HEIGHT floor: 20 is the checkbox's row, and a table of 13px
			// ticks and small-font text does not need it. The floor was what
			// made the Table read looser than the Checklist even though every
			// measurement matched (Luke, 2026-07-25). One table-wide scan per
			// layout pass — the value is the same for every row by design.
			int height = 0;
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
			int height = insets.top + insets.bottom + rowHeight() * rows.size();
			return new Dimension(V2Tokens.CONTENT_WIDTH,
				Math.max(0, height));
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
