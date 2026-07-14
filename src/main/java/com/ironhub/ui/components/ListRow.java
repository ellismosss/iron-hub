package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * The shared list row atom (mockup frame 1a §5):
 * status glyph · name (flex, ellipsize) · optional right value · icon buttons.
 *
 * Locked rows are two-line — the blocking requirement moves to a muted
 * "needs:" second line because it does not fit inline at 225 px.
 * Warning rows tint the border and show a red right value.
 */
public class ListRow extends JPanel
{
	private ListRow(Status status, String name, String rightValue, String needs, IconButton... buttons)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.CARD_BG);
		boolean twoLine = needs != null;

		LineBorder frame = new LineBorder(status == Status.WARNING
			? UiTokens.BORDER_WARNING_ROW : UiTokens.BORDER_ROW);
		setBorder(new CompoundBorder(frame, twoLine
			? new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)
			: new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));

		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);

		JLabel glyph = new JLabel(status.glyph());
		glyph.setHorizontalAlignment(SwingConstants.CENTER);
		line.add(glyph);
		line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(nameLabel.getFont().deriveFont(
			status == Status.AVAILABLE ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		nameLabel.setForeground(nameColor(status));
		nameLabel.setToolTipText(name); // names truncate at ~120 px with both buttons
		nameLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		nameLabel.setMinimumSize(new Dimension(0, 0)); // ellipsize rather than clip the buttons
		line.add(nameLabel);
		line.add(Box.createHorizontalGlue());

		if (rightValue != null)
		{
			line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			JLabel value = new JLabel(rightValue);
			value.setFont(value.getFont().deriveFont(
				status == Status.WARNING ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			value.setForeground(status == Status.WARNING ? UiTokens.STATUS_WARNING : UiTokens.TEXT_MUTED);
			line.add(value);
		}

		for (IconButton button : buttons)
		{
			line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			line.add(button);
		}

		if (!twoLine)
		{
			// 26 px total: 24 px content inside the 1 px frame
			int innerHeight = UiTokens.ROW_HEIGHT - 2;
			line.setPreferredSize(new Dimension(0, innerHeight));
			line.setMinimumSize(new Dimension(0, innerHeight));
		}
		add(line);

		if (twoLine)
		{
			JLabel needsLabel = new JLabel("needs: " + needs);
			needsLabel.setFont(needsLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			needsLabel.setForeground(UiTokens.TEXT_MUTED);
			needsLabel.setToolTipText("needs: " + needs);
			needsLabel.setAlignmentX(LEFT_ALIGNMENT);
			// indent past the glyph column
			needsLabel.setBorder(new EmptyBorder(2, UiTokens.STATUS_GLYPH_SIZE + UiTokens.ROW_GAP, 0, 0));
			add(needsLabel);
		}

		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		// full width, fixed height — rows never stretch vertically in a column
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	public static ListRow owned(String name, IconButton... buttons)
	{
		return new ListRow(Status.OWNED, name, null, null, buttons);
	}

	public static ListRow available(String name, IconButton... buttons)
	{
		return new ListRow(Status.AVAILABLE, name, null, null, buttons);
	}

	public static ListRow locked(String name, String needs, IconButton... buttons)
	{
		return new ListRow(Status.LOCKED, name, null, needs, buttons);
	}

	public static ListRow warning(String name, String rightValue, IconButton... buttons)
	{
		return new ListRow(Status.WARNING, name, rightValue, null, buttons);
	}

	private static java.awt.Color nameColor(Status status)
	{
		switch (status)
		{
			case AVAILABLE:
				return UiTokens.TEXT_PRIMARY;
			case LOCKED:
				return UiTokens.TEXT_MUTED;
			default:
				return UiTokens.TEXT_BODY;
		}
	}
}
