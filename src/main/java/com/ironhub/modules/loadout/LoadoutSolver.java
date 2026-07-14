package com.ironhub.modules.loadout;

import com.ironhub.state.AccountState;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;

/**
 * Best-in-bank loadout: per equipment slot, the owned item with the best
 * style-relevant score. Local heuristic — authoritative numbers live in
 * the wiki DPS calculator export (DESIGN.md §3.6). BETA: golden-tested
 * on known setups, but heuristic by design.
 *
 * ponytail: bank-only candidates and no 2h-vs-shield resolution yet;
 * upgrade path is enumerating inv/equip ids and a 2h/dual comparison.
 */
final class LoadoutSolver
{
	private LoadoutSolver()
	{
	}

	/** slot -> itemId of the best owned item; slots with no candidate absent. */
	static Map<EquipmentInventorySlot, Integer> solve(AccountState state, String style,
		IntFunction<ItemStats> statsLookup)
	{
		Map<EquipmentInventorySlot, Integer> best = new EnumMap<>(EquipmentInventorySlot.class);
		Map<EquipmentInventorySlot, Double> bestScore = new EnumMap<>(EquipmentInventorySlot.class);

		for (int itemId : state.getBankSnapshot().keySet())
		{
			ItemStats stats = statsLookup.apply(itemId);
			ItemEquipmentStats equip = stats == null ? null : stats.getEquipment();
			if (equip == null)
			{
				continue;
			}
			EquipmentInventorySlot slot = slotOf(equip.getSlot());
			if (slot == null)
			{
				continue;
			}
			double score = score(equip, style);
			if (score > 0 && score > bestScore.getOrDefault(slot, 0.0))
			{
				bestScore.put(slot, score);
				best.put(slot, itemId);
			}
		}
		return best;
	}

	/** Offence-weighted heuristic per style; documented, not authoritative. */
	static double score(ItemEquipmentStats equip, String style)
	{
		switch (style)
		{
			case "Range":
				return equip.getArange() + 1.5 * equip.getRstr();
			case "Mage":
				return equip.getAmagic() + 10 * equip.getMdmg();
			default: // Melee
				return Math.max(equip.getAstab(), Math.max(equip.getAslash(), equip.getAcrush()))
					+ 1.5 * equip.getStr();
		}
	}

	private static EquipmentInventorySlot slotOf(int slotIdx)
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (slot.getSlotIdx() == slotIdx)
			{
				return slot;
			}
		}
		return null;
	}
}
