package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
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
	/** Narrow on purpose: a tooltip should cover as little as possible. */
	private static final int MAX_WIDTH = 160;
	/** The whole padding — a tooltip has no surface art to clear. */
	private static final int PAD = V2Tokens.TIGHT;

	private final OsrsTheme theme;
	private com.ironhub.ui.osrs.OsrsLabel body;

	public V2Tooltip(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		// JToolTip defaults to CENTER_ALIGNMENT, and BoxLayout aligns a
		// column by making its children's alignment points coincide: this one
		// centre-aligned component pushed every left-aligned sibling in
		// Design lab V2 sixty pixels right. Every V2 atom claims LEFT.
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new java.awt.BorderLayout());
		setBorder(new javax.swing.border.EmptyBorder(PAD, PAD + 1, PAD, PAD + 1));
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
	 * A filled rectangle with a 1px border, and nothing else (Luke,
	 * 2026-07-25: "just draw a simple bordered rectangle using Detail font").
	 * A tooltip covers what you are pointing at, so its job is to be small.
	 *
	 * <p>Deliberately does NOT call {@code super.paintComponent}: that
	 * delegates to {@code BasicToolTipUI}, which draws the tip string itself
	 * in the look-and-feel's font — a second copy of the text spilling out of
	 * the box (the render caught it). The wrapped label child is the text.
	 */
	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(theme.background);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setColor(theme.edgeDark);
		g.drawRect(0, 0, getWidth() - 1, getHeight() - 1); // v2-exempt: 1px box
	}

	@Override
	public Dimension getPreferredSize()
	{
		if (body == null)
		{
			return new Dimension(0, 0);
		}
		Dimension text = body.getPreferredSize();
		return new Dimension(text.width + 2 * PAD + 2, text.height + 2 * PAD);
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
