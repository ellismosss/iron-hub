package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JComponent;

/**
 * The game's inventory: 4 x 7 = 28 slots on its own framed backing.
 *
 * <p><b>A port of {@code SavedSetupView.InventoryView}</b>, which already
 * renders this correctly in Gear &amp; Combat (Luke, 2026-07-25: "the correct
 * inventory LITERALLY EXISTS in the Gear &amp; Combat module, so why not just
 * import those well-aligned sprites"). Every number here is measured from the
 * game's own interface, and this class exists so V2 shares that measurement
 * rather than re-deriving it — which is exactly what went wrong: I composed
 * the frame from a generic nine-slice and drew each edge's FULL 32px canvas,
 * so the bar floated 13px inside the box instead of sitting on its edge.
 *
 * <p>The step that matters: each edge sprite is a strip centred in a 32px
 * canvas, and its band width DIFFERS PER THEME (6px vs 7px). So every edge is
 * cropped to its own measured opaque band, top and left anchored at 0 and
 * bottom and right anchored flush. The corners are corner-anchored L-pieces
 * with transparent interiors and go on last, over the strips.
 */
public class V2Inventory extends JComponent
{
	/** The game's own inventory panel, measured. */
	public static final int WIDTH = 190;
	public static final int HEIGHT = 261;
	/** First slot's origin and the 4x7 grid's pitch. */
	private static final int SLOT_X = 13;
	private static final int SLOT_Y = 9;
	private static final int SLOT_DX = 42;
	private static final int SLOT_DY = 36;
	/** 4 across, 7 down. The OSRS inventory has always been 28 slots. */
	public static final int COLUMNS = 4;
	public static final int ROWS = 7;
	public static final int SLOTS = COLUMNS * ROWS;

	private static final String TEXTURE = "ui/borders/inventory_background";
	private static final String EDGE = "ui/borders/bottom_line_mode_side_panel_edge_%s";
	private static final String CORNER = "ui/borders/bottom_line_mode_side_panel_corner_%s";

	/** Cropped strips, per theme and side — the crop scans pixels, so it is
	 *  done once rather than per paint. */
	private static final ConcurrentHashMap<String, BufferedImage> BANDS = new ConcurrentHashMap<>();

	private final OsrsTheme theme;
	private final BufferedImage[] items = new BufferedImage[SLOTS];

	public V2Inventory(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Put an item sprite in a slot (0..27); null clears it. */
	public V2Inventory item(int slot, BufferedImage sprite)
	{
		if (slot < 0 || slot >= SLOTS)
		{
			throw new IllegalArgumentException("the inventory has " + SLOTS
				+ " slots (4 x 7), not a slot " + slot);
		}
		items[slot] = sprite;
		repaint();
		return this;
	}

	/**
	 * An edge sprite cropped to its exact opaque band. The strips sit centred
	 * in a 32px canvas and their widths differ per theme, so the band is
	 * MEASURED rather than assumed — drawing the full canvas is what put the
	 * bar 13px inside the frame.
	 */
	private static BufferedImage band(OsrsTheme theme, String side, boolean horizontal)
	{
		return BANDS.computeIfAbsent(theme.spriteVariant() + "/" + side, key ->
		{
			BufferedImage source = V2Sprites.get(theme, String.format(EDGE, side));
			int min = Integer.MAX_VALUE;
			int max = -1;
			for (int y = 0; y < source.getHeight(); y++)
			{
				for (int x = 0; x < source.getWidth(); x++)
				{
					if ((source.getRGB(x, y) >>> 24) != 0)
					{
						int v = horizontal ? y : x;
						min = Math.min(min, v);
						max = Math.max(max, v);
					}
				}
			}
			return horizontal
				? source.getSubimage(0, min, source.getWidth(), max - min + 1)
				: source.getSubimage(min, 0, max - min + 1, source.getHeight());
		});
	}

	private static void tile(Graphics2D g, BufferedImage src, int x, int y, int w, int h)
	{
		for (int oy = 0; oy < h; oy += src.getHeight())
		{
			int ph = Math.min(src.getHeight(), h - oy);
			for (int ox = 0; ox < w; ox += src.getWidth())
			{
				int pw = Math.min(src.getWidth(), w - ox);
				g.drawImage(src, x + ox, y + oy, x + ox + pw, y + oy + ph,
					0, 0, pw, ph, null);
			}
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		tile(g2, V2Sprites.get(theme, TEXTURE), 0, 0, WIDTH, HEIGHT);

		BufferedImage top = band(theme, "top", true);
		BufferedImage bottom = band(theme, "bottom", true);
		BufferedImage left = band(theme, "left", false);
		BufferedImage right = band(theme, "right", false);
		tile(g2, top, 0, 0, WIDTH, top.getHeight());
		tile(g2, bottom, 0, HEIGHT - bottom.getHeight(), WIDTH, bottom.getHeight());
		tile(g2, left, 0, 0, left.getWidth(), HEIGHT);
		tile(g2, right, WIDTH - right.getWidth(), 0, right.getWidth(), HEIGHT);

		// corner L-pieces last, over the strips
		BufferedImage tl = V2Sprites.get(theme, String.format(CORNER, "top_left"));
		BufferedImage tr = V2Sprites.get(theme, String.format(CORNER, "top_right"));
		BufferedImage bl = V2Sprites.get(theme, String.format(CORNER, "bottom_left"));
		BufferedImage br = V2Sprites.get(theme, String.format(CORNER, "bottom_right"));
		g2.drawImage(tl, 0, 0, null);
		g2.drawImage(tr, WIDTH - tr.getWidth(), 0, null);
		g2.drawImage(bl, 0, HEIGHT - bl.getHeight(), null);
		g2.drawImage(br, WIDTH - br.getWidth(), HEIGHT - br.getHeight(), null);

		for (int i = 0; i < SLOTS; i++)
		{
			if (items[i] != null)
			{
				g2.drawImage(items[i], SLOT_X + (i % COLUMNS) * SLOT_DX,
					SLOT_Y + (i / COLUMNS) * SLOT_DY, null);
			}
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(WIDTH, HEIGHT);
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
}
