package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Labeled 3-column tile (mockup frame 2f, BANKED XP): 22 px icon + 9 px name
 * + 9 px bold value in a status color + optional mini progress bar.
 * Three columns fit 225 px; hover shows an accent border.
 */
public class LabeledTile extends JPanel
{
	public LabeledTile(String code, String name, String value, Color valueColor,
		HubProgressBar miniBar, String tooltip)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.CARD_BG);
		setBorder(tileBorder(UiTokens.BORDER_ROW));
		setToolTipText(tooltip);

		JLabel icon = new JLabel(code, SwingConstants.CENTER);
		icon.setOpaque(true);
		icon.setBackground(UiTokens.BORDER); // sprite placeholder square
		icon.setForeground(UiTokens.GLYPH_MUTED);
		icon.setFont(icon.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_CODE));
		Dimension iconSize = new Dimension(UiTokens.TILE_ICON_SIZE, UiTokens.TILE_ICON_SIZE);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		icon.setAlignmentX(Component.CENTER_ALIGNMENT);
		add(icon);
		add(javax.swing.Box.createVerticalStrut(2));

		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_TILE_LABEL));
		nameLabel.setForeground(UiTokens.TEXT_BODY);
		nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		add(nameLabel);

		if (value != null)
		{
			JLabel valueLabel = new JLabel(value);
			valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_LABEL));
			valueLabel.setForeground(valueColor);
			valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			add(valueLabel);
		}

		if (miniBar != null)
		{
			add(javax.swing.Box.createVerticalStrut(2));
			miniBar.setAlignmentX(Component.CENTER_ALIGNMENT);
			add(miniBar);
		}

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setBorder(tileBorder(UiTokens.ACCENT));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setBorder(tileBorder(UiTokens.BORDER_ROW));
			}
		});
	}

	private static CompoundBorder tileBorder(Color line)
	{
		// mockup padding 5px 2px 4px
		return new CompoundBorder(new LineBorder(line), new EmptyBorder(5, 2, 4, 2));
	}
}
