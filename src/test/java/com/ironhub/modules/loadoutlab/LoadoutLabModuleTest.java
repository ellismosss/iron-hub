package com.ironhub.modules.loadoutlab;

import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoadoutLabModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void savedSetupsPersistWithInventoryAndPouch()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 42L);
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("WEAPON", 12926);
		setup.equipment.put("HEAD", 13197);
		setup.inventory = new int[]{385, -1, 2434};
		setup.inventoryQty = new int[]{5, 0, 1};
		setup.pouchRunes = new int[]{554, 555, -1, -1};
		setup.pouchAmounts = new int[]{1000, 500, 0, 0};
		before.saveSetup("Kalphites", setup);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 42L);
		PersistedState.SavedSetup loaded = after.savedSetup("Kalphites");
		assertTrue(loaded != null);
		assertEquals((Integer) 12926, loaded.equipment.get("WEAPON"));
		assertEquals(385, loaded.inventory[0]);
		assertEquals(5, loaded.inventoryQty[0]);
		assertEquals(554, loaded.pouchRunes[0]);
		assertEquals(1000, loaded.pouchAmounts[0]);
		assertTrue(after.savedSetup("Zulrah") == null);
	}

	@Test
	public void equipmentRendersInOsrsLayoutOrder()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("RING", 28307);
		setup.equipment.put("HEAD", 13197);
		setup.equipment.put("WEAPON", 12926);
		Map<String, Integer> ordered = LoadoutLabModule.layoutOrder(setup);
		// head row first, weapon row before the gloves/boots/ring row
		assertEquals(java.util.List.of("HEAD", "WEAPON", "RING"),
			new java.util.ArrayList<>(ordered.keySet()));
	}
}
