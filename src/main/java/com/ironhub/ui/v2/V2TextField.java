package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/**
 * The search field, on the game's OWN field well — the three-piece strip it
 * draws behind every Filters dropdown and search box (Luke, 2026-07-25; the
 * pieces came out of all_sprites, where the game ships them as
 * {@code ge/number_field_*}). The magnifier and Swing's text editing sit
 * inside it.
 *
 * <p>Three details here are hard-won and must not be re-derived:
 *
 * <ul>
 * <li>{@link OsrsSkin#crisp} is mandatory. Setting the antialias hint on the
 * Graphics is not enough — Swing re-applies the look-and-feel's own setting
 * over it, which smears the pixel font with LCD subpixel fringes.
 * <li>Text needs a pixel of extra padding at the top. Swing centres by the
 * font's em box, and this font's ink floats high in it, so an em-centred
 * field reads 1-2px high.
 * <li>The placeholder draws at the field's own baseline, so it sits exactly
 * where the typed text will — anything else makes the text jump on the first
 * keystroke.
 * </ul>
 */
public class V2TextField extends JPanel
{
	private static final String SEARCH = "icons/search/search_1";
	private static final int HEIGHT = 20;

	private final OsrsTheme theme;
	private final V2Well well = V2Tokens.well();
	private final JTextField field = new JTextField();
	private final String placeholder;

	public V2TextField(OsrsTheme theme, String placeholder, Runnable onChange)
	{
		this.theme = theme;
		this.placeholder = placeholder;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new java.awt.BorderLayout());

		int icon = V2Sprites.meta(SEARCH).width();
		field.setOpaque(false);
		field.setBorder(new EmptyBorder(V2Tokens.TIGHT + 1, icon + V2Tokens.PAD,
			V2Tokens.TIGHT, V2Tokens.PAD));
		field.setFont(V2Tokens.bodyFont());
		field.setForeground(V2Tokens.TEXT);
		field.setCaretColor(V2Tokens.TEXT);
		OsrsSkin.crisp(field);
		add(field, java.awt.BorderLayout.CENTER);

		field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				changed();
			}

			private void changed()
			{
				repaint();
				if (onChange != null)
				{
					onChange.run();
				}
			}
		});
	}

	public String getText()
	{
		return field.getText();
	}

	public void setText(String text)
	{
		field.setText(text);
	}

	/** The editor itself, for focus and key handling. */
	public JTextField editor()
	{
		return field;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		well.paint(g2, theme, 0, 0, getWidth(), getHeight());
		BufferedImage icon = V2Sprites.get(theme, SEARCH);
		g2.drawImage(icon, V2Tokens.PAD, (getHeight() - icon.getHeight()) / 2, null);
		super.paintComponent(g);
	}

	@Override
	protected void paintChildren(Graphics g)
	{
		super.paintChildren(g);
		if (!field.getText().isEmpty() || placeholder == null)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(V2Tokens.bodyFont());
		g2.setColor(V2Tokens.FAINT);
		// the field's OWN baseline, so the placeholder sits exactly where the
		// typed text will and nothing jumps on the first keystroke
		int baseline = field.getY() + field.getBaseline(field.getWidth(), field.getHeight());
		g2.drawString(placeholder, field.getX() + field.getInsets().left, baseline);
		g2.dispose();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(4 * V2Tokens.SECTION, HEIGHT);
	}
}
