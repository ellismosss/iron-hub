package com.ironhub.modules.farming;

import com.ironhub.state.PersistedState;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

/**
 * Reorganises the bank into a farm run's saved gear + inventory, the way
 * the Inventory Setups plugin does it (github.com/dillydill123/inventory-
 * setups, BSD-2) — a hidden Bank Tag with a {@link Layout} that pins each
 * setup item to a bank-grid position, opened via {@link BankTagsService}
 * so the real bank filters and lays itself out to match. Not a floating
 * overlay: the bank itself rearranges.
 *
 * <p>All calls mutate bank/tag state and MUST run on the client thread.
 * Null services (headless tests) make every method a no-op.
 */
public class FarmBankLayout
{
	private static final String TAG_PREFIX = "_ironhubfarm_";

	/**
	 * Setup slot -> position in the 8-wide bank grid — the Inventory Setups
	 * "preset" arrangement: equipment mirrors the worn-items interface on
	 * the left three columns, inventory fills the right four columns.
	 */
	private static final Map<EquipmentInventorySlot, Integer> EQUIPMENT_POS = new LinkedHashMap<>();

	static
	{
		EQUIPMENT_POS.put(EquipmentInventorySlot.HEAD, 1);
		EQUIPMENT_POS.put(EquipmentInventorySlot.CAPE, 8);
		EQUIPMENT_POS.put(EquipmentInventorySlot.AMULET, 9);
		EQUIPMENT_POS.put(EquipmentInventorySlot.AMMO, 10);
		EQUIPMENT_POS.put(EquipmentInventorySlot.WEAPON, 16);
		EQUIPMENT_POS.put(EquipmentInventorySlot.BODY, 17);
		EQUIPMENT_POS.put(EquipmentInventorySlot.SHIELD, 18);
		EQUIPMENT_POS.put(EquipmentInventorySlot.LEGS, 25);
		EQUIPMENT_POS.put(EquipmentInventorySlot.GLOVES, 32);
		EQUIPMENT_POS.put(EquipmentInventorySlot.BOOTS, 33);
		EQUIPMENT_POS.put(EquipmentInventorySlot.RING, 34);
	}

	private final BankTagsService bankTagsService;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ItemManager itemManager;

	private String appliedTag;
	private int appliedSignature;

	public FarmBankLayout(BankTagsService bankTagsService, TagManager tagManager,
		LayoutManager layoutManager, ItemManager itemManager)
	{
		this.bankTagsService = bankTagsService;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.itemManager = itemManager;
	}

	private boolean available()
	{
		return bankTagsService != null && tagManager != null
			&& layoutManager != null && itemManager != null;
	}

	/** Bank-grid position -> item id for a setup (equipment then inventory).
	 *  Pure — static for tests. */
	static Map<Integer, Integer> positions(PersistedState.SavedSetup setup)
	{
		Map<Integer, Integer> byPos = new LinkedHashMap<>();
		for (Map.Entry<EquipmentInventorySlot, Integer> slot : EQUIPMENT_POS.entrySet())
		{
			Integer id = setup.equipment.get(slot.getKey().name());
			if (id != null && id > 0)
			{
				byPos.put(slot.getValue(), id);
			}
		}
		int invCol = 4;
		int invRow = 0;
		for (int itemId : setup.inventory)
		{
			if (itemId > 0)
			{
				byPos.put(invCol + invRow * 8, itemId);
			}
			if (invCol == 7)
			{
				invCol = 4;
				invRow++;
			}
			else
			{
				invCol++;
			}
		}
		return byPos;
	}

	/**
	 * Filter and lay the bank out to this run's setup. Rebuilds the hidden
	 * tag + layout only when the run or its setup actually changed, then
	 * (re)opens the tag so the bank rearranges. Client thread.
	 */
	public void apply(String runName, PersistedState.SavedSetup setup)
	{
		if (!available())
		{
			return;
		}
		String tag = TAG_PREFIX + runName.toLowerCase().replaceAll("[^a-z0-9]", "");
		Map<Integer, Integer> byPos = positions(setup);
		int signature = byPos.hashCode();
		if (tag.equals(appliedTag) && signature == appliedSignature
			&& tag.equals(bankTagsService.getActiveTag()))
		{
			// already showing exactly this — every openBankTag forces a full
			// bank relayout, and Bank Tags keeps its active tag across bank
			// rebuilds (2026-07-18 bank-open freeze audit)
			return;
		}
		if (!tag.equals(appliedTag) || signature != appliedSignature)
		{
			removeApplied();
			Layout layout = new Layout(tag);
			for (Map.Entry<Integer, Integer> e : byPos.entrySet())
			{
				int canonical = itemManager.canonicalize(e.getValue());
				layout.setItemAtPos(canonical, e.getKey());
				tagManager.addTag(canonical, tag, false);
			}
			layoutManager.saveLayout(layout);
			tagManager.setHidden(tag, true); // never clutters the tag bar
			appliedTag = tag;
			appliedSignature = signature;
		}
		bankTagsService.openBankTag(tag, BankTagsService.OPTION_HIDE_TAG_NAME);
	}

	/** Close the setup view and remove its (hidden) tag + layout so nothing
	 *  is left behind in the player's bank. Client thread. */
	public void clear()
	{
		if (!available() || appliedTag == null)
		{
			return;
		}
		bankTagsService.closeBankTag();
		removeApplied();
	}

	public boolean isApplied()
	{
		return appliedTag != null;
	}

	private void removeApplied()
	{
		if (appliedTag != null)
		{
			tagManager.removeTag(appliedTag);
			layoutManager.removeLayout(appliedTag);
			appliedTag = null;
			appliedSignature = 0;
		}
	}
}
