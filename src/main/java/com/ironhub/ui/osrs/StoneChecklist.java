package com.ironhub.ui.osrs;

import java.awt.Color;
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
 * <p>The highlight band is inset EQUALLY on all four sides — the corner
 * stamp's height, which is both the first row that clears the notch and the
 * spacing Luke asked the sides to match (2026-07-16: full-bleed stretched
 * too far, a 1px side gap left the band closer to the sides than the top).
 * Within the band, the checkbox keeps its 1px gap on every side.
 */
public class StoneChecklist extends StonePanel
{
	public StoneChecklist(OsrsTheme theme)
	{
		super(theme);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		int corner = theme.cornerStamp.length;
		setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));
	}

	public StoneChecklist row(String text, boolean checked)
	{
		add(new Row(theme, text, checked, null, null, null, null));
		return this;
	}

	/**
	 * A row whose label colour is a STATUS the caller owns (the dailies
	 * scale: green claimable, orange short, faint done...), with an optional
	 * badge icon after the name and a toggle callback. The caller's state
	 * change is expected to rebuild the list, so the row does not recolour
	 * itself the way the simple overload does.
	 */
	public StoneChecklist row(String text, boolean checked, Color labelColor, String tooltip,
		javax.swing.Icon badge, java.util.function.Consumer<Boolean> onToggle)
	{
		add(new Row(theme, text, checked, labelColor, tooltip, badge, onToggle));
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
		/** The gap the highlight leaves around the checkbox, every side. */
		private static final int PAD = 1;
		private static final int GAP = 6;

		private final OsrsTheme theme;
		private final StoneCheckbox box;
		private final OsrsLabel label;
		private final javax.swing.JLabel badge; // null when the row has none
		private final java.util.function.Consumer<Boolean> onToggle;
		private final boolean selfColoring;
		private boolean checked;

		Row(OsrsTheme theme, String text, boolean checked, Color labelColor, String tooltip,
			javax.swing.Icon badgeIcon, java.util.function.Consumer<Boolean> onToggle)
		{
			this.theme = theme;
			this.checked = checked;
			this.onToggle = onToggle;
			this.selfColoring = labelColor == null;
			this.box = new StoneCheckbox(theme, checked);
			this.label = new OsrsLabel(text,
				labelColor != null ? labelColor : checked ? OsrsSkin.VALUE : OsrsSkin.LABEL,
				OsrsSkin.font()).leftAligned();
			this.badge = badgeIcon == null ? null : new javax.swing.JLabel(badgeIcon);
			setLayout(null);
			setOpaque(true);
			setBackground(theme.boxFill);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(box);
			add(label);
			if (badge != null)
			{
				add(badge);
			}

			MouseAdapter clicks = new MouseAdapter()
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
			};
			addMouseListener(clicks);
			if (tooltip != null)
			{
				// a tooltip registers the label's own mouse listeners, which
				// would swallow the row's — so the label carries both
				setToolTipText(tooltip);
				label.setToolTipText(tooltip);
				label.addMouseListener(clicks);
			}
		}

		private void toggle()
		{
			checked = !checked;
			box.setChecked(checked);
			if (selfColoring)
			{
				label.setColor(checked ? OsrsSkin.VALUE : OsrsSkin.LABEL);
			}
			repaint();
			if (onToggle != null)
			{
				onToggle.accept(checked);
			}
		}

		@Override
		public void doLayout()
		{
			int h = getHeight();
			Dimension bp = box.getPreferredSize();
			box.setBounds(PAD, (h - bp.height) / 2, bp.width, bp.height);
			int right = getWidth() - PAD;
			if (badge != null)
			{
				Dimension ip = badge.getPreferredSize();
				badge.setBounds(right - ip.width, (h - ip.height) / 2, ip.width, ip.height);
				right -= ip.width + GAP;
			}
			// the label owns the full row height and centers its own ink in it
			int x = PAD + bp.width + GAP;
			label.setBounds(x, 0, Math.max(0, right - x), h);
		}

		@Override
		public Dimension getPreferredSize()
		{
			// tall enough for PAD above and below the box; odd, so the ink
			// block and the odd-sized box both center exactly
			return new Dimension(0, Math.max(box.getPreferredSize().height + 2 * PAD,
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
