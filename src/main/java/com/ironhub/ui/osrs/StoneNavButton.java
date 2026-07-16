package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * The square stone button — the game's side-panel tab stone. Reserved for
 * NAVIGATION (per Luke: elsewhere a notched box reads cleaner). Selected
 * lifts the fill and brightens the bevel, exactly as the pack's
 * tab_stone_middle_selected sprite does.
 *
 * <p>Geometry follows that sprite: a chunky 3px bevel (dark, then light) with
 * a 4px stepped corner, icon centered.
 */
public class StoneNavButton extends JComponent
{
	private static final int SIZE = 33;
	private static final int CORNER = 4;

	private final OsrsTheme theme;
	private final Icon icon;
	private final Runnable onClick;
	private boolean selected;
	private boolean hover;

	public StoneNavButton(OsrsTheme theme, Icon icon, boolean selected, Runnable onClick)
	{
		this.theme = theme;
		this.icon = icon;
		this.selected = selected;
		this.onClick = onClick;
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				repaint();
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

	public void setSelected(boolean selected)
	{
		this.selected = selected;
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SIZE, SIZE + 3);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		int w = getWidth(), h = getHeight();
		Color fill = selected ? theme.selectFill : hover ? theme.hoverFill : theme.boxFill;
		Color bevel = selected ? theme.selectEdge : theme.edgeLight;

		g2.setColor(theme.background);
		g2.fillRect(0, 0, w, h);

		// stepped corners: clip the bevel box in by CORNER px diagonally
		for (int inset = 0; inset < 2; inset++)
		{
			g2.setColor(inset == 0 ? theme.edgeDark : bevel);
			paintRing(g2, inset, w, h);
		}
		g2.setColor(fill);
		fillBody(g2, 2, w, h);

		if (icon != null)
		{
			icon.paintIcon(this, g2, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
		}
	}

	/** One 1px ring of the chamfered box, `inset` px in from the edge. */
	private void paintRing(Graphics2D g2, int inset, int w, int h)
	{
		int c = CORNER - inset;
		g2.fillRect(inset + c, inset, w - 2 * (inset + c), 1);
		g2.fillRect(inset + c, h - 1 - inset, w - 2 * (inset + c), 1);
		g2.fillRect(inset, inset + c, 1, h - 2 * (inset + c));
		g2.fillRect(w - 1 - inset, inset + c, 1, h - 2 * (inset + c));
		for (int step = 0; step < c; step++)
		{
			int dx = inset + step, dy = inset + c - step;
			g2.fillRect(dx, dy, 1, 1);
			g2.fillRect(w - 1 - dx, dy, 1, 1);
			g2.fillRect(dx, h - 1 - dy, 1, 1);
			g2.fillRect(w - 1 - dx, h - 1 - dy, 1, 1);
		}
	}

	private void fillBody(Graphics2D g2, int inset, int w, int h)
	{
		int c = Math.max(0, CORNER - inset);
		g2.fillRect(inset, inset + c, w - 2 * inset, h - 2 * (inset + c));
		for (int step = 0; step < c; step++)
		{
			int y1 = inset + step;
			int pad = c - step;
			g2.fillRect(inset + pad, y1, w - 2 * (inset + pad), 1);
			g2.fillRect(inset + pad, h - 1 - y1, w - 2 * (inset + pad), 1);
		}
	}
}
