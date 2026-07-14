package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;

/**
 * Row of equal-width selectable chips with small gaps (dashboard time
 * selector, module filter chips). Unlike SegmentedControl the chips are
 * separate bordered pills; selected = accent bg + dark bold text.
 * Four chips is the maximum that fits at 225 px.
 */
public class ChipRow extends JPanel
{
	private final List<JLabel> chips = new ArrayList<>();
	private int selected;
	private IntConsumer onChange;

	public ChipRow(String... labels)
	{
		setLayout(new GridLayout(1, labels.length, UiTokens.CHIP_GAP, 0));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		for (int i = 0; i < labels.length; i++)
		{
			final int index = i;
			JLabel chip = new JLabel(labels[i], SwingConstants.CENTER);
			chip.setOpaque(true);
			chip.setPreferredSize(new Dimension(0, UiTokens.SEGMENTED_HEIGHT));
			chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			chip.addMouseListener(new MouseAdapter()
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
						chip.setBorder(new LineBorder(UiTokens.ACCENT));
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					if (index != selected)
					{
						chip.setBorder(new LineBorder(UiTokens.BORDER_DIM));
					}
				}
			});
			chips.add(chip);
			add(chip);
		}
		style();
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
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	private void style()
	{
		for (int i = 0; i < chips.size(); i++)
		{
			JLabel chip = chips.get(i);
			boolean isSelected = i == selected;
			chip.setBackground(isSelected ? UiTokens.ACCENT : UiTokens.CARD_BG);
			chip.setForeground(isSelected ? UiTokens.ACCENT_TEXT_ON : UiTokens.GLYPH_MUTED);
			chip.setBorder(new LineBorder(isSelected ? UiTokens.ACCENT : UiTokens.BORDER_DIM));
			chip.setFont(chip.getFont().deriveFont(
				isSelected ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		}
	}
}
