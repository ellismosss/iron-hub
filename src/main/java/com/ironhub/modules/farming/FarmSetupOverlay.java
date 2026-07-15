package com.ironhub.modules.farming;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Farm-run bank companion (ported behaviour from the Inventory Setups
 * plugin — github.com/dillydill123/inventory-setups, BSD-2): while a run
 * with a saved gear + inventory setup is active and the bank is open, this
 * draws that setup as an OSRS-style equipment grid + 4x7 inventory grid on
 * the canvas, with each slot you are NOT yet carrying tinted red — a live
 * shopping list to re-stock the run without leaving the game. Display-only;
 * drag it beside the bank (RuneLite persists the position).
 */
class FarmSetupOverlay extends Overlay
{
	private static final int CELL = 24;
	private static final int GAP = 1;
	private static final int PAD = 4;
	private static final int TITLE_H = 14;
	private static final int COL = CELL + GAP;

	// The OSRS equipment interface layout (Inventory Setups' arrangement):
	// 3 columns, 5 rows; null = a structural gap.
	private static final EquipmentInventorySlot[][] EQUIPMENT_LAYOUT = {
		{null, EquipmentInventorySlot.HEAD, null},
		{EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET, EquipmentInventorySlot.AMMO},
		{EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD},
		{null, EquipmentInventorySlot.LEGS, null},
		{EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING},
	};

	private static final Color MISSING_TINT = new Color(0xD9, 0x57, 0x57, 90);
	private static final Color SLOT_BG = new Color(0x1F, 0x1F, 0x1F, 210);

	private final FarmingRunModule module;
	private final AccountState state;
	private final IronHubConfig config;
	private final ItemManager itemManager; // null in headless tests
	private final Client client;           // null in headless tests

	FarmSetupOverlay(FarmingRunModule module, AccountState state, IronHubConfig config,
		ItemManager itemManager, Client client)
	{
		this.module = module;
		this.state = state;
		this.config = config;
		this.itemManager = itemManager;
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.farmBankSetup() || itemManager == null || !module.running() || !bankOpen())
		{
			return null;
		}
		PersistedState.SavedSetup setup = state.getFarmRunSetup(module.runName());
		if (setup == null)
		{
			return null;
		}

		final int equipWidth = 3 * COL - GAP;
		final int equipHeight = EQUIPMENT_LAYOUT.length * COL - GAP;
		final int invWidth = 4 * COL - GAP;
		final int invHeight = 7 * COL - GAP;
		final int equipX = PAD;
		final int invX = equipX + equipWidth + PAD + 2;
		final int gridY = PAD + TITLE_H;
		final int width = invX + invWidth + PAD;
		final int height = gridY + Math.max(equipHeight, invHeight) + PAD;

		graphics.setColor(UiTokens.OVERLAY_BG);
		graphics.fillRect(0, 0, width, height);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(Color.WHITE);
		graphics.drawString(module.runName() + " setup", PAD, PAD + 10);

		drawEquipment(graphics, setup, equipX, gridY);
		drawInventory(graphics, setup, invX, gridY);

		return new Dimension(width, height);
	}

	private void drawEquipment(Graphics2D g, PersistedState.SavedSetup setup, int originX, int originY)
	{
		for (int row = 0; row < EQUIPMENT_LAYOUT.length; row++)
		{
			for (int col = 0; col < 3; col++)
			{
				EquipmentInventorySlot slot = EQUIPMENT_LAYOUT[row][col];
				if (slot == null)
				{
					continue;
				}
				Integer itemId = setup.equipment.get(slot.name());
				drawSlot(g, originX + col * COL, originY + row * COL,
					itemId == null ? -1 : itemId, 1);
			}
		}
	}

	private void drawInventory(Graphics2D g, PersistedState.SavedSetup setup, int originX, int originY)
	{
		for (int i = 0; i < 28; i++)
		{
			int itemId = i < setup.inventory.length ? setup.inventory[i] : -1;
			int qty = i < setup.inventoryQty.length ? setup.inventoryQty[i] : 1;
			drawSlot(g, originX + (i % 4) * COL, originY + (i / 4) * COL, itemId, qty);
		}
	}

	/** One grid cell: dark backing, red tint when the item isn't carried
	 *  yet (still to withdraw), then the item sprite. Empty slots are just
	 *  the backing, so the grid keeps its inventory/equipment shape. */
	private void drawSlot(Graphics2D g, int x, int y, int itemId, int qty)
	{
		g.setColor(SLOT_BG);
		g.fillRect(x, y, CELL, CELL);
		if (itemId > 0)
		{
			if (module.carriedCount(itemId) < Math.max(1, qty))
			{
				g.setColor(MISSING_TINT);
				g.fillRect(x, y, CELL, CELL);
			}
			Image sprite = itemManager.getImage(itemId, qty, qty > 1);
			if (sprite != null)
			{
				g.drawImage(sprite, x, y + (CELL - 21) / 2, CELL, 21, null);
			}
		}
		g.setColor(UiTokens.BORDER_ROW);
		g.drawRect(x, y, CELL, CELL);
	}

	private boolean bankOpen()
	{
		if (client == null)
		{
			return false;
		}
		Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return bank != null && !bank.isHidden();
	}
}
