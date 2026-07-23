package com.ironhub.modules.collectionlog;

import com.ironhub.ui.components.SpriteCache;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * The log's own item grid: sprites laid out five to a row, the ones you own
 * drawn solid with their count in the game's yellow, the ones you don't
 * ghosted back — the same reading as the interface, which dims an unobtained
 * slot to 175 alpha rather than hiding it.
 *
 * <p>One component paints the whole page: a Barrows or clue page runs to
 * hundreds of slots, and that many child labels per rebuild is the row-storm
 * the panel's caps exist to prevent.
 */
class ClogItemGrid extends JComponent
{
	static final int COLUMNS = 5;
	/**
	 * The interface's own pitch is a 36x32 sprite in a 42x36 cell; five of
	 * those want 210px and the panel has 205 once the row's gaps are taken,
	 * so the cell narrows by one pixel a side. The sprite is untouched.
	 */
	static final int CELL_WIDTH = 41;
	static final int CELL_HEIGHT = 36;
	private static final int SPRITE_HEIGHT = 32;
	/** The game's stack-count scale: yellow under 100K, white to 10M, green
	 *  beyond — the same three colours the inventory paints. */
	private static final Color COUNT = new Color(0xFFFF00);
	private static final Color COUNT_100K = Color.WHITE;
	private static final Color COUNT_10M = new Color(0x00FF80);
	private static final float GHOST = 0.35f;

	static class Cell
	{
		final int itemId;
		final String name;
		final boolean obtained;
		final int count;

		Cell(int itemId, String name, boolean obtained, int count)
		{
			this.itemId = itemId;
			this.name = name;
			this.obtained = obtained;
			this.count = count;
		}
	}

	private final List<Cell> cells;
	private final SpriteCache sprites;

	ClogItemGrid(List<Cell> cells, SpriteCache sprites)
	{
		this.cells = cells;
		this.sprites = sprites;
		setAlignmentX(LEFT_ALIGNMENT);
		setToolTipText(""); // opt in to tooltips; the text is per cell
	}

	/** The cell under a point, or null between them. */
	Cell cellAt(java.awt.Point point)
	{
		int column = point.x / CELL_WIDTH;
		int row = point.y / CELL_HEIGHT;
		if (column < 0 || column >= COLUMNS || row < 0)
		{
			return null;
		}
		int index = row * COLUMNS + column;
		return index >= 0 && index < cells.size() ? cells.get(index) : null;
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		Cell cell = cellAt(event.getPoint());
		if (cell == null)
		{
			return null;
		}
		if (!cell.obtained)
		{
			return cell.name + " — not obtained";
		}
		return cell.name + (cell.count > 1 ? " — " + String.format(Locale.ROOT, "%,d", cell.count)
			+ " collected" : " — obtained");
	}

	@Override
	public Dimension getPreferredSize()
	{
		int rows = (cells.size() + COLUMNS - 1) / COLUMNS;
		return new Dimension(COLUMNS * CELL_WIDTH, Math.max(1, rows) * CELL_HEIGHT);
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
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		Font font = com.ironhub.ui.osrs.OsrsSkin.smallFont();
		for (int i = 0; i < cells.size(); i++)
		{
			Cell cell = cells.get(i);
			int x = (i % COLUMNS) * CELL_WIDTH;
			int y = (i / COLUMNS) * CELL_HEIGHT;
			Image sprite = sprites.get(cell.itemId, -1, SPRITE_HEIGHT);
			if (sprite != null)
			{
				int width = sprite.getWidth(null);
				if (!cell.obtained)
				{
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, GHOST));
				}
				g2.drawImage(sprite, x + (CELL_WIDTH - width) / 2,
					y + (CELL_HEIGHT - SPRITE_HEIGHT) / 2, null);
				g2.setComposite(AlphaComposite.SrcOver);
			}
			if (cell.obtained && cell.count > 1)
			{
				String text = count(cell.count);
				g2.setFont(font);
				g2.setColor(com.ironhub.ui.osrs.OsrsSkin.TEXT_SHADOW);
				g2.drawString(text, x + 2, y + 11);
				g2.setColor(cell.count >= 10_000_000 ? COUNT_10M
					: cell.count >= 100_000 ? COUNT_100K : COUNT);
				g2.drawString(text, x + 1, y + 10);
			}
		}
	}

	/** The game's own abbreviation: 15,000 reads "15K", millions "1M". */
	static String count(int value)
	{
		if (value >= 10_000_000)
		{
			return (value / 1_000_000) + "M";
		}
		if (value >= 100_000)
		{
			return (value / 1_000) + "K";
		}
		return String.valueOf(value);
	}
}
