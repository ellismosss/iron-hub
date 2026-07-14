package com.ironhub.modules.loadout;

import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Golden setups: known gear must rank correctly per style. */
public class LoadoutSolverTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static final int WEAPON_SLOT = EquipmentInventorySlot.WEAPON.getSlotIdx();

	// whip: slash 82 str 82 · rune scim: slash 45 str 44 · msb: range 69
	private final Map<Integer, ItemStats> stats = Map.of(
		4151, weapon(0, 82, 0, 0, 82, 0, 0),
		1333, weapon(7, 45, -2, 0, 44, 0, 0),
		861, weapon(0, 0, 0, 69, 0, 0, 0));

	private static ItemStats weapon(int stab, int slash, int crush, int range,
		int str, int rstr, int amagic)
	{
		ItemEquipmentStats equip = ItemEquipmentStats.builder()
			.slot(WEAPON_SLOT)
			.astab(stab).aslash(slash).acrush(crush).arange(range).amagic(amagic)
			.str(str).rstr(rstr)
			.build();
		return new ItemStats(false, 0, 0, equip);
	}

	private final IntFunction<ItemStats> lookup = id -> stats.get(id);

	@Test
	public void meleePicksWhipOverRuneScim()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(4151, 1, 1333, 1, 861, 1));

		Map<EquipmentInventorySlot, Integer> best = LoadoutSolver.solve(state, "Melee", lookup);
		assertEquals((Integer) 4151, best.get(EquipmentInventorySlot.WEAPON));
	}

	@Test
	public void rangePicksBowAndIgnoresMeleeGear()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(4151, 1, 861, 1));

		Map<EquipmentInventorySlot, Integer> best = LoadoutSolver.solve(state, "Range", lookup);
		assertEquals((Integer) 861, best.get(EquipmentInventorySlot.WEAPON));
	}

	@Test
	public void noOwnedGearMeansEmptySlots()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		Map<EquipmentInventorySlot, Integer> best = LoadoutSolver.solve(state, "Melee", lookup);
		assertFalse(best.containsKey(EquipmentInventorySlot.WEAPON));
	}
}
