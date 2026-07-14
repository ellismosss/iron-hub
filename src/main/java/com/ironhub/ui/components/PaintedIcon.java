package com.ironhub.ui.components;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Tiny painted control glyphs (⚑ flag, ⊞ grid, ☰ list) — painted in the
 * host component's foreground so hover/selection recoloring applies.
 * Painted because font fallback for these codepoints is unreliable.
 */
public class PaintedIcon implements Icon
{
	public enum Shape
	{
		FLAG,
		GRID,
		LIST
	}

	private final Shape shape;
	private final int size;

	public PaintedIcon(Shape shape, int size)
	{
		this.shape = shape;
		this.size = size;
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.translate(x, y);
		g2.setColor(c.getForeground());

		switch (shape)
		{
			case FLAG: // pole + pennant
				g2.setStroke(new BasicStroke(1.2f));
				g2.drawLine(2, 0, 2, size - 1);
				Path2D pennant = new Path2D.Float();
				pennant.moveTo(3, 0.5);
				pennant.lineTo(size - 2, 2.5);
				pennant.lineTo(3, 4.5);
				pennant.closePath();
				g2.fill(pennant);
				break;
			case GRID: // 2×2 cells
				int cell = (size - 2) / 2;
				g2.fillRect(0, 0, cell, cell);
				g2.fillRect(cell + 2, 0, cell, cell);
				g2.fillRect(0, cell + 2, cell, cell);
				g2.fillRect(cell + 2, cell + 2, cell, cell);
				break;
			case LIST: // 3 lines
				g2.setStroke(new BasicStroke(1.4f));
				int step = (size - 2) / 2;
				for (int i = 0; i < 3; i++)
				{
					int ly = 1 + i * step;
					g2.drawLine(0, ly, size - 1, ly);
				}
				break;
		}
		g2.dispose();
	}

	@Override
	public int getIconWidth()
	{
		return size;
	}

	@Override
	public int getIconHeight()
	{
		return size;
	}
}
