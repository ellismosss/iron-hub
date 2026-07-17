package com.ironhub.modules.loadoutlab;

import com.ironhub.state.PersistedState;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Pure diff of a saved setup against what the player currently wears and
 * carries — the tint rules for the setup viewer. Per slot, with S = the
 * setup's item and C = the current item (variation-aware compares, so a
 * charged glory settles the glory slot):
 *
 * <ul>
 * <li>S == C (including both empty): show S, no tint.</li>
 * <li>S present, C different: show S, SWAP (orange — withdraw/equip it).</li>
 * <li>S empty, C present and C appears elsewhere in the setup: show the
 *     empty setup slot (the item is accounted for at its own slot).</li>
 * <li>S empty, C present and C appears NOWHERE in the setup: show C,
 *     DEPOSIT (red — the setup has no place for it).</li>
 * </ul>
 *
 * Quantities are ignored for tinting (same item, short stack still reads
 * as matching here — the bank withdraw list is count-aware instead).
 */
final class SetupDiff
{
	enum Tint
	{
		NONE, SWAP, DEPOSIT
	}

	/** What a diffed slot displays: the item to draw + its tint. */
	static final class Slot
	{
		final int itemId; // <= 0 = empty
		final Tint tint;

		Slot(int itemId, Tint tint)
		{
			this.itemId = itemId;
			this.tint = tint;
		}
	}

	private SetupDiff()
	{
	}

	private static int canonical(int itemId)
	{
		return itemId > 0 ? ItemVariationMapping.map(itemId) : -1;
	}

	/** Canonical ids present anywhere in the setup (gear, inventory, pouch). */
	static Set<Integer> setupItems(PersistedState.SavedSetup setup)
	{
		Set<Integer> ids = new HashSet<>();
		if (setup.equipment != null)
		{
			for (Integer id : setup.equipment.values())
			{
				if (id != null && id > 0)
				{
					ids.add(canonical(id));
				}
			}
		}
		for (int id : setup.inventory)
		{
			if (id > 0)
			{
				ids.add(canonical(id));
			}
		}
		for (int id : setup.pouchRunes)
		{
			if (id > 0)
			{
				ids.add(canonical(id));
			}
		}
		return ids;
	}

	private static Slot slot(int setupId, int currentId, Set<Integer> setupIds)
	{
		boolean setupHas = setupId > 0;
		boolean currentHas = currentId > 0;
		if (setupHas)
		{
			return canonical(setupId) == canonical(currentId)
				? new Slot(setupId, Tint.NONE)
				: new Slot(setupId, Tint.SWAP);
		}
		if (currentHas && !setupIds.contains(canonical(currentId)))
		{
			return new Slot(currentId, Tint.DEPOSIT);
		}
		return new Slot(-1, Tint.NONE);
	}

	/** Per equipment slot name (EquipmentInventorySlot.name()) -> diffed slot. */
	static Map<String, Slot> equipment(PersistedState.SavedSetup setup, int[] wornSlots)
	{
		Set<Integer> setupIds = setupItems(setup);
		Map<String, Slot> out = new LinkedHashMap<>();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Integer saved = setup.equipment != null ? setup.equipment.get(slot.name()) : null;
			int setupId = saved != null ? saved : -1;
			int wornId = slot.getSlotIdx() < wornSlots.length ? wornSlots[slot.getSlotIdx()] : -1;
			out.put(slot.name(), slot(setupId, wornId, setupIds));
		}
		return out;
	}

	/** 28 diffed inventory slots (setup slot order vs current slot order). */
	static Slot[] inventory(PersistedState.SavedSetup setup, int[] currentSlots)
	{
		Set<Integer> setupIds = setupItems(setup);
		Slot[] out = new Slot[28];
		for (int i = 0; i < 28; i++)
		{
			int setupId = i < setup.inventory.length ? setup.inventory[i] : -1;
			int currentId = i < currentSlots.length ? currentSlots[i] : -1;
			out[i] = slot(setupId, currentId, setupIds);
		}
		return out;
	}
}
