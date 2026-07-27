package com.ironhub.modules.ca;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * A tile of the Combat Achievements interface's two grids — a tier in the
 * Difficulty view, a boss in the Bosses view — in the shape the game draws:
 * a name that turns green once everything under it is done, a smaller line
 * beneath it, and a fill bar across the foot.
 *
 * <p>Sized by the caller: two across the panel for tiers (which also carry
 * the tier's wiki icon), three across for bosses.
 *
 * <p>A COMPOSITE, not a hand-rolled tile: the stone under it is the Tile
 * surface itself ({@code V2Surface.paintTile}) and the foot bar is the METER
 * atom, painted in place. Only the arrangement — icon left, title over a sub
 * line, bar across the foot — is this class's own, because that arrangement is
 * the game's Combat Achievements interface and no atom covers it.
 */
class CaProgressTile extends JComponent
{
	private static final int METER = 3;
	/** The pixel font's line pitch, and the most lines a title may wrap to. */
	private static final int LINE = 11;
	private static final int MAX_LINES = 2;

	private final OsrsTheme theme;
	private final Image icon; // null for boss tiles
	private final String title;
	private final String sub;
	private final int done;
	private final int total;
	private final int width;
	private final int height;
	private boolean hover;
	private final V2ProgressBar bar;

	CaProgressTile(OsrsTheme theme, Image icon, String title, String sub, int done, int total,
		int width, int height, String tooltip, Runnable onClick)
	{
		this.theme = theme;
		this.icon = icon;
		this.title = title;
		this.sub = sub;
		this.done = done;
		this.total = total;
		this.width = width;
		this.height = height;
		this.bar = new V2ProgressBar(theme, V2ProgressBar.Size.METER);
		setToolTipText(tooltip);
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

	private boolean complete()
	{
		return total > 0 && done >= total;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(width, height);
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
		int w = getWidth();
		int h = getHeight();
		V2Surface.paintTile(g2, theme, w, h, false);
		if (hover)
		{
			V2Surface.washTile(g2, w, h);
		}
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		int textLeft = 4;
		if (icon != null)
		{
			g2.drawImage(icon, 4, (h - METER - 4 - icon.getHeight(null)) / 2, null);
			textLeft = 6 + icon.getWidth(null);
		}

		// the interface's own colouring: green once the tile is finished,
		// orange while it is not
		Color titleColour = complete() ? OsrsSkin.VALUE : OsrsSkin.TITLE;
		int available = w - textLeft - 4;
		List<String> lines = titleLines(g2, available);
		boolean hasSub = sub != null && !sub.isEmpty();
		int block = lines.size() * LINE + (hasSub ? LINE : 0);
		int top = (h - METER - 4 - block) / 2 + LINE - 2;
		for (String line : lines)
		{
			draw(g2, line, titleColour, textLeft, top, available);
			top += LINE;
		}
		if (hasSub)
		{
			g2.setFont(OsrsSkin.smallFont());
			draw(g2, sub, OsrsSkin.MUTED, textLeft, top, available);
		}

		// the METER atom across the foot, painted in place — this used to be a
		// hand-filled trough and bar (Luke's Progression pass, 2026-07-26)
		bar.fill(complete() ? V2Tokens.BAR_FILL : V2Tokens.BAR_BLUE)
			.fraction(total == 0 ? 0 : done / (double) total);
		int barY = h - bar.getPreferredSize().height - V2Tokens.ROW;
		bar.setBounds(V2Tokens.ROW, barY, w - 2 * V2Tokens.ROW,
			bar.getPreferredSize().height);
		g2.translate(V2Tokens.ROW, barY);
		bar.paint(g2);
		g2.translate(-V2Tokens.ROW, -barY);
	}

	/**
	 * The title at the largest weight that fits, wrapped onto a second line
	 * before it will ellipsize: a three-across boss grid holds names like
	 * "Chambers of Xeric", and "Chambe…" is not a name (the render caught
	 * every one of them truncated). Leaves the chosen font set on g2.
	 */
	private List<String> titleLines(Graphics2D g2, int available)
	{
		for (Font font : new Font[]{OsrsSkin.boldFont(), OsrsSkin.font(), OsrsSkin.smallFont()})
		{
			g2.setFont(font);
			if (g2.getFontMetrics().stringWidth(title) <= available)
			{
				return List.of(title);
			}
		}
		g2.setFont(OsrsSkin.smallFont());
		FontMetrics fm = g2.getFontMetrics();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : title.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) <= available || line.length() == 0)
			{
				line.setLength(0);
				line.append(candidate);
			}
			else
			{
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
			if (lines.size() == MAX_LINES - 1 && fm.stringWidth(line.toString()) > available)
			{
				break;
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		while (lines.size() > MAX_LINES)
		{
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	/** Centred in the space to the right of any icon, ellipsized to fit,
	 *  in whatever font g2 already carries. */
	private void draw(Graphics2D g2, String text, Color colour, int left, int baseline,
		int available)
	{
		FontMetrics fm = g2.getFontMetrics();
		String line = text;
		if (fm.stringWidth(line) > available)
		{
			while (line.length() > 1 && fm.stringWidth(line + "…") > available)
			{
				line = line.substring(0, line.length() - 1);
			}
			line = line + "…";
		}
		int x = left + Math.max(0, (available - fm.stringWidth(line)) / 2);
		g2.setColor(OsrsSkin.TEXT_SHADOW);
		g2.drawString(line, x + 1, baseline + 1);
		g2.setColor(colour);
		g2.drawString(line, x, baseline);
	}
}
