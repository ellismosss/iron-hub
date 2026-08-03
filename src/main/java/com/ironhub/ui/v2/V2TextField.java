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
	/**
	 * Whether the magnifier leads the field.
	 *
	 * <p>It used to be unconditional, which put a SEARCH icon on a slayer note,
	 * a page number and a supply target — none of which are searches, and the
	 * icon's reserved column also squeezed a 40px numeric box down to nothing
	 * so its digits clipped (Luke's Bank pass, 2026-07-26). Use
	 * {@link #plain} for an entry field that is not a search.
	 */
	private final boolean magnifier;

	public V2TextField(OsrsTheme theme, String placeholder, Runnable onChange)
	{
		this(theme, placeholder, onChange, true);
	}

	/** An entry field with no magnifier — a note, a number, a name. */
	public static V2TextField plain(OsrsTheme theme, String placeholder, Runnable onChange)
	{
		return new V2TextField(theme, placeholder, onChange, false);
	}

	private V2TextField(OsrsTheme theme, String placeholder, Runnable onChange,
		boolean magnifier)
	{
		this.theme = theme;
		this.placeholder = placeholder;
		this.magnifier = magnifier;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new java.awt.BorderLayout());

		int icon = magnifier ? V2Sprites.meta(SEARCH).width() + V2Tokens.PAD : V2Tokens.PAD;
		field.setOpaque(false);
		field.setBorder(new EmptyBorder(V2Tokens.TIGHT + 1, icon,
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
		if (magnifier)
		{
			BufferedImage icon = V2Sprites.get(theme, SEARCH);
			g2.drawImage(icon, V2Tokens.PAD, (getHeight() - icon.getHeight()) / 2, null);
		}
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

	/** 0 = fluid (the default full-width field). */
	private int fixedWidth;

	/** Pin the field to one width — the sizes here override the setXxxSize
	 *  setters, so a small box (a page number) needs this, not those. */
	public V2TextField width(int width)
	{
		this.fixedWidth = width;
		return this;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(fixedWidth > 0 ? fixedWidth : V2Tokens.CONTENT_WIDTH, HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(fixedWidth > 0 ? fixedWidth : Integer.MAX_VALUE, HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		if (fixedWidth > 0)
		{
			return new Dimension(fixedWidth, HEIGHT);
		}
		// a plain field may be a three-digit box; a search never is
		return new Dimension(magnifier ? 4 * V2Tokens.SECTION : 2 * V2Tokens.SECTION, HEIGHT);
	}
}
