package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A one-of-many picker for lists too long to be chips: the game's own field
 * well with its stone arrow at the right end.
 *
 * <p><b>The open list floats OVER the content</b> (Luke, 2026-07-27,
 * reversing his 2026-07-25 grow-in-place ruling: growing pushed the page's
 * cards down, which turned out to be the worse read). What the popup learned
 * from that ruling's complaints survives: it is the WELL continuing —
 * painted over the panel background, exactly the control's width, flush
 * under the closed row — never a floated Card reading as client chrome, and
 * never wider than the column.
 *
 * <p>Deliberately not a styled {@code JComboBox}. The Swing control brings a
 * renderer, a UI delegate, a popup border and a scrollbar that each have to be
 * re-skinned and each drift on their own schedule — five surfaces to keep in
 * step where this needs one.
 */
public class V2Dropdown extends JPanel
{
	/** The longest an open well gets. */
	public static final int MAX_ROWS = 20;

	private final OsrsTheme theme;
	private final V2Well well = V2Tokens.well();
	private final String[] options;
	private int selected;
	private IntConsumer onChange;
	private boolean expanded;
	/** The floated list while open; null while closed. */
	private javax.swing.JPopupMenu popup;
	/** Which row the pointer is over, or -1. Drives the wash. */
	private int hovered = -1;
	/** A pinned width, for a dropdown sharing a row with another control.
	 *  0 = the full content width. {@code setPreferredSize} cannot do this:
	 *  the size overrides below ignore it (§14's {@code getMaximumSize} trap). */
	private int width;

	public V2Dropdown(OsrsTheme theme, String... options)
	{
		this.theme = theme;
		this.options = options.clone();
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		rebuild();
	}

	/** Pin the width — the Gear library puts a slot picker and a sort picker
	 *  on one row, and each must keep its own share of it. */
	public V2Dropdown width(int width)
	{
		this.width = width;
		revalidate();
		return this;
	}

	public V2Dropdown onChange(IntConsumer onChange)
	{
		this.onChange = onChange;
		return this;
	}

	public int selected()
	{
		return selected;
	}

	public void setSelected(int index)
	{
		selected = index;
		rebuild();
	}

	/** Pick as a click would, listener included. Test seam. */
	public void pick(int index)
	{
		selected = index;
		expanded = false;
		rebuild();
		if (onChange != null)
		{
			onChange.accept(index);
		}
	}

	/** Open or close the list. Test seam. */
	public void setExpanded(boolean expanded)
	{
		this.expanded = expanded;
		hovered = -1;
		if (!expanded && popup != null)
		{
			javax.swing.JPopupMenu closing = popup;
			popup = null;
			closing.setVisible(false);
		}
		// headless render tests flag the state without a heavyweight popup
		if (expanded && isShowing())
		{
			showPopup();
		}
		rebuild();
	}

	/** The floated list: the well continuing under the closed row. */
	private void showPopup()
	{
		popup = new javax.swing.JPopupMenu();
		popup.setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));
		popup.setOpaque(false);
		ListPanel list = new ListPanel();
		popup.add(list);
		popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e)
			{
				// outside click, ESC, or a pick — either way we are closed
				if (popup != null)
				{
					popup = null;
					expanded = false;
					hovered = -1;
					rebuild();
				}
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e)
			{
			}
		});
		popup.show(this, 0, V2Tokens.CONTROL_HEIGHT);
	}

	/** The popup's body: every option in one well, the control's width. */
	private class ListPanel extends JPanel
	{
		private int listHovered = -1;

		ListPanel()
		{
			setOpaque(true);
			setBackground(theme.background);
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			for (int i = 0; i < shownRows(); i++)
			{
				int index = i;
				JPanel row = new JPanel(new java.awt.BorderLayout());
				row.setOpaque(false);
				row.setAlignmentX(LEFT_ALIGNMENT);
				row.setBorder(new javax.swing.border.EmptyBorder(0, V2Tokens.PAD, 0,
					V2Sprites.meta("ui/arrows/arrow_down").width() + V2Tokens.PAD));
				row.add(index == selected
					? V2Label.heading(options[index]) : V2Label.detail(options[index]),
					java.awt.BorderLayout.CENTER);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
				row.setPreferredSize(new Dimension(listWidth(), V2Tokens.CONTROL_HEIGHT));
				row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				row.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						javax.swing.JPopupMenu closing = popup;
						popup = null;
						expanded = false;
						if (closing != null)
						{
							closing.setVisible(false);
						}
						pick(index);
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						listHovered = index;
						ListPanel.this.repaint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						listHovered = -1;
						ListPanel.this.repaint();
					}
				});
				add(row);
			}
		}

		private int listWidth()
		{
			return V2Dropdown.this.getWidth() > 0
				? V2Dropdown.this.getWidth()
				: width > 0 ? width : V2Tokens.CONTENT_WIDTH;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			g2.setColor(theme.background);
			g2.fillRect(0, 0, getWidth(), getHeight());
			well.paint(g2, theme, 0, 0, getWidth(), getHeight());
			if (listHovered >= 0)
			{
				g2.setColor(V2Tokens.HIGHLIGHT);
				g2.fillRect(V2Well.CAP, listHovered * V2Tokens.CONTROL_HEIGHT,
					Math.max(0, getWidth() - 2 * V2Well.CAP), V2Tokens.CONTROL_HEIGHT);
			}
			super.paintComponent(g);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(listWidth(), shownRows() * V2Tokens.CONTROL_HEIGHT);
		}
	}

	public boolean isExpanded()
	{
		return expanded;
	}

	/**
	 * Rebuild the rows for the current state: one when closed, all of them when
	 * open. Revalidated on the spot, because the component's own HEIGHT changes
	 * — a dropdown that grows without telling its parent is a dropdown drawn
	 * over whatever sits beneath it.
	 */
	private void rebuild()
	{
		removeAll();
		if (options.length == 0)
		{
			revalidate();
			repaint();
			return;
		}
		// the component is ALWAYS the one closed row — the open list floats
		// in the popup, over the content instead of pushing it down
		add(new Row(selected));
		revalidate();
		repaint();
	}

	/** How many options an open well lists. Capped (Luke, 2026-07-25): a
	 *  hundred kill sources would push the whole page down the panel. */
	private int shownRows()
	{
		return Math.min(options.length, MAX_ROWS);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		well.paint(g2, theme, 0, 0, getWidth(), getHeight());
		if (hovered >= 0)
		{
			// clipped to the well's INTERIOR — its end caps are CAP wide, and a
			// full-width fill washed the air outside them (Luke, 2026-07-25)
			g2.setColor(V2Tokens.HIGHLIGHT);
			g2.fillRect(V2Well.CAP, hovered * V2Tokens.CONTROL_HEIGHT,
				Math.max(0, getWidth() - 2 * V2Well.CAP), V2Tokens.CONTROL_HEIGHT);
		}
		// the game puts a stone arrow button at the right end of the well; it
		// stays on the FIRST row, which is the one that opens and closes
		BufferedImage arrow = V2Sprites.get(theme,
			expanded ? "ui/arrows/arrow_up" : "ui/arrows/arrow_down");
		g2.drawImage(arrow, getWidth() - arrow.getWidth() - V2Tokens.TIGHT,
			(V2Tokens.CONTROL_HEIGHT - arrow.getHeight()) / 2, null);
		super.paintComponent(g);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(width > 0 ? width : V2Tokens.CONTENT_WIDTH,
			V2Tokens.CONTROL_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(width > 0 ? width : Integer.MAX_VALUE,
			V2Tokens.CONTROL_HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(width > 0 ? width : 4 * V2Tokens.SECTION,
			V2Tokens.CONTROL_HEIGHT);
	}

	/** One option line inside the well. Transparent: the well is painted once,
	 *  underneath all of them, so a row never draws a surface of its own. */
	private class Row extends JPanel
	{
		private final int index;

		Row(int index)
		{
			this.index = index;
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			setLayout(new java.awt.BorderLayout());
			// the arrow's column is reserved on every row, so a long option
			// never runs underneath it
			setBorder(new javax.swing.border.EmptyBorder(0, V2Tokens.PAD, 0,
				V2Sprites.meta("ui/arrows/arrow_down").width() + V2Tokens.PAD));
			// the selection is the HEADING; everything under it is DETAIL, so
			// the row you are on is the one that reads (Luke, 2026-07-25)
			OsrsLabel text = index == selected
				? V2Label.heading(options[index]) : V2Label.detail(options[index]);
			add(text, java.awt.BorderLayout.CENTER);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					setExpanded(!expanded);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hovered = rowPosition();
					V2Dropdown.this.repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hovered = -1;
					V2Dropdown.this.repaint();
				}
			});
		}

		/** Where this row sits in the well — its option index when open, and
		 *  always the top row when closed. */
		private int rowPosition()
		{
			return expanded ? index : 0;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(V2Tokens.CONTENT_WIDTH, V2Tokens.CONTROL_HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return new Dimension(0, V2Tokens.CONTROL_HEIGHT);
		}
	}

	/** Every child is a Row and every Row is left-aligned; kept explicit so the
	 *  atom test's alignment sweep sees it on the container too. */
	@Override
	public Component add(Component child)
	{
		if (child instanceof JPanel)
		{
			((JPanel) child).setAlignmentX(LEFT_ALIGNMENT);
		}
		return super.add(child);
	}
}
