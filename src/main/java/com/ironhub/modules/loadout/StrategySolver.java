package com.ironhub.modules.loadout;

import com.ironhub.state.AccountState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemStats;

/**
 * Best available setup for a wiki strategy: per slot, the highest-ranked
 * candidate the account owns anywhere (bank, inventory, worn — any
 * variation, resolved to the owned variant id). A two-handed weapon
 * clears the shield slot.
 */
final class StrategySolver
{
	/** wiki template slot key → equipment slot. */
	static final Map<String, EquipmentInventorySlot> SLOTS = Map.ofEntries(
		Map.entry("head", EquipmentInventorySlot.HEAD),
		Map.entry("cape", EquipmentInventorySlot.CAPE),
		Map.entry("neck", EquipmentInventorySlot.AMULET),
		Map.entry("ammo", EquipmentInventorySlot.AMMO),
		Map.entry("weapon", EquipmentInventorySlot.WEAPON),
		Map.entry("body", EquipmentInventorySlot.BODY),
		Map.entry("shield", EquipmentInventorySlot.SHIELD),
		Map.entry("legs", EquipmentInventorySlot.LEGS),
		Map.entry("hands", EquipmentInventorySlot.GLOVES),
		Map.entry("feet", EquipmentInventorySlot.BOOTS),
		Map.entry("ring", EquipmentInventorySlot.RING));

	private StrategySolver()
	{
	}

	/** Highest-ranked owned candidate per slot; slots with none absent. */
	static Map<EquipmentInventorySlot, Integer> solve(AccountState state,
		WikiStrategy strategy, IntFunction<ItemStats> statsLookup)
	{
		Map<EquipmentInventorySlot, Integer> best = new EnumMap<>(EquipmentInventorySlot.class);
		strategy.slots.forEach((slotKey, candidates) ->
		{
			EquipmentInventorySlot slot = SLOTS.get(slotKey);
			if (slot == null)
			{
				return;
			}
			for (WikiStrategy.Candidate candidate : candidates)
			{
				int owned = state.ownedVariantOf(candidate.itemId);
				if (owned > 0)
				{
					best.put(slot, owned);
					break;
				}
			}
		});

		Integer weapon = best.get(EquipmentInventorySlot.WEAPON);
		if (weapon != null && statsLookup != null)
		{
			ItemStats stats = statsLookup.apply(weapon);
			if (stats != null && stats.getEquipment() != null && stats.getEquipment().isTwoHanded())
			{
				best.remove(EquipmentInventorySlot.SHIELD);
			}
		}
		return best;
	}
}
