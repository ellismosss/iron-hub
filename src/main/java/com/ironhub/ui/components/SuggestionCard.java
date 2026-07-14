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
import javax.swing.border.LineBorder;

/**
 * Ranked suggestion card (frames 1b/What Now): accent rank number, bold
 * title + right duration, one muted ellipsized "why" line (full text in
 * tooltip — explainability principle), chevron; hover = accent border.
 */
public class SuggestionCard extends JPanel
{
	public SuggestionCard(int rank, String title, String duration, String why)
	{
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setBackground(UiTokens.CARD_BG);
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(cardBorder(UiTokens.BORDER_ROW));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel rankLabel = new JLabel(String.valueOf(rank), SwingConstants.CENTER);
		rankLabel.setForeground(UiTokens.ACCENT);
		rankLabel.setFont(rankLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		rankLabel.setPreferredSize(new Dimension(UiTokens.STATUS_GLYPH_SIZE, 0));
		rankLabel.setMaximumSize(new Dimension(UiTokens.STATUS_GLYPH_SIZE, Integer.MAX_VALUE));
		add(rankLabel);
		add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		JPanel titleLine = new JPanel();
		titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
		titleLine.setOpaque(false);
		titleLine.setAlignmentX(LEFT_ALIGNMENT);
		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(UiTokens.TEXT_PRIMARY);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		titleLabel.setToolTipText(title);
		titleLabel.setMinimumSize(new Dimension(0, 0));
		titleLine.add(titleLabel);
		titleLine.add(Box.createHorizontalGlue());
		JLabel durationLabel = new JLabel(duration);
		durationLabel.setForeground(UiTokens.TEXT_MUTED);
		durationLabel.setFont(durationLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		titleLine.add(durationLabel);
		text.add(titleLine);

		JLabel whyLabel = new JLabel(why);
		whyLabel.setForeground(UiTokens.TEXT_MUTED);
		whyLabel.setFont(whyLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		whyLabel.setToolTipText(why);
		whyLabel.setMinimumSize(new Dimension(0, 0));
		whyLabel.setAlignmentX(LEFT_ALIGNMENT);
		text.add(whyLabel);
		add(text);
		add(Box.createHorizontalGlue());

		JLabel chevron = new JLabel(new PaintedIcon(
			PaintedIcon.Shape.CHEVRON_RIGHT, (int) UiTokens.FONT_SIZE_LABEL));
		chevron.setForeground(UiTokens.TEXT_FAINT);
		add(chevron);

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setBorder(cardBorder(UiTokens.ACCENT));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setBorder(cardBorder(UiTokens.BORDER_ROW));
			}
		});
	}

	private static CompoundBorder cardBorder(java.awt.Color line)
	{
		return new CompoundBorder(new LineBorder(line),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
