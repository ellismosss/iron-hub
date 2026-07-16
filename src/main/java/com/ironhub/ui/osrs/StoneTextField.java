package com.ironhub.ui.osrs;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * The game's text input: a sunken field — 1px dark outline over a slightly
 * lighter inner line, dark interior, orange text. Anatomy sampled from the
 * wiki's File:Settings_interface.png search box (#261D11 outline, #31281C
 * inner, #372E22 interior, #FF981F text); MYSTIC's interior comes from the
 * pack's own overrides.toml (item_search.background #141414).
 *
 * <p>Antialiasing is forced off before the caret and text paint, or the
 * pixel font smears — Swing text components would otherwise use the LAF's
 * own hints.
 */
public class StoneTextField extends JTextField
{
	private static final int HEIGHT = 22;

	private final String placeholder;

	public StoneTextField(OsrsTheme theme, String placeholder)
	{
		this.placeholder = placeholder;
		setOpaque(true);
		setBackground(theme.fieldFill);
		setForeground(OsrsSkin.LABEL);
		setCaretColor(OsrsSkin.LABEL);
		setSelectionColor(theme.selectFill);
		setSelectedTextColor(OsrsSkin.TITLE);
		setFont(OsrsSkin.font());
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(new CompoundBorder(
			new CompoundBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark),
				new MatteBorder(1, 1, 1, 1, theme.fieldEdge)),
			new EmptyBorder(2, 4, 2, 4)));
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		super.paintComponent(g);

		if (!getText().isEmpty() || placeholder == null)
		{
			return;
		}
		g2.setFont(getFont());
		g2.setColor(OsrsSkin.MUTED);
		java.awt.Insets in = getInsets();
		g2.drawString(placeholder, in.left, in.top + g2.getFontMetrics().getAscent() - 2);
	}
}
