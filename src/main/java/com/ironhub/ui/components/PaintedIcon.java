package com.ironhub.ui.components;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Tiny painted control glyphs (flag, grid, list, chevrons, triangles,
 * dots) — painted in the host component's foreground so hover/selection
 * recoloring applies. Painted because RuneLite's bundled RuneScape fonts
 * lack these codepoints entirely (they render as the boxed missing-glyph
 * character); only ASCII plus · … — × are safe as label text.
 */
public class PaintedIcon implements Icon
{
	public enum Shape
	{
		FLAG,
		PIN,
		CROSS,
		HEART,
		HEART_OUTLINE,
		GRID,
		LIST,
		CHEVRON_LEFT,
		CHEVRON_RIGHT,
		TRIANGLE_UP,
		TRIANGLE_DOWN,
		TRIANGLE_RIGHT,
		DOTS_VERTICAL
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
			case PIN: // a pushpin seen head-on: round head, tapering needle
			{
				float s = size;
				float cx = s * 0.5f;
				float headR = s * 0.26f;
				float headCy = s * 0.34f;
				// needle
				g2.setStroke(new BasicStroke(Math.max(1f, s * 0.09f)));
				g2.draw(new java.awt.geom.Line2D.Float(cx, headCy + headR * 0.6f, cx, s - 0.5f));
				// head
				g2.fill(new java.awt.geom.Ellipse2D.Float(cx - headR, headCy - headR, headR * 2, headR * 2));
				// a small highlight nick so the head reads as round, not a dot
				g2.setColor(new java.awt.Color(0, 0, 0, 90));
				g2.fill(new java.awt.geom.Ellipse2D.Float(cx - headR * 0.2f, headCy - headR * 0.5f,
					headR * 0.7f, headR * 0.7f));
				break;
			}
			case CROSS: // an × drawn as two strokes
				g2.setStroke(new BasicStroke(Math.max(1.4f, size * 0.16f),
					BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				float in = size * 0.2f;
				g2.draw(new java.awt.geom.Line2D.Float(in, in, size - in, size - in));
				g2.draw(new java.awt.geom.Line2D.Float(size - in, in, in, size - in));
				break;
			case HEART:
			case HEART_OUTLINE:
			{
				Path2D heart = heartPath(size);
				if (shape == Shape.HEART)
				{
					g2.fill(heart);
				}
				else
				{
					g2.setStroke(new BasicStroke(1.1f));
					g2.draw(heart);
				}
				break;
			}
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
			case CHEVRON_LEFT:
				g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.draw(chevron(size, 0.62f, 0.34f));
				break;
			case CHEVRON_RIGHT:
				g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.draw(chevron(size, 0.38f, 0.66f));
				break;
			case TRIANGLE_UP:
				g2.fill(triangle(size, 0.5f, 0.2f, 0.84f, 0.74f, 0.16f, 0.74f));
				break;
			case TRIANGLE_DOWN:
				g2.fill(triangle(size, 0.16f, 0.26f, 0.84f, 0.26f, 0.5f, 0.8f));
				break;
			case TRIANGLE_RIGHT:
				g2.fill(triangle(size, 0.3f, 0.16f, 0.3f, 0.84f, 0.82f, 0.5f));
				break;
			case DOTS_VERTICAL:
				float d = Math.max(1.6f, size * 0.16f);
				for (int i = 0; i < 3; i++)
				{
					float cy = size * (0.18f + 0.32f * i);
					g2.fill(new java.awt.geom.Ellipse2D.Float(size / 2f - d / 2, cy - d / 2, d, d));
				}
				break;
		}
		g2.dispose();
	}

	private static Path2D chevron(int size, float fromX, float tipX)
	{
		Path2D path = new Path2D.Float();
		path.moveTo(size * fromX, size * 0.18f);
		path.lineTo(size * tipX, size * 0.5f);
		path.lineTo(size * fromX, size * 0.82f);
		return path;
	}

	private static Path2D heartPath(int size)
	{
		float s = size;
		Path2D h = new Path2D.Float();
		h.moveTo(s * 0.5, s * 0.30);
		// left lobe
		h.curveTo(s * 0.40, s * 0.08, s * 0.02, s * 0.14, s * 0.14, s * 0.46);
		h.curveTo(s * 0.22, s * 0.66, s * 0.42, s * 0.80, s * 0.5, s * 0.92);
		// right lobe
		h.curveTo(s * 0.58, s * 0.80, s * 0.78, s * 0.66, s * 0.86, s * 0.46);
		h.curveTo(s * 0.98, s * 0.14, s * 0.60, s * 0.08, s * 0.5, s * 0.30);
		h.closePath();
		return h;
	}

	private static Path2D triangle(int size, float x1, float y1, float x2, float y2, float x3, float y3)
	{
		Path2D path = new Path2D.Float();
		path.moveTo(size * x1, size * y1);
		path.lineTo(size * x2, size * y2);
		path.lineTo(size * x3, size * y3);
		path.closePath();
		return path;
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
