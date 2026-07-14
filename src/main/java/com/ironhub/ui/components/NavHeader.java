package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * 30 px navigation header: back arrow + 12 px bold title. Required on every
 * non-home screen (navigation depth ≤ 2).
 */
public class NavHeader extends JPanel
{
	public NavHeader(String title, Runnable onBack)
	{
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		setBackground(UiTokens.CARD_BG);
		setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD)));

		JLabel back = new JLabel("‹", SwingConstants.CENTER);
		back.setForeground(UiTokens.TEXT_MUTED);
		back.setFont(back.getFont().deriveFont(Font.PLAIN, UiTokens.STATUS_GLYPH_SIZE));
		back.setToolTipText("Back");
		Dimension backSize = new Dimension(UiTokens.ICON_BUTTON_SIZE, UiTokens.ICON_BUTTON_SIZE);
		back.setPreferredSize(backSize);
		back.setMinimumSize(backSize);
		back.setMaximumSize(backSize);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				back.setForeground(UiTokens.ACCENT);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				back.setForeground(UiTokens.TEXT_MUTED);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onBack != null)
				{
					onBack.run();
				}
			}
		});
		add(back);
		add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		titleLabel.setForeground(UiTokens.TEXT_PRIMARY);
		add(titleLabel);
		add(Box.createHorizontalGlue());
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, UiTokens.HEADER_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, UiTokens.HEADER_HEIGHT);
	}
}
