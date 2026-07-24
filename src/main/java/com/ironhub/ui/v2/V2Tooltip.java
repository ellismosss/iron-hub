package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JToolTip;

/**
 * The hover explanation, on a Card (§9). Detail that doesn't fit 225px lives
 * in one of these, so a tooltip is a first-class part of the layout budget
 * rather than an afterthought.
 *
 * <p>Swing routes tooltips through {@code JComponent.createToolTip()}, so an
 * atom that wants the V2 look overrides that to return one of these — which
 * is why {@link #install} exists: it sets the text and nothing else, and the
 * atom's own override does the rest.
 */
public class V2Tooltip extends JToolTip
{
	/** Fits inside the 225px panel with the Card's insets — a tooltip floats
	 *  free of the panel, but one that reads wider than the panel it explains
	 *  looks like a different application. */
	private static final int MAX_WIDTH = 180;

	private final OsrsTheme theme;
	private final NineSlice card = V2Tokens.card();
	private com.ironhub.ui.osrs.OsrsLabel body;

	public V2Tooltip(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		setLayout(new java.awt.BorderLayout());
		int inset = V2Tokens.SLICE_INSET + V2Tokens.PAD;
		setBorder(new javax.swing.border.EmptyBorder(inset, inset, inset, inset));
	}

	@Override
	public void setTipText(String tipText)
	{
		super.setTipText(tipText);
		removeAll();
		if (tipText != null && !tipText.isEmpty())
		{
			body = V2Label.wrappedDetail(tipText, MAX_WIDTH);
			add(body, java.awt.BorderLayout.CENTER);
		}
		else
		{
			body = null;
		}
	}

	/** Give a component V2 tooltip text. The atom's {@code createToolTip}
	 *  override is what makes it wear the Card. */
	public static void install(JComponent component, String text)
	{
		component.setToolTipText(text);
	}

	/**
	 * The Card, and nothing else. Deliberately does NOT call
	 * {@code super.paintComponent}: that delegates to {@code BasicToolTipUI},
	 * which draws the tip string itself in the look-and-feel's font at its
	 * own origin — which rendered as a second copy of the text spilling out
	 * of the card (the render caught it). The wrapped V2 label child is the
	 * text, and it paints through {@code paintChildren}.
	 */
	@Override
	protected void paintComponent(Graphics g)
	{
		card.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
	}

	@Override
	public Dimension getPreferredSize()
	{
		if (body == null)
		{
			return new Dimension(0, 0);
		}
		Dimension text = body.getPreferredSize();
		int pad = 2 * (V2Tokens.SLICE_INSET + V2Tokens.PAD);
		return new Dimension(text.width + pad, text.height + pad);
	}

	/**
	 * A tooltip is exactly as big as its text. Without this a surrounding
	 * BoxLayout can hand it less height than it asked for and clip the last
	 * line — which is what happened the first time this rendered, since a
	 * horizontal glue in the row constrained the row's height.
	 */
	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}
}
