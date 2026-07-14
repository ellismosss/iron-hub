package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * 24 px search field: inset background, border turns accent while focused,
 * faint placeholder text until the user types.
 */
public class SearchField extends JTextField
{
	private final String placeholder;

	public SearchField(String placeholder)
	{
		this.placeholder = placeholder;
		setAlignmentX(LEFT_ALIGNMENT);
		setBackground(UiTokens.INSET_BG);
		setForeground(UiTokens.TEXT_BODY);
		setCaretColor(UiTokens.TEXT_BODY);
		setFont(getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		setBorder(fieldBorder(UiTokens.BORDER));

		addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				setBorder(fieldBorder(UiTokens.ACCENT));
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				setBorder(fieldBorder(UiTokens.BORDER));
			}
		});
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, UiTokens.SEARCH_FIELD_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, UiTokens.SEARCH_FIELD_HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (getText().isEmpty())
		{
			g.setColor(UiTokens.TEXT_FAINT);
			g.setFont(getFont());
			int baseline = g.getFontMetrics().getAscent()
				+ (getHeight() - g.getFontMetrics().getHeight()) / 2;
			g.drawString(placeholder, getInsets().left, baseline);
		}
	}

	private static CompoundBorder fieldBorder(java.awt.Color line)
	{
		return new CompoundBorder(new LineBorder(line), new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD));
	}
}
