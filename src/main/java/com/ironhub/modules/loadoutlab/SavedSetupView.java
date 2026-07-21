package com.ironhub.modules.loadoutlab;

import com.ironhub.state.PersistedState;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The remembered setup drawn the way the GAME draws it (Luke, 2026-07-17):
 * the Worn Equipment interface — 36px slot tiles at the game's own layout,
 * chain links between them, per-slot placeholder silhouettes on empty slots
 * — and the inventory as items sitting directly on the game's framed
 * side-panel backing.
 *
 * <p>Geometry and the link art are MEASURED, not eyeballed: slot positions
 * template-matched in the wiki's native-1x File:Worn_Equipment_tab.png, and
 * link_h/link_v cropped from the same image (resource packs do not redraw
 * the links, so both themes share them). Slot sprites come per theme via
 * OsrsIcons (vanilla = Luke's dropped official set at data/icons/osrs/
 * equipment/, Mystic = the pack's redraws). The inventory is the game's
 * bottom-line-mode side panel on BOTH themes (Luke's word): the background
 * texture tiled inside the bottom_line_mode_side_panel edge/corner frame,
 * each theme shipping its own redraws under dialog_inventory_sprites/.
 */
public class SavedSetupView
{
	private static final int SLOT = 36;
	/** Slot positions in the 204px-wide reference frame (measured). */
	private static final Map<EquipmentInventorySlot, Point> SLOTS = new LinkedHashMap<>();
	private static final Map<EquipmentInventorySlot, String> PLACEHOLDERS = new LinkedHashMap<>();

	static
	{
		SLOTS.put(EquipmentInventorySlot.HEAD, new Point(84, 11));
		SLOTS.put(EquipmentInventorySlot.CAPE, new Point(43, 50));
		SLOTS.put(EquipmentInventorySlot.AMULET, new Point(84, 50));
		SLOTS.put(EquipmentInventorySlot.AMMO, new Point(125, 50));
		SLOTS.put(EquipmentInventorySlot.WEAPON, new Point(28, 89));
		SLOTS.put(EquipmentInventorySlot.BODY, new Point(84, 89));
		SLOTS.put(EquipmentInventorySlot.SHIELD, new Point(140, 89));
		SLOTS.put(EquipmentInventorySlot.LEGS, new Point(84, 129));
		SLOTS.put(EquipmentInventorySlot.GLOVES, new Point(28, 169));
		SLOTS.put(EquipmentInventorySlot.BOOTS, new Point(84, 169));
		SLOTS.put(EquipmentInventorySlot.RING, new Point(140, 169));

		PLACEHOLDERS.put(EquipmentInventorySlot.HEAD, "slot_head");
		PLACEHOLDERS.put(EquipmentInventorySlot.CAPE, "slot_cape");
		PLACEHOLDERS.put(EquipmentInventorySlot.AMULET, "slot_neck");
		PLACEHOLDERS.put(EquipmentInventorySlot.AMMO, "slot_ammunition");
		PLACEHOLDERS.put(EquipmentInventorySlot.WEAPON, "slot_weapon");
		PLACEHOLDERS.put(EquipmentInventorySlot.BODY, "slot_torso");
		PLACEHOLDERS.put(EquipmentInventorySlot.SHIELD, "slot_shield");
		PLACEHOLDERS.put(EquipmentInventorySlot.LEGS, "slot_legs");
		PLACEHOLDERS.put(EquipmentInventorySlot.GLOVES, "slot_hands");
		PLACEHOLDERS.put(EquipmentInventorySlot.BOOTS, "slot_feet");
		PLACEHOLDERS.put(EquipmentInventorySlot.RING, "slot_ring");
	}

	/** Equipment canvas: bottom row ends at 169+36, plus the top margin. */
	private static final int EQUIP_WIDTH = 204;
	private static final int EQUIP_HEIGHT = 169 + SLOT + 11;
	/** Links sit centred on slot centres: centre − 3 (measured offset 15). */
	private static final int LINK_OFF = SLOT / 2 - 3;

	/** The game's side panel backing and its inventory grid (13,9 + 42x36). */
	private static final int INV_WIDTH = 190;
	private static final int INV_HEIGHT = 261;
	private static final int INV_X = 13;
	private static final int INV_Y = 9;
	private static final int INV_DX = 42;
	private static final int INV_DY = 36;

	private final OsrsTheme theme;
	private final ItemManager itemManager;   // null headless — sprites skipped
	private final Function<Integer, String> itemName;
	private final BufferedImage slotTile;
	private final BufferedImage linkH;
	private final BufferedImage linkV;

	public SavedSetupView(OsrsTheme theme, ItemManager itemManager,
		Function<Integer, String> itemName)
	{
		this.theme = theme;
		this.itemManager = itemManager;
		this.itemName = itemName;
		this.slotTile = OsrsIcons.image(theme, "equipment/slot_tile");
		this.linkH = OsrsIcons.image(theme, "equipment/link_h");
		this.linkV = OsrsIcons.image(theme, "equipment/link_v");
	}

	/**
	 * The worn-equipment interface with per-slot diff tint borders (slot
	 * name -> border colour, null = none) and an optional slot-press
	 * callback (fires for ANY slot, filled or empty).
	 */
	public JComponent equipment(PersistedState.SavedSetup setup,
		Map<String, java.awt.Color> tints,
		java.util.function.Consumer<EquipmentInventorySlot> onSlotPress)
	{
		return new EquipmentCanvas(setup, tints, onSlotPress);
	}

	/** The rune pouch as a short row of slot tiles. */
	public JComponent runePouch(PersistedState.SavedSetup setup)
	{
		return new PouchCanvas(setup);
	}

	/** The inventory with per-slot diff tint borders (28 entries or null). */
	public JComponent inventory(PersistedState.SavedSetup setup, java.awt.Color[] tints)
	{
		return new InventoryCanvas(setup, tints);
	}

	/** Diff tints paint 1px at ~2/3 alpha — visible, never shouty (Luke). */
	private static java.awt.Color subtle(java.awt.Color tint)
	{
		return new java.awt.Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 170);
	}

	private AsyncBufferedImage sprite(JComponent repaintTarget, int itemId, int quantity)
	{
		if (itemManager == null)
		{
			return null;
		}
		AsyncBufferedImage image = itemManager.getImage(itemId, Math.max(1, quantity), quantity > 1);
		image.onLoaded(repaintTarget::repaint);
		return image;
	}

	/** The game's stack-count yellow. */
	private static final java.awt.Color STACK_YELLOW = new java.awt.Color(0xFFFF00);

	/** Crop an edge sprite to its exact opaque strip band (null-safe): the
	 *  strips sit centred in 32px canvases, and their widths differ per
	 *  theme, so the band is measured rather than assumed. */
	private static BufferedImage strip(BufferedImage source, boolean horizontal)
	{
		if (source == null)
		{
			return null;
		}
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
		if (max < 0)
		{
			return null;
		}
		return horizontal
			? source.getSubimage(0, min, source.getWidth(), max - min + 1)
			: source.getSubimage(min, 0, max - min + 1, source.getHeight());
	}

	/**
	 * Draw an item sprite with its visible INK centred on a slot tile — the
	 * art floats off-centre inside the 36x32 sprite canvas, so a canvas-
	 * aligned draw reads visibly off (the skin's centre-measured-ink rule,
	 * applied to sprites). Falls back to canvas-aligned while still loading.
	 */
	private static void drawInkCentred(Graphics2D g, BufferedImage image, int tileX, int tileY)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < 0)
		{
			g.drawImage(image, tileX, tileY + 2, null); // not loaded yet
			return;
		}
		int w = maxX - minX + 1;
		int h = maxY - minY + 1;
		g.drawImage(image, tileX + (SLOT - w) / 2 - minX, tileY + (SLOT - h) / 2 - minY, null);
	}

	/** Tile a link strip along its span (stretching would smear the chain). */
	private static void tile(Graphics2D g, BufferedImage art, int x, int y, int w, int h)
	{
		if (art == null)
		{
			return;
		}
		java.awt.Shape clip = g.getClip();
		g.clipRect(x, y, w, h);
		for (int dy = 0; dy < h; dy += art.getHeight())
		{
			for (int dx = 0; dx < w; dx += art.getWidth())
			{
				g.drawImage(art, x + dx, y + dy, null);
			}
		}
		g.setClip(clip);
	}

	private class EquipmentCanvas extends JPanel
	{
		private final PersistedState.SavedSetup setup;
		private final Map<String, java.awt.Color> tints;
		private final Map<EquipmentInventorySlot, AsyncBufferedImage> items = new LinkedHashMap<>();

		EquipmentCanvas(PersistedState.SavedSetup setup, Map<String, java.awt.Color> tints,
			java.util.function.Consumer<EquipmentInventorySlot> onSlotPress)
		{
			this.setup = setup;
			this.tints = tints;
			setOpaque(false);
			setToolTipText(""); // enables per-point tooltips
			for (EquipmentInventorySlot slot : SLOTS.keySet())
			{
				Integer id = setup.equipment.get(slot.name());
				if (id != null && id > 0)
				{
					AsyncBufferedImage image = sprite(this, id, 0);
					if (image != null)
					{
						items.put(slot, image);
					}
				}
			}
			if (onSlotPress != null)
			{
				setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						EquipmentInventorySlot slot = slotAt(e.getX(), e.getY());
						if (slot != null)
						{
							onSlotPress.accept(slot);
						}
					}
				});
			}
		}

		private EquipmentInventorySlot slotAt(int x, int y)
		{
			for (Map.Entry<EquipmentInventorySlot, Point> entry : SLOTS.entrySet())
			{
				Point at = entry.getValue();
				if (x >= at.x && x < at.x + SLOT && y >= at.y && y < at.y + SLOT)
				{
					return entry.getKey();
				}
			}
			return null;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(EQUIP_WIDTH, EQUIP_HEIGHT);
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
			// the chain links first — the tiles cover their ends, exactly
			// like the game's own draw order
			int cx = 84 + LINK_OFF;
			tile(g2, linkV, cx, 11 + SLOT / 2, 6, 169 - 11);          // head..feet spine
			tile(g2, linkV, 28 + LINK_OFF, 89 + SLOT / 2, 6, 169 - 89); // weapon..hands
			tile(g2, linkV, 140 + LINK_OFF, 89 + SLOT / 2, 6, 169 - 89); // shield..ring
			tile(g2, linkH, 43 + SLOT / 2, 50 + LINK_OFF, 125 - 43, 6); // cape..ammo
			tile(g2, linkH, 28 + SLOT / 2, 89 + LINK_OFF, 140 - 28, 6); // weapon..shield

			for (Map.Entry<EquipmentInventorySlot, Point> entry : SLOTS.entrySet())
			{
				Point at = entry.getValue();
				if (slotTile != null)
				{
					g2.drawImage(slotTile, at.x, at.y, null);
				}
				AsyncBufferedImage item = items.get(entry.getKey());
				if (item != null)
				{
					// the game's 36x32 item sprite, centred on the tile
					g2.drawImage(item, at.x, at.y + 2, null);
				}
				else if (setup.equipment.get(entry.getKey().name()) == null)
				{
					BufferedImage ghost = OsrsIcons.image(theme,
						"equipment/" + PLACEHOLDERS.get(entry.getKey()));
					if (ghost != null)
					{
						g2.drawImage(ghost, at.x + 2, at.y + 2, null);
					}
				}
				java.awt.Color tint = tints != null ? tints.get(entry.getKey().name()) : null;
				if (tint != null)
				{
					// 1px + translucent: the loud double border overwhelmed
					// the slot art (Luke, 2026-07-21)
					OsrsSkin.outline(g2, subtle(tint), at.x, at.y, SLOT, SLOT);
				}
			}
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			for (Map.Entry<EquipmentInventorySlot, Point> entry : SLOTS.entrySet())
			{
				Point at = entry.getValue();
				if (e.getX() >= at.x && e.getX() < at.x + SLOT
					&& e.getY() >= at.y && e.getY() < at.y + SLOT)
				{
					Integer id = setup.equipment.get(entry.getKey().name());
					return id != null && id > 0 ? itemName.apply(id)
						: entry.getKey().name().toLowerCase(java.util.Locale.ROOT);
				}
			}
			return null;
		}
	}

	private class PouchCanvas extends JPanel
	{
		private static final int PITCH = 41;

		private final PersistedState.SavedSetup setup;
		private final AsyncBufferedImage[] runes;

		PouchCanvas(PersistedState.SavedSetup setup)
		{
			this.setup = setup;
			this.runes = new AsyncBufferedImage[setup.pouchRunes.length];
			setOpaque(false);
			setToolTipText("");
			for (int i = 0; i < setup.pouchRunes.length; i++)
			{
				if (setup.pouchRunes[i] > 0 && itemManager != null)
				{
					// stack-correct art WITHOUT the baked count: the number
					// is painted separately so the rune can be INK-centred
					// (rune art sits off-centre in its sprite canvas — the
					// canvas-aligned draw read visibly off, Luke)
					int amount = setup.pouchAmounts.length > i ? setup.pouchAmounts[i] : 1;
					runes[i] = itemManager.getImage(setup.pouchRunes[i],
						Math.max(1, amount), false);
					runes[i].onLoaded(this::repaint);
				}
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(PITCH * setup.pouchRunes.length - (PITCH - SLOT), SLOT);
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
			for (int i = 0; i < setup.pouchRunes.length; i++)
			{
				int x = i * PITCH;
				if (slotTile != null)
				{
					g2.drawImage(slotTile, x, 0, null);
				}
				if (runes[i] != null)
				{
					drawInkCentred(g2, runes[i], x, 0);
					int amount = setup.pouchAmounts.length > i ? setup.pouchAmounts[i] : 0;
					if (amount > 0)
					{
						// the game's stack number: yellow, small font, top-left
						g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
							java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
						g2.setFont(OsrsSkin.smallFont());
						String count = String.valueOf(amount);
						g2.setColor(java.awt.Color.BLACK);
						g2.drawString(count, x + 2, 11);
						g2.setColor(STACK_YELLOW);
						g2.drawString(count, x + 1, 10);
					}
				}
			}
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = e.getX() / PITCH;
			if (i >= 0 && i < setup.pouchRunes.length && e.getX() % PITCH < SLOT)
			{
				if (setup.pouchRunes[i] > 0)
				{
					int amount = setup.pouchAmounts.length > i ? setup.pouchAmounts[i] : 0;
					return itemName.apply(setup.pouchRunes[i])
						+ (amount > 1 ? " x" + amount : "");
				}
				return "empty";
			}
			return null;
		}
	}

	private class InventoryCanvas extends JPanel
	{
		private final PersistedState.SavedSetup setup;
		private final java.awt.Color[] tints;
		private final AsyncBufferedImage[] items = new AsyncBufferedImage[28];
		/** The game's bottom-line-mode side panel, per theme: background
		 *  texture tiled inside the edge/corner frame pieces (Mystic ships
		 *  its own redraws of both; the strips sit 7px centred in 32px
		 *  canvases, so the inside-visible parts are cropped). */
		private final BufferedImage texture;
		private final BufferedImage edgeTop;
		private final BufferedImage edgeBottom;
		private final BufferedImage edgeLeft;
		private final BufferedImage edgeRight;
		private final BufferedImage cornerTl;
		private final BufferedImage cornerTr;
		private final BufferedImage cornerBl;
		private final BufferedImage cornerBr;

		InventoryCanvas(PersistedState.SavedSetup setup, java.awt.Color[] tints)
		{
			this.setup = setup;
			this.tints = tints;
			setOpaque(false);
			setToolTipText("");
			texture = OsrsIcons.image(theme, "dialog_inventory_sprites/background");
			// edges carry a strip centred in a 32px canvas; each is cropped to
			// its exact opaque band so top/left anchor at 0 and bottom/right
			// anchor flush (the band widths differ per theme: 6px vs 7px)
			edgeTop = strip(OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_edge_top"), true);
			edgeBottom = strip(OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_edge_bottom"), true);
			edgeLeft = strip(OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_edge_left"), false);
			edgeRight = strip(OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_edge_right"), false);
			// corners are corner-anchored L-pieces with TRANSPARENT interiors
			// (Luke: the quadrant crops sliced the ornament) — drawn whole,
			// over the edge strips, at the four canvas corners
			cornerTl = OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_corner_top_left");
			cornerTr = OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_corner_top_right");
			cornerBl = OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_corner_bottom_left");
			cornerBr = OsrsIcons.image(theme,
				"dialog_inventory_sprites/bottom_line_mode_side_panel_corner_bottom_right");
			for (int i = 0; i < 28 && i < setup.inventory.length; i++)
			{
				if (setup.inventory[i] > 0)
				{
					items[i] = sprite(this, setup.inventory[i],
						setup.inventoryQty.length > i ? setup.inventoryQty[i] : 1);
				}
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(INV_WIDTH, INV_HEIGHT);
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
			if (texture != null)
			{
				tile(g2, texture, 0, 0, INV_WIDTH, INV_HEIGHT);
				if (edgeTop != null)
				{
					tile(g2, edgeTop, 0, 0, INV_WIDTH, edgeTop.getHeight());
					tile(g2, edgeBottom, 0, INV_HEIGHT - edgeBottom.getHeight(),
						INV_WIDTH, edgeBottom.getHeight());
					tile(g2, edgeLeft, 0, 0, edgeLeft.getWidth(), INV_HEIGHT);
					tile(g2, edgeRight, INV_WIDTH - edgeRight.getWidth(), 0,
						edgeRight.getWidth(), INV_HEIGHT);
				}
				if (cornerTl != null)
				{
					g2.drawImage(cornerTl, 0, 0, null);
					g2.drawImage(cornerTr, INV_WIDTH - cornerTr.getWidth(), 0, null);
					g2.drawImage(cornerBl, 0, INV_HEIGHT - cornerBl.getHeight(), null);
					g2.drawImage(cornerBr, INV_WIDTH - cornerBr.getWidth(),
						INV_HEIGHT - cornerBr.getHeight(), null);
				}
			}
			else
			{
				// honest fallback: a plain recess, never invented texture
				g2.setColor(theme.recess);
				g2.fillRect(0, 0, INV_WIDTH, INV_HEIGHT);
				OsrsSkin.outline(g2, theme.edgeDark, 0, 0, INV_WIDTH, INV_HEIGHT);
				OsrsSkin.outline(g2, theme.edgeLight, 1, 1, INV_WIDTH - 2, INV_HEIGHT - 2);
			}
			for (int i = 0; i < 28; i++)
			{
				int x = INV_X + (i % 4) * INV_DX;
				int y = INV_Y + (i / 4) * INV_DY;
				if (items[i] != null)
				{
					g2.drawImage(items[i], x, y, null);
				}
				java.awt.Color tint = tints != null && i < tints.length ? tints[i] : null;
				if (tint != null)
				{
					// the game's 36x32 item cell, 1px subtle diff border (Luke)
					OsrsSkin.outline(g2, subtle(tint), x, y, 36, 32);
				}
			}
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int col = (e.getX() - INV_X) / INV_DX;
			int row = (e.getY() - INV_Y) / INV_DY;
			if (col >= 0 && col < 4 && row >= 0 && row < 7)
			{
				int i = row * 4 + col;
				if (i < setup.inventory.length && setup.inventory[i] > 0)
				{
					int quantity = setup.inventoryQty.length > i ? setup.inventoryQty[i] : 1;
					return itemName.apply(setup.inventory[i])
						+ (quantity > 1 ? " x" + quantity : "");
				}
			}
			return null;
		}
	}
}
