package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ironhub.state.AccountState;
import com.loadoutlab.engine.PlayerLevels;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
		return buildPayload(gson, state, loadoutName, equipment, monsterId, monsterName,
			onSlayerTask, null, null, null, null);
	}

	/**
	 * Payload carrying the FULL scenario the engine computed - the picked
	 * style, the assumed prayers and the assumed boost levels - so the
	 * calculator opens showing the same number the panel shows (2026-07-27:
	 * verified against a live share that gear+levels alone diverge by the
	 * stance auto-pick and any live boost).
	 *
	 * @param attackType    DpsResult.getAttackType() ("crush (aggressive)",
	 *                      "ranged rapid - Rune dart", "magic: Fire Surge");
	 *                      null keeps the template's style
	 * @param spellName     DpsResult.getSpellName(); the calculator
	 *                      rehydrates spells by name (empty/null = none)
	 * @param assumedLevels the levels the numbers used (real+assumed boost,
	 *                      never below live boosted); exported as the
	 *                      calculator's per-skill boost deltas vs real
	 * @param prayerNames   the assumed prayer label ("Piety",
	 *                      "Augury + Mystic Vigour"); empty/null = none
	 */
	public static JsonObject buildPayload(Gson gson, AccountState state, String loadoutName,
		Map<EquipmentInventorySlot, Integer> equipment, int monsterId, String monsterName,
		boolean onSlayerTask, String attackType, String spellName,
		PlayerLevels assumedLevels, String prayerNames)
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
		if (attackType != null && !attackType.isEmpty())
		{
			loadout.add("style", styleJson(attackType));
		}
		if (spellName != null && !spellName.isEmpty())
		{
			JsonObject spell = new JsonObject();
			spell.addProperty("name", spellName);
			loadout.add("spell", spell);
		}
		if (assumedLevels != null)
		{
			JsonObject boosts = loadout.getAsJsonObject("boosts");
			boosts.addProperty("atk", assumedLevels.getAttack() - state.getRealLevel(Skill.ATTACK));
			boosts.addProperty("str", assumedLevels.getStrength() - state.getRealLevel(Skill.STRENGTH));
			boosts.addProperty("def", assumedLevels.getDefence() - state.getRealLevel(Skill.DEFENCE));
			boosts.addProperty("ranged", assumedLevels.getRanged() - state.getRealLevel(Skill.RANGED));
			boosts.addProperty("magic", assumedLevels.getMagic() - state.getRealLevel(Skill.MAGIC));
		}
		if (prayerNames != null && !prayerNames.isEmpty())
		{
			JsonArray prayers = new JsonArray();
			for (String name : prayerNames.split(" \\+ "))
			{
				int id = prayerId(name.trim());
				if (id >= 0)
				{
					prayers.add(id);
				}
			}
			loadout.add("prayers", prayers);
		}
		return data;
	}

	/**
	 * The engine's picked variant as the calculator's style object. The
	 * calculator's math reads only type + stance (name is display); magic
	 * exports as Accurate because the engine's +2 matches the calculator's
	 * Accurate stance (its Autocast stance adds 0).
	 */
	static JsonObject styleJson(String attackType)
	{
		String type;
		String stance;
		if (attackType.startsWith("ranged"))
		{
			type = "ranged";
			stance = attackType.contains("rapid") ? "Rapid" : "Accurate";
		}
		else if (attackType.startsWith("magic"))
		{
			type = "magic";
			stance = "Accurate";
		}
		else
		{
			type = attackType.split(" ")[0];
			int open = attackType.indexOf('(');
			int close = attackType.indexOf(')');
			String s = open >= 0 && close > open ? attackType.substring(open + 1, close) : "accurate";
			stance = Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
		}
		JsonObject style = new JsonObject();
		style.addProperty("name", stance);
		style.addProperty("type", type);
		style.addProperty("stance", stance);
		return style;
	}

	/** PrayerBonuses assumption labels -> the calculator's Prayer enum
	 * (weirdgloop/osrs-dps-calc src/enums/Prayer.ts, serializationVersion
	 * 10). Unknown labels are skipped, never guessed. */
	static int prayerId(String name)
	{
		switch (name)
		{
			case "Piety": return 13;
			case "Chivalry": return 12;
			case "Incredible Reflexes": return 9;
			case "Ultimate Strength": return 8;
			case "Rigour": return 14;
			case "Deadeye": return 19;
			case "Eagle Eye": return 10;
			case "Augury": return 15;
			case "Mystic Vigour": return 20;
			case "Mystic Might": return 11;
			default: return -1;
		}
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
