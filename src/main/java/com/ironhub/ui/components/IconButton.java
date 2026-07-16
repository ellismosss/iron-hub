package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;

/**
 * 18×18 icon button (⚑ Path / W Wiki) — hover turns glyph + border accent.
 * Tooltip is mandatory: the glyph alone is not self-explanatory.
 */
public class IconButton extends JLabel
{
	public IconButton(String glyph, String tooltip, Runnable onClick)
	{
		super(glyph, SwingConstants.CENTER);
		init(tooltip, onClick);
	}

	public IconButton(javax.swing.Icon icon, String tooltip, Runnable onClick)
	{
		super(icon);
		setHorizontalAlignment(SwingConstants.CENTER);
		init(tooltip, onClick);
	}

	private void init(String tooltip, Runnable onClick)
	{
		setOpaque(true);
		setBackground(UiTokens.ICON_BUTTON_BG);
		setForeground(UiTokens.GLYPH_MUTED);
		setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
		setFont(getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		setToolTipText(tooltip);
		Dimension size = new Dimension(UiTokens.ICON_BUTTON_SIZE, UiTokens.ICON_BUTTON_SIZE);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setForeground(UiTokens.ACCENT);
				setBorder(new LineBorder(UiTokens.ACCENT));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setForeground(UiTokens.GLYPH_MUTED);
				setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onClick != null)
				{
					onClick.run();
				}
			}
		});
	}

	public static IconButton path(Runnable onClick)
	{
		return new IconButton(
			new PaintedIcon(PaintedIcon.Shape.FLAG, (int) UiTokens.FONT_SIZE_LABEL),
			"Route via Shortest Path", onClick);
	}

	public static IconButton wiki(Runnable onClick)
	{
		IconButton b = new IconButton("W", "Open wiki page", onClick);
		b.setFont(b.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_LABEL));
		return b;
	}

	/** A wider "Skip" text button (the 18px square doesn't fit the word). */
	public static IconButton skip(Runnable onClick)
	{
		IconButton b = new IconButton("Skip", "Skip this stop", onClick);
		Dimension size = new Dimension(36, UiTokens.ICON_BUTTON_SIZE);
		b.setPreferredSize(size);
		b.setMinimumSize(size);
		b.setMaximumSize(size);
		return b;
	}
}
