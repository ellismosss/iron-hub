package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * The text button — the game's own, {@code regular_large}, 9-sliced to
 * whatever width the panel gives it and fixed at its native 28px height.
 *
 * <p>It has <b>no hover and no pressed state</b>, because the curated set has
 * neither: the game drew one button sprite and no second state for it. Under
 * §8 that is the answer rather than a problem to paper over with a tint. The
 * hand cursor is the affordance.
 *
 * <p>A button that needs to show a state is not this atom — it is a
 * {@link V2SpriteButton} (which offers exactly the states its art has) or a
 * chip on the Card family (which has a hovered sprite).
 */
public class V2Button extends JPanel
{
	private final OsrsTheme theme;
	private final NineSlice slice;
	private final OsrsLabel label;

	public V2Button(OsrsTheme theme, String text, Runnable onPress)
	{
		this.theme = theme;
		this.slice = V2Tokens.button();
		this.label = V2Label.centred(text);
		setOpaque(false);
		setLayout(new BorderLayout());
		setAlignmentX(LEFT_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add(label, BorderLayout.CENTER);
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onPress != null)
				{
					onPress.run();
				}
			}
		});
	}

	/** Recolour the label — a saved-green confirmation, a blocked warning.
	 *  Status colours only; the button art itself never changes. */
	public V2Button labelColor(java.awt.Color color)
	{
		label.setColor(color);
		return this;
	}

	public String text()
	{
		return label.text();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		slice.paint((Graphics2D) g, theme, 0, 0, getWidth(), getHeight());
		super.paintComponent(g);
	}

	@Override
	public Dimension getPreferredSize()
	{
		BufferedImage art = V2Sprites.get(theme, "ui/buttons/regular_large");
		return new Dimension(
			Math.max(art.getWidth(), label.getPreferredSize().width + 4 * V2Tokens.PAD),
			V2Tokens.BUTTON_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, V2Tokens.BUTTON_HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(2 * slice.inset(), V2Tokens.BUTTON_HEIGHT);
	}
}
