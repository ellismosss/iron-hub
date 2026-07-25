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
	/**
	 * The 4x7 grid's pitch — the game's own, and the one fixed thing here.
	 * Everything else is DERIVED from it, because the frame has to fit the
	 * grid rather than the grid having to fit a hardcoded frame.
	 *
	 * <p>It was the other way round until 2026-07-25, at a measured 190x261
	 * with the first slot at 13,9. That put the bottom row's last pixel at 257
	 * against a frame whose inner edge is 254, so the bottom row hung over the
	 * frame's own band — Luke's "the items are falling off the low-edge". The
	 * same fixed numbers could not be right for all three themes anyway: the
	 * edge bands are 6px in one pack and 7px in another, so a constant WIDTH
	 * silently changed the margins with the theme, which is the second half of
	 * the report ("they don't look evenly spaced").
	 */
	private static final int SLOT_DX = 42;
	private static final int SLOT_DY = 36;

	/**
	 * The game's own panel size, and it is FIXED — Luke compared the two side
	 * by side and DLV2's was visibly smaller (2026-07-25). Deriving the frame
	 * from the grid, which I tried first, made it 180x266: narrower and taller
	 * than the game's.
	 *
	 * <p>190 is corroborated rather than assumed: the stone bands measure 7px,
	 * and 7 + 7 + (3 x 42 + 36) + 7 + 7 comes to exactly 190. The game's margin
	 * is one band's width.
	 */
	public static final int WIDTH = 190;
	/**
	 * 276, not the 261 the width's source implied. At 261 the seven rows need
	 * 248px against 247 available, so the grid sat flush against both bands
	 * with no air at all (Luke, 2026-07-25: "it needs to be a little taller, to
	 * leave a small padding between the icons and the upper and lower edges").
	 *
	 * <p>276 is the width's own rule applied downward — margin equals one
	 * band's width — so the padding above and below the grid is the same 7px as
	 * the padding left and right: {@code 7 + 7 + (6 x 36 + 32) + 7 + 7}.
	 */
	public static final int HEIGHT = 276;
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
	/** {@code Image}, not BufferedImage: at runtime these are ItemManager's
	 *  AsyncBufferedImages arriving through SpriteCache, already scaled. */
	private final java.awt.Image[] items = new java.awt.Image[SLOTS];

	public V2Inventory(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Put an item sprite in a slot (0..27); null clears it. */
	public V2Inventory item(int slot, java.awt.Image sprite)
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

	/** The game's item cell — the size {@code ItemManager} hands back. */
	public static final int CELL_W = 36;
	public static final int CELL_H = 32;

	/**
	 * The grid is CENTRED inside whatever the theme's bands leave, rather than
	 * pinned at a hardcoded 13,9. That origin gave a 2px top margin and a 3px
	 * bottom overhang, which is the lopsided look Luke reported; and it could
	 * not be right for every pack anyway, since Mystic's bands are 6px where
	 * stone's and dark's are 7.
	 */
	public int slotX(int slot)
	{
		int left = band(theme, "left", false).getWidth();
		int slack = WIDTH - left - band(theme, "right", false).getWidth()
			- ((COLUMNS - 1) * SLOT_DX + CELL_W);
		return left + Math.floorDiv(slack, 2) + (slot % COLUMNS) * SLOT_DX;
	}

	public int slotY(int slot)
	{
		int top = band(theme, "top", true).getHeight();
		int slack = HEIGHT - top - band(theme, "bottom", true).getHeight()
			- ((ROWS - 1) * SLOT_DY + CELL_H);
		return top + Math.floorDiv(slack, 2) + (slot / COLUMNS) * SLOT_DY;
	}

	/** The first pixel row the frame's bottom band occupies — content above. */
	public int innerBottom()
	{
		return HEIGHT - band(theme, "bottom", true).getHeight();
	}

	/** The first pixel column the frame's right band occupies. */
	public int innerRight()
	{
		return WIDTH - band(theme, "right", false).getWidth();
	}

	/** What is in a slot, or null. Test seam: an empty inventory and a
	 *  correctly filled one look identical in a headless render. */
	public java.awt.Image item(int slot)
	{
		return items[slot];
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
		int w = WIDTH, h = HEIGHT;
		tile(g2, V2Sprites.get(theme, TEXTURE), 0, 0, w, h);

		BufferedImage top = band(theme, "top", true);
		BufferedImage bottom = band(theme, "bottom", true);
		BufferedImage left = band(theme, "left", false);
		BufferedImage right = band(theme, "right", false);
		tile(g2, top, 0, 0, w, top.getHeight());
		tile(g2, bottom, 0, h - bottom.getHeight(), w, bottom.getHeight());
		tile(g2, left, 0, 0, left.getWidth(), h);
		tile(g2, right, w - right.getWidth(), 0, right.getWidth(), h);

		// corner L-pieces last, over the strips
		BufferedImage tl = V2Sprites.get(theme, String.format(CORNER, "top_left"));
		BufferedImage tr = V2Sprites.get(theme, String.format(CORNER, "top_right"));
		BufferedImage bl = V2Sprites.get(theme, String.format(CORNER, "bottom_left"));
		BufferedImage br = V2Sprites.get(theme, String.format(CORNER, "bottom_right"));
		g2.drawImage(tl, 0, 0, null);
		g2.drawImage(tr, w - tr.getWidth(), 0, null);
		g2.drawImage(bl, 0, h - bl.getHeight(), null);
		g2.drawImage(br, w - br.getWidth(), h - br.getHeight(), null);

		for (int i = 0; i < SLOTS; i++)
		{
			if (items[i] != null)
			{
				g2.drawImage(items[i], slotX(i), slotY(i), null);
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
