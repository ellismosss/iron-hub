package com.ironhub.ui.osrs;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A group of check rows inside ONE notched stone frame (per Luke — rows do
 * not each get their own box). Rows highlight under the pointer and toggle on
 * press; ticked rows go green, matching the skin's "done" reading.
 *
 * <p>The frame's side padding is trimmed to 2px so a row's highlight reaches
 * the engraved edge (at the stat-box padding it left a gap down both sides —
 * Luke, in-client 2026-07-16); the top/bottom padding is the corner stamp's
 * own height, which is the first row that clears the notch.
 */
public class StoneChecklist extends StonePanel
{
	private static final int SIDE_PAD = 2;

	public StoneChecklist(OsrsTheme theme)
	{
		super(theme);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		int corner = theme.cornerStamp.length;
		setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, SIDE_PAD, corner, SIDE_PAD)));
	}

	public StoneChecklist row(String text, boolean checked)
	{
		add(new Row(theme, text, checked));
		return this;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/**
	 * One checkbox + label line; the whole row is the hit target and the
	 * highlight. Laid out by hand rather than by BoxLayout: the row must
	 * center a 15px box and an odd-height ink block in the same height, and
	 * BoxLayout's aligned-span rounding put the box a pixel high.
	 */
	static class Row extends JPanel
	{
		private static final int PAD = 4;
		private static final int GAP = 6;

		private final OsrsTheme theme;
		private final StoneCheckbox box;
		private final OsrsLabel label;
		private boolean checked;

		Row(OsrsTheme theme, String text, boolean checked)
		{
			this.theme = theme;
			this.checked = checked;
			this.box = new StoneCheckbox(theme, checked);
			this.label = new OsrsLabel(text, checked ? OsrsSkin.VALUE : OsrsSkin.LABEL, OsrsSkin.font())
				.leftAligned();
			setLayout(null);
			setOpaque(true);
			setBackground(theme.boxFill);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(box);
			add(label);

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
		public void doLayout()
		{
			int h = getHeight();
			Dimension bp = box.getPreferredSize();
			box.setBounds(PAD, (h - bp.height) / 2, bp.width, bp.height);
			// the label owns the full row height and centers its own ink in it
			int x = PAD + bp.width + GAP;
			label.setBounds(x, 0, Math.max(0, getWidth() - x - PAD), h);
		}

		@Override
		public Dimension getPreferredSize()
		{
			// odd height: the ink block and the odd-sized box both center exactly
			return new Dimension(0, Math.max(box.getPreferredSize().height,
				label.getPreferredSize().height));
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		/** Test seams. */
		StoneCheckbox checkbox()
		{
			return box;
		}
	}
}
