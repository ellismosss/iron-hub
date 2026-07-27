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

	@Test
	public void payloadCarriesTheComputedScenario()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.ATTACK, 90, 0);
		StateFixture.stat(state, Skill.STRENGTH, 90, 0);

		// assumed levels = real + a super combat's worth on atk/str
		com.loadoutlab.engine.PlayerLevels assumed =
			new com.loadoutlab.engine.PlayerLevels(108, 108, 1, 1, 1, 1, 10);
		JsonObject payload = DpsExport.buildPayload(new Gson(), state, "Iron Hub - Dust devil",
			Map.of(EquipmentInventorySlot.WEAPON, 28997), 7249, "Dust devil",
			true, "crush (aggressive)", "", assumed, "Piety");

		JsonObject loadout = payload.getAsJsonArray("loadouts").get(0).getAsJsonObject();
		JsonObject style = loadout.getAsJsonObject("style");
		assertEquals("crush", style.get("type").getAsString());
		assertEquals("Aggressive", style.get("stance").getAsString());

		JsonObject boosts = loadout.getAsJsonObject("boosts");
		assertEquals(18, boosts.get("atk").getAsInt());
		assertEquals(18, boosts.get("str").getAsInt());
		assertEquals(0, boosts.get("ranged").getAsInt()); // unboosted styles stay 0

		assertEquals(1, loadout.getAsJsonArray("prayers").size());
		assertEquals(13, loadout.getAsJsonArray("prayers").get(0).getAsInt()); // PIETY
		assertTrue(loadout.get("spell").isJsonNull()); // empty spell stays none
	}

	@Test
	public void styleJsonCoversAllEngineVariants()
	{
		assertEquals("Rapid", DpsExport.styleJson("ranged rapid - Rune dart").get("stance").getAsString());
		assertEquals("ranged", DpsExport.styleJson("ranged accurate").get("type").getAsString());
		assertEquals("Accurate", DpsExport.styleJson("ranged accurate").get("stance").getAsString());
		// magic exports as Accurate: the engine's +2 matches the site's
		// Accurate stance (its Autocast stance adds 0)
		assertEquals("Accurate", DpsExport.styleJson("magic: Fire Surge").get("stance").getAsString());
		assertEquals("magic", DpsExport.styleJson("magic: Fire Surge").get("type").getAsString());
		assertEquals("Controlled", DpsExport.styleJson("stab (controlled)").get("stance").getAsString());
		assertEquals("stab", DpsExport.styleJson("stab (controlled)").get("type").getAsString());
	}

	@Test
	public void compoundPrayerLabelsSplitIntoBothIds()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		JsonObject payload = DpsExport.buildPayload(new Gson(), state, "x",
			Map.of(), 7249, "Dust devil", true, "magic: Fire Surge", "Fire Surge",
			null, "Augury + Mystic Vigour");
		JsonObject loadout = payload.getAsJsonArray("loadouts").get(0).getAsJsonObject();
		com.google.gson.JsonArray prayers = loadout.getAsJsonArray("prayers");
		assertEquals(2, prayers.size());
		assertEquals(15, prayers.get(0).getAsInt()); // AUGURY
		assertEquals(20, prayers.get(1).getAsInt()); // MYSTIC_VIGOUR
		assertEquals("Fire Surge", loadout.getAsJsonObject("spell").get("name").getAsString());
	}
}
