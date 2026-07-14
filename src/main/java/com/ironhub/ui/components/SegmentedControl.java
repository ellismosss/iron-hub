package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;

/**
 * Segmented control: 1 px frame, selected segment = accent bg + dark bold
 * text (interaction color — selection, never status). Two layouts:
 * stretch (full-width, equal segments — Melee/Range/Mage) and content-sized
 * (Tree/Checklist, ⊞/☰ view toggle).
 */
public class SegmentedControl extends JPanel
{
	private final boolean stretch;
	private final List<JLabel> segments = new ArrayList<>();
	private int selected;
	private IntConsumer onChange;

	public SegmentedControl(boolean stretch, String... labels)
	{
		this.stretch = stretch;
		setLayout(stretch ? new GridLayout(1, labels.length)
			: new BoxLayout(this, BoxLayout.X_AXIS));
		setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
		setBackground(UiTokens.CARD_BG);
		setAlignmentX(Component.LEFT_ALIGNMENT);

		for (int i = 0; i < labels.length; i++)
		{
			final int index = i;
			JLabel segment = new JLabel(labels[i], SwingConstants.CENTER);
			segment.setOpaque(true);
			// width measured with the bold (selected) font so segments
			// don't shift as the selection moves
			Font bold = segment.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY);
			int width = segment.getFontMetrics(bold).stringWidth(labels[i]) + 2 * UiTokens.PAD;
			Dimension size = new Dimension(stretch ? 0 : width, UiTokens.SEGMENTED_HEIGHT);
			segment.setPreferredSize(size);
			if (!stretch)
			{
				segment.setMaximumSize(size);
			}
			segment.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			segment.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					setSelected(index);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (index != selected)
					{
						segment.setForeground(UiTokens.TEXT_PRIMARY);
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					if (index != selected)
					{
						segment.setForeground(UiTokens.GLYPH_MUTED);
					}
				}
			});
			segments.add(segment);
			add(segment);
		}
		style();
	}

	/** ⊞/☰ toggle — every gridded section carries one; index 0 = grid. */
	public static SegmentedControl viewToggle()
	{
		SegmentedControl toggle = new SegmentedControl(false, "  ", "  ");
		int iconSize = (int) UiTokens.FONT_SIZE_LABEL;
		toggle.segments.get(0).setText(null);
		toggle.segments.get(0).setIcon(new PaintedIcon(PaintedIcon.Shape.GRID, iconSize));
		toggle.segments.get(1).setText(null);
		toggle.segments.get(1).setIcon(new PaintedIcon(PaintedIcon.Shape.LIST, iconSize));
		toggle.segments.get(0).setToolTipText("Grid view");
		toggle.segments.get(1).setToolTipText("List view");
		return toggle;
	}

	public void setSelected(int index)
	{
		if (index == selected)
		{
			return;
		}
		selected = index;
		style();
		if (onChange != null)
		{
			onChange.accept(index);
		}
	}

	public int getSelected()
	{
		return selected;
	}

	public void onChange(IntConsumer listener)
	{
		this.onChange = listener;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(stretch ? Integer.MAX_VALUE : getPreferredSize().width,
			getPreferredSize().height);
	}

	private void style()
	{
		for (int i = 0; i < segments.size(); i++)
		{
			JLabel segment = segments.get(i);
			boolean isSelected = i == selected;
			segment.setBackground(isSelected ? UiTokens.ACCENT : UiTokens.CARD_BG);
			segment.setForeground(isSelected ? UiTokens.ACCENT_TEXT_ON : UiTokens.GLYPH_MUTED);
			segment.setFont(segment.getFont().deriveFont(
				isSelected ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		}
	}
}
