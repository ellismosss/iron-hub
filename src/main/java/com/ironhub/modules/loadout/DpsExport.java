package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ironhub.state.AccountState;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;

/**
 * Wiki DPS calculator export (DESIGN.md §3.6). Protocol verified against
 * the live calculator and its source (weirdgloop/osrs-dps-calc):
 * POST the ImportableData JSON to the shortlink endpoint, get an id back,
 * open https://dps.osrs.wiki/?id=&lt;id&gt;. Equipment hydrates by item id
 * on their side (parseLoadoutsFromImportedData), so slots only carry ids.
 * The template is a captured default state (serializationVersion 10);
 * their import migrates older versions, so drift fails soft.
 */
public final class DpsExport
{
	public static final String ENDPOINT = "https://tools.runescape.wiki/osrs-dps/shortlink";
	public static final String SHARE_URL = "https://dps.osrs.wiki/?id=";

	// calculator slot keys per EquipmentInventorySlot
	private static final Map<EquipmentInventorySlot, String> SLOT_KEYS = Map.ofEntries(
		Map.entry(EquipmentInventorySlot.HEAD, "head"),
		Map.entry(EquipmentInventorySlot.CAPE, "cape"),
		Map.entry(EquipmentInventorySlot.AMULET, "neck"),
		Map.entry(EquipmentInventorySlot.AMMO, "ammo"),
		Map.entry(EquipmentInventorySlot.WEAPON, "weapon"),
		Map.entry(EquipmentInventorySlot.BODY, "body"),
		Map.entry(EquipmentInventorySlot.SHIELD, "shield"),
		Map.entry(EquipmentInventorySlot.LEGS, "legs"),
		Map.entry(EquipmentInventorySlot.GLOVES, "hands"),
		Map.entry(EquipmentInventorySlot.BOOTS, "feet"),
		Map.entry(EquipmentInventorySlot.RING, "ring"));

	private DpsExport()
	{
	}

	/** The ImportableData payload: template + live skills + solved gear. */
	static JsonObject buildPayload(Gson gson, AccountState state, String loadoutName,
		Map<EquipmentInventorySlot, Integer> equipment)
	{
		return buildPayload(gson, state, loadoutName, equipment, -1, null);
	}

	/**
	 * Payload with the target monster preselected. The calculator
	 * rehydrates monsters by id from its own dataset (state.tsx
	 * updateImportedData), so id + name is all it needs.
	 */
	public static JsonObject buildPayload(Gson gson, AccountState state, String loadoutName,
		Map<EquipmentInventorySlot, Integer> equipment, int monsterId, String monsterName)
	{
		return buildPayload(gson, state, loadoutName, equipment, monsterId, monsterName, true);
	}

	public static JsonObject buildPayload(Gson gson, AccountState state, String loadoutName,
		Map<EquipmentInventorySlot, Integer> equipment, int monsterId, String monsterName, boolean onSlayerTask)
	{
		JsonObject data = loadTemplate(gson);
		if (monsterId > 0 && monsterName != null)
		{
			JsonObject monster = data.getAsJsonObject("monster");
			monster.addProperty("id", monsterId);
			monster.addProperty("name", monsterName);
			monster.addProperty("version", "");
		}
		JsonObject loadout = data.getAsJsonArray("loadouts").get(0).getAsJsonObject();
		loadout.addProperty("name", loadoutName);

		JsonObject skills = loadout.getAsJsonObject("skills");
		skills.addProperty("atk", state.getRealLevel(Skill.ATTACK));
		skills.addProperty("def", state.getRealLevel(Skill.DEFENCE));
		skills.addProperty("hp", state.getRealLevel(Skill.HITPOINTS));
		skills.addProperty("magic", state.getRealLevel(Skill.MAGIC));
		skills.addProperty("prayer", state.getRealLevel(Skill.PRAYER));
		skills.addProperty("ranged", state.getRealLevel(Skill.RANGED));
		skills.addProperty("str", state.getRealLevel(Skill.STRENGTH));
		skills.addProperty("mining", state.getRealLevel(Skill.MINING));
		skills.addProperty("herblore", state.getRealLevel(Skill.HERBLORE));

		loadout.getAsJsonObject("buffs").addProperty("onSlayerTask", onSlayerTask);
		JsonObject gear = loadout.getAsJsonObject("equipment");
		SLOT_KEYS.forEach((slot, key) ->
		{
			Integer itemId = equipment.get(slot);
			if (itemId != null)
			{
				JsonObject piece = new JsonObject();
				piece.addProperty("id", itemId);
				gear.add(key, piece);
			}
		});
		return data;
	}

	private static JsonObject loadTemplate(Gson gson)
	{
		InputStream in = DpsExport.class.getResourceAsStream("/integrations/dps-export-template.json");
		if (in == null)
		{
			throw new IllegalStateException("missing dps export template");
		}
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, JsonObject.class);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("corrupt dps export template", e);
		}
	}
}
