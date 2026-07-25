package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Rows grouped inside ONE framed surface, with a highlight band under the
 * pointer — the V1 checklist grammar, which Luke walked to the pixel across
 * three rounds and asked to keep.
 *
 * <p>The frame is the WELL — the game's own sunken texture, the same one
 * behind every field and dropdown in the system. It wore the dark
 * {@code tab_stone_middle} until 2026-07-25, which read as neither a list nor
 * a field: "Tables and Checklists both need to be Wells. Currently I don't
 * know what they are" (Luke). The band is inset equally on all four sides so
 * the space beside a row matches the space above the first one.
 *
 * <p>A checklist is not a {@link V2Table}: the table exists to align COLUMNS
 * down a list, this exists to group ROWS into one object. Rows that need both
 * put a table inside a checklist.
 */
public class V2Checklist extends JPanel
{
	/** The well's end caps, so the band clears them. */
	private static final int INSET = V2Well.CAP;

	private final OsrsTheme theme;
	private final V2Well frame;
	private final List<Component> rows = new ArrayList<>();
	private int hoverRow = -1;

	public V2Checklist(OsrsTheme theme)
	{
		this.theme = theme;
		this.frame = V2Tokens.well();
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		// CAP clears the well's end caps, TIGHT is the only air on top of it —
		// a list wants to feel dense (Luke, 2026-07-25: "needs to feel tight").
		// PAD here put 10px round three rows and the surface read half empty.
		setBorder(new EmptyBorder(V2Well.CAP + V2Tokens.TIGHT, V2Well.CAP + V2Tokens.TIGHT,
			V2Well.CAP + V2Tokens.TIGHT, V2Well.CAP + V2Tokens.TIGHT));
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

	public V2Checklist row(Component row)
	{
		V2Table.detailFont(row);
		rows.add(row);
		add(row);
		return this;
	}

	public int rowCount()
	{
		return rows.size();
	}

	/** Light a row without the pointer — the test seam, and how a selected
	 *  row reads. */
	public void setHighlighted(int index)
	{
		hoverRow = index;
		repaint();
	}

	private int rowAt(int y)
	{
		for (int i = 0; i < rows.size(); i++)
		{
			Component row = rows.get(i);
			if (y >= row.getY() && y < row.getY() + row.getHeight())
			{
				return i;
			}
		}
		return -1;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		frame.paint(g2, theme, 0, 0, getWidth(), getHeight());
		if (hoverRow >= 0 && hoverRow < rows.size())
		{
			Component row = rows.get(hoverRow);
			g2.setColor(V2Tokens.HIGHLIGHT);
			g2.fillRect(INSET, row.getY(), getWidth() - 2 * INSET, row.getHeight());
		}
		super.paintComponent(g);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
