package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Alert chip: 10 px bold status-colored text with a matching 1 px border
 * ("4 dailies", "! runway 6 h"). Clicks navigate to the owning module —
 * consumers wire the listener.
 */
public class AlertChip extends JLabel
{
	public AlertChip(String text, Status status)
	{
		super(text);
		Color color = status.color();
		setOpaque(true);
		setBackground(UiTokens.CARD_BG);
		setForeground(color);
		setFont(getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL));
		// mockup padding 3px 7px (inside the 1 px border)
		setBorder(new CompoundBorder(new LineBorder(color), new EmptyBorder(3, 7, 3, 7)));
	}
}
