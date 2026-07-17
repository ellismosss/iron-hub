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
import javax.swing.SwingUtilities;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
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
 * equipment/, Mystic = the pack's redraws); the inventory backing is the
 * Mystic pack's fixed_mode/side_panel_background for MYSTIC and the game's
 * own SpriteID.RS2_SIDE_PANEL_BACKGROUND fetched from the sprite cache at
 * runtime for STONE (headless renders paint an honest plain fallback).
 */
class SavedSetupView
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
	private final SpriteManager spriteManager; // null headless — backing falls back
	private final Function<Integer, String> itemName;
	private final BufferedImage slotTile;
	private final BufferedImage linkH;
	private final BufferedImage linkV;

	SavedSetupView(OsrsTheme theme, ItemManager itemManager, SpriteManager spriteManager,
		Function<Integer, String> itemName)
	{
		this.theme = theme;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.itemName = itemName;
		this.slotTile = OsrsIcons.image(theme, "equipment/slot_tile");
		this.linkH = OsrsIcons.image(theme, "equipment/link_h");
		this.linkV = OsrsIcons.image(theme, "equipment/link_v");
	}

	/** The worn-equipment interface for a saved setup. */
	JComponent equipment(PersistedState.SavedSetup setup)
	{
		return new EquipmentCanvas(setup);
	}

	/** The rune pouch as a short row of slot tiles. */
	JComponent runePouch(PersistedState.SavedSetup setup)
	{
		return new PouchCanvas(setup);
	}

	/** The inventory: items directly on the game's framed side panel. */
	JComponent inventory(PersistedState.SavedSetup setup)
	{
		return new InventoryCanvas(setup);
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
		private final Map<EquipmentInventorySlot, AsyncBufferedImage> items = new LinkedHashMap<>();

		EquipmentCanvas(PersistedState.SavedSetup setup)
		{
			this.setup = setup;
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
				if (setup.pouchRunes[i] > 0)
				{
					runes[i] = sprite(this, setup.pouchRunes[i],
						setup.pouchAmounts.length > i ? setup.pouchAmounts[i] : 0);
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
			for (int i = 0; i < setup.pouchRunes.length; i++)
			{
				int x = i * PITCH;
				if (slotTile != null)
				{
					g.drawImage(slotTile, x, 0, null);
				}
				if (runes[i] != null)
				{
					g.drawImage(runes[i], x, 2, null);
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
		private final AsyncBufferedImage[] items = new AsyncBufferedImage[28];
		private BufferedImage backing;

		InventoryCanvas(PersistedState.SavedSetup setup)
		{
			this.setup = setup;
			setOpaque(false);
			setToolTipText("");
			backing = OsrsIcons.image(theme, "fixed_mode/side_panel_background");
			if (backing == null && spriteManager != null)
			{
				// vanilla: the game's own framed backing from the sprite cache
				spriteManager.getSpriteAsync(SpriteID.RS2_SIDE_PANEL_BACKGROUND, 0,
					image -> SwingUtilities.invokeLater(() ->
					{
						backing = image;
						repaint();
					}));
			}
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
			if (backing != null)
			{
				g2.drawImage(backing, 0, 0, null);
			}
			else
			{
				// honest fallback (headless, or the cache not loaded yet):
				// a plain recess in the frame grammar, never invented texture
				g2.setColor(theme.recess);
				g2.fillRect(0, 0, INV_WIDTH, INV_HEIGHT);
				OsrsSkin.outline(g2, theme.edgeDark, 0, 0, INV_WIDTH, INV_HEIGHT);
				OsrsSkin.outline(g2, theme.edgeLight, 1, 1, INV_WIDTH - 2, INV_HEIGHT - 2);
			}
			for (int i = 0; i < 28; i++)
			{
				if (items[i] != null)
				{
					int x = INV_X + (i % 4) * INV_DX;
					int y = INV_Y + (i / 4) * INV_DY;
					g2.drawImage(items[i], x, y, null);
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
