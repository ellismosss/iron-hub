package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/**
 * A multi-line entry area on the game's field well — {@link V2TextField}'s
 * taller sibling (S5, 2026-08-03: the slayer note outgrew one line). Wraps
 * at word boundaries, grows with its text between a minimum and a maximum
 * row count, and follows the field's hard-won text rules: OsrsSkin.crisp,
 * the +1px top pad for this font's high-floating ink, and the placeholder
 * drawn at the editor's own baseline.
 */
public class V2TextArea extends JPanel
{
	private static final int MIN_ROWS = 3;
	private static final int MAX_ROWS = 8;

	private final OsrsTheme theme;
	private final V2Well well = V2Tokens.well();
	private final JTextArea area = new JTextArea();
	private final String placeholder;

	public V2TextArea(OsrsTheme theme, String placeholder, Runnable onChange)
	{
		this.theme = theme;
		this.placeholder = placeholder;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new java.awt.BorderLayout());

		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setRows(MIN_ROWS);
		area.setBorder(new EmptyBorder(V2Tokens.TIGHT + 1, V2Tokens.PAD,
			V2Tokens.TIGHT, V2Tokens.PAD));
		area.setFont(V2Tokens.bodyFont());
		area.setForeground(V2Tokens.TEXT);
		area.setCaretColor(V2Tokens.TEXT);
		OsrsSkin.crisp(area);
		add(area, java.awt.BorderLayout.CENTER);

		area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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
				revalidate();
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
		return area.getText();
	}

	public void setText(String text)
	{
		area.setText(text);
	}

	/** The editor itself, for focus and key handling. */
	public JTextArea editor()
	{
		return area;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		well.paint(g2(g), theme, 0, 0, getWidth(), getHeight());
		super.paintComponent(g);
	}

	private Graphics2D g2(Graphics g)
	{
		return (Graphics2D) g;
	}

	@Override
	protected void paintChildren(Graphics g)
	{
		super.paintChildren(g);
		if (!area.getText().isEmpty() || placeholder == null)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setFont(V2Tokens.bodyFont());
		g2.setColor(V2Tokens.FAINT);
		java.awt.FontMetrics fm = g2.getFontMetrics();
		g2.drawString(placeholder, area.getX() + area.getInsets().left,
			area.getY() + area.getInsets().top + fm.getAscent());
		g2.dispose();
	}

	/** Height tracks the text: at least MIN_ROWS, at most MAX_ROWS, then
	 *  the area scrolls its caret into view as Swing text areas do. */
	private int rowsHeight()
	{
		int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
		int rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, area.getLineCount()));
		return rows * lineHeight + V2Tokens.TIGHT * 2 + 2;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, rowsHeight());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, rowsHeight());
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(4 * V2Tokens.SECTION, rowsHeight());
	}
}
