package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Painted 14 px status glyph (✓ ● ○ !) — painted, not font emoji, per the
 * design handoff assets note.
 */
public class StatusGlyph implements Icon
{
	private final Status status;

	public StatusGlyph(Status status)
	{
		this.status = status;
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g2.translate(x, y);
		g2.setColor(status.color());

		switch (status)
		{
			case OWNED: // check mark
				g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				Path2D check = new Path2D.Float();
				check.moveTo(3, 7.5);
				check.lineTo(6, 10.5);
				check.lineTo(11, 4);
				g2.draw(check);
				break;
			case AVAILABLE: // filled dot
				g2.fill(new Ellipse2D.Float(3.5f, 3.5f, 7, 7));
				break;
			case LOCKED: // hollow dot
				g2.setStroke(new BasicStroke(1.3f));
				g2.draw(new Ellipse2D.Float(4, 4, 6, 6));
				break;
			case WARNING: // exclamation
				g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.drawLine(7, 3, 7, 8);
				g2.fill(new Ellipse2D.Float(6.1f, 10.2f, 1.8f, 1.8f));
				break;
		}
		g2.dispose();
	}

	@Override
	public int getIconWidth()
	{
		return UiTokens.STATUS_GLYPH_SIZE;
	}

	@Override
	public int getIconHeight()
	{
		return UiTokens.STATUS_GLYPH_SIZE;
	}
}
