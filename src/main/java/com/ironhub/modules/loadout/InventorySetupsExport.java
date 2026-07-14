package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;

/**
 * Inventory Setups plugin export: their "Import setup from clipboard"
 * portable format, verified against dillydill123/inventory-setups
 * serialization sources (InventorySetupPortable = {setup, layout};
 * items serialize as {id, q?}, equipment indexed by equipment slot,
 * inventory as 28 entries). Clipboard-only — no plugin dependency,
 * per Hub rules.
 */
final class InventorySetupsExport
{
	private InventorySetupsExport()
	{
	}

	static String buildJson(Gson gson, String name,
		Map<EquipmentInventorySlot, Integer> equipment,
		int[] inventorySlots, Map<Integer, Integer> inventoryQuantities)
	{
		JsonObject setup = new JsonObject();

		JsonArray inv = new JsonArray();
		for (int i = 0; i < 28; i++)
		{
			int id = i < inventorySlots.length ? inventorySlots[i] : -1;
			if (id > 0)
			{
				inv.add(item(id, inventoryQuantities.getOrDefault(id, 1)));
			}
			else
			{
				inv.add(JsonNull.INSTANCE);
			}
		}
		setup.add("inv", inv);

		JsonArray eq = new JsonArray();
		Integer[] bySlot = new Integer[14];
		equipment.forEach((slot, id) -> bySlot[slot.getSlotIdx()] = id);
		for (int i = 0; i < 14; i++)
		{
			eq.add(bySlot[i] == null ? JsonNull.INSTANCE : item(bySlot[i], 1));
		}
		setup.add("eq", eq);

		setup.addProperty("name", name);
		JsonObject highlight = new JsonObject();
		highlight.addProperty("value", 0xFFDC8A00); // Iron Hub accent
		highlight.addProperty("falpha", 0.0f);
		setup.add("hc", highlight);

		// simple layout: equipment then inventory ids in order (their
		// layout is a bank-tab arrangement; imports may rearrange freely)
		JsonArray layout = new JsonArray();
		for (int i = 0; i < 14; i++)
		{
			layout.add(bySlot[i] == null ? -1 : bySlot[i]);
		}
		for (int i = 0; i < 28; i++)
		{
			layout.add(i < inventorySlots.length && inventorySlots[i] > 0 ? inventorySlots[i] : -1);
		}

		JsonObject portable = new JsonObject();
		portable.add("setup", setup);
		portable.add("layout", layout);
		return gson.toJson(portable);
	}

	private static JsonObject item(int id, int quantity)
	{
		JsonObject item = new JsonObject();
		item.addProperty("id", id);
		if (quantity > 1)
		{
			item.addProperty("q", quantity);
		}
		return item;
	}
}
