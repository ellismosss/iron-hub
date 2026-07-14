package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InventorySetupsExportTest
{
	@Test
	public void portableShapeMatchesInventorySetups()
	{
		Gson gson = new Gson();
		int[] inv = new int[28];
		java.util.Arrays.fill(inv, -1);
		inv[0] = 385;  // shark
		inv[1] = 2434; // prayer potion(4)

		String json = InventorySetupsExport.buildJson(gson, "Iron Hub - Zulrah",
			Map.of(EquipmentInventorySlot.WEAPON, 12926, EquipmentInventorySlot.HEAD, 13197),
			inv, Map.of(385, 5));

		JsonObject portable = gson.fromJson(json, JsonObject.class);
		JsonObject setup = portable.getAsJsonObject("setup");
		assertEquals("Iron Hub - Zulrah", setup.get("name").getAsString());

		JsonArray eq = setup.getAsJsonArray("eq");
		assertEquals(14, eq.size());
		assertEquals(13197, eq.get(EquipmentInventorySlot.HEAD.getSlotIdx())
			.getAsJsonObject().get("id").getAsInt());
		assertEquals(12926, eq.get(EquipmentInventorySlot.WEAPON.getSlotIdx())
			.getAsJsonObject().get("id").getAsInt());
		assertTrue(eq.get(EquipmentInventorySlot.RING.getSlotIdx()).isJsonNull());

		JsonArray invOut = setup.getAsJsonArray("inv");
		assertEquals(28, invOut.size());
		assertEquals(385, invOut.get(0).getAsJsonObject().get("id").getAsInt());
		assertEquals(5, invOut.get(0).getAsJsonObject().get("q").getAsInt()); // stack size kept
		assertTrue(invOut.get(2).isJsonNull());

		assertEquals(42, portable.getAsJsonArray("layout").size());
	}

	@Test
	public void dpsPayloadCarriesTheMonster()
	{
		Gson gson = new Gson();
		com.ironhub.state.AccountState state;
		try
		{
			org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();
			temp.create();
			state = com.ironhub.state.StateFixture.state(temp.getRoot());
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
		JsonObject payload = DpsExport.buildPayload(gson, state, "Iron Hub - Zulrah",
			Map.of(EquipmentInventorySlot.WEAPON, 12926), 2042, "Zulrah");
		JsonObject monster = payload.getAsJsonObject("monster");
		assertEquals(2042, monster.get("id").getAsInt());
		assertEquals("Zulrah", monster.get("name").getAsString());
	}
}
