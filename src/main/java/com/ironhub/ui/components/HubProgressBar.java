package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;

/**
 * Flat progress bar. Standard: 10 px, inset trough + 1 px border, accent
 * fill, green when complete. Mini: 3–4 px, borderless, amber status fill
 * (green when complete) — used inside rows and labeled tiles.
 */
public class HubProgressBar extends JComponent
{
	private final boolean mini;
	private double fraction;

	private HubProgressBar(boolean mini, double fraction, int width)
	{
		this.mini = mini;
		this.fraction = clamp(fraction);
		setAlignmentX(LEFT_ALIGNMENT);
		int height = mini ? UiTokens.MINI_BAR_HEIGHT : UiTokens.PROGRESS_BAR_HEIGHT;
		setPreferredSize(new Dimension(width, height));
		setMinimumSize(new Dimension(0, height));
		setMaximumSize(new Dimension(width > 0 ? width : Integer.MAX_VALUE, height));
	}

	/** Full-width 10 px bar. */
	public static HubProgressBar bar(double fraction)
	{
		return new HubProgressBar(false, fraction, 0);
	}

	/** Fixed-width mini bar (48 px in tiles, 40 px in list rows). */
	public static HubProgressBar mini(double fraction, int width)
	{
		return new HubProgressBar(true, fraction, width);
	}

	public void setFraction(double fraction)
	{
		this.fraction = clamp(fraction);
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth();
		int h = getHeight();
		g.setColor(UiTokens.INSET_BG);
		g.fillRect(0, 0, w, h);

		boolean complete = fraction >= 1.0;
		Color fill = complete ? UiTokens.STATUS_OWNED
			: (mini ? UiTokens.STATUS_AVAILABLE : UiTokens.ACCENT);
		g.setColor(fill);
		if (mini)
		{
			g.fillRect(0, 0, (int) Math.round(w * fraction), h);
		}
		else
		{
			g.fillRect(1, 1, (int) Math.round((w - 2) * fraction), h - 2);
			g.setColor(UiTokens.BORDER);
			g.drawRect(0, 0, w - 1, h - 1);
		}
	}

	private static double clamp(double f)
	{
		return Math.max(0, Math.min(1, f));
	}
}
