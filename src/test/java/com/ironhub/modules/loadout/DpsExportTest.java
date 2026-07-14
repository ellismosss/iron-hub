package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DpsExportTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void payloadCarriesSkillsAndEquipmentIds()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.RANGED, 85, 0);
		StateFixture.stat(state, Skill.HITPOINTS, 90, 0);

		JsonObject payload = DpsExport.buildPayload(new Gson(), state, "Iron Hub — Zulrah",
			Map.of(EquipmentInventorySlot.WEAPON, 12926, EquipmentInventorySlot.AMULET, 1704));

		assertEquals(10, payload.get("serializationVersion").getAsInt());
		JsonObject loadout = payload.getAsJsonArray("loadouts").get(0).getAsJsonObject();
		assertEquals("Iron Hub — Zulrah", loadout.get("name").getAsString());

		JsonObject skills = loadout.getAsJsonObject("skills");
		assertEquals(85, skills.get("ranged").getAsInt());
		assertEquals(90, skills.get("hp").getAsInt());
		assertEquals(1, skills.get("atk").getAsInt()); // untrained default

		JsonObject gear = loadout.getAsJsonObject("equipment");
		assertEquals(12926, gear.getAsJsonObject("weapon").get("id").getAsInt());
		assertEquals(1704, gear.getAsJsonObject("neck").get("id").getAsInt()); // AMULET -> neck
		assertTrue(gear.get("head").isJsonNull()); // unsolved slots stay null
	}
}
