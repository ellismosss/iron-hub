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
		OsrsSkin.crisp(this);
		setOpaque(true);
		setBackground(theme.fieldFill);
		// typed text reads as body copy, not as a game label (Luke): the
		// game's own field types in orange, but the sidebar's does not
		setForeground(OsrsSkin.MUTED);
		setCaretColor(OsrsSkin.MUTED);
		setSelectionColor(theme.selectFill);
		setSelectedTextColor(OsrsSkin.TITLE);
		setFont(OsrsSkin.font());
		setAlignmentX(LEFT_ALIGNMENT);
		// asymmetric padding by 1: Swing centres text by the FONT'S EM BOX,
		// and the RuneScape font's ink floats high inside it, so an
		// em-centred field reads a pixel high (measured). Paying the pixel
		// here centres the INK, which is what the eye reads.
		setBorder(new CompoundBorder(
			new CompoundBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark),
				new MatteBorder(1, 1, 1, 1, theme.fieldEdge)),
			new EmptyBorder(3, 4, 1, 4)));
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
		g2.setColor(OsrsSkin.FAINT);
		// the field's own baseline, so the placeholder sits exactly where
		// the text it stands in for will — never a hand-guessed offset
		g2.drawString(placeholder, getInsets().left, getBaseline(getWidth(), getHeight()));
	}
}
