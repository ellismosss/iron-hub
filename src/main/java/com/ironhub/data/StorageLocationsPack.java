package com.ironhub.data;

import java.util.List;
import java.util.Map;

/**
 * The storage-location registry + the static item allow-lists that
 * disambiguate co-located storages (data/storage-locations.json, generated
 * by tools/gen_storage_locations.py from the "Dude, Where's My Stuff?" hub
 * plugin at its pinned commit — BSD-2, (c) 2022 Thource).
 *
 * <p>Powers the Bank-hub "Where's my stuff" module and feeds the Gear
 * library's ownership line: for every storable place in the game, which
 * family it belongs to, its display name, and — for the storages that share
 * one backing container (the whole POH costume room hangs off
 * InventoryID.POH_COSTUMES) — the {@code items} allow-list that attributes a
 * given item to it (e.g. a Fancy dress box entry vs an Armour case entry).</p>
 *
 * <p>No container ids: the detection Java references the gameval constants
 * directly. {@link #familyLabels} is the parenthesised suffix a surface
 * appends, e.g. "Fancy dress box (PoH)".</p>
 */
public class StorageLocationsPack
{
	@com.google.gson.annotations.SerializedName("_source")
	public String source;

	public Map<String, String> familyLabels;

	public List<Storage> storages;

	public static class Storage
	{
		/** globally-unique handle ("family:key"); the snapshot map key. */
		public String id;
		public String family;
		public String key;
		public String name;
		public boolean members;
		public boolean automatic;
		/** gameval InventoryID constant name this storage reads, or null. */
		public String container;
		/** resolved InventoryID value this storage reads, or 0. */
		public int containerId;
		/** detection mode the module drives this storage by: "poh" (allow-list
		 *  attribution of the shared costume container), "container" (a plain
		 *  ItemContainerChanged read), or null (bespoke / not yet detected). */
		public String mode;
		/** allow-list of item ids owned by this storage, or null. */
		public List<Integer> items;
		/** object-mount detection (cape hanger): a spawned object id -> the
		 *  [cape, hood] it means is stored. Null unless mode == "objectmount". */
		public List<Mount> mounts;
		/** object ids whose spawn means the storage is empty (empty hanger). */
		public List<Integer> clearObjects;
		/** varbit-static detection (mode == "varbits"): each item's quantity is
		 *  read from its varbit (plank sack, blast furnace, fossil storage). */
		public List<VarbitItem> varbitItems;
		/** varbit-index detection (mode == "varbitindex"): one varbit's value
		 *  indexes this item-id array (0/negative = empty; pickaxe statue). */
		public int indexVarbit;
		public List<Integer> indexItems;
		/** slot detection (mode == "slots"): N (type, count) slots — rune pouch,
		 *  bolt pouch, Dizana's quiver. The type value resolves to an item id
		 *  via typeKind ("array" -> typeArray[type], "enum" -> game enum
		 *  typeEnum, "direct" -> the value IS the item id); count is the qty.
		 *  varp = read the slot vars as VarPlayers, not varbits (the quiver). */
		public List<Slot> slots;
		public String typeKind;
		public List<Integer> typeArray;
		public int typeEnum;
		public boolean varp;
		/** compute detection (mode == "compute"): per-item derived formulas. */
		public List<Compute> computeItems;
	}

	public static class Slot
	{
		public int typeVarbit;
		public int countVarbit;
	}

	/** compute detection (mode == "compute"): per-item formulas for the
	 *  storages the reference derives with arithmetic — Tool Leprechaun,
	 *  Elnock Inquisitor. Each item is one of four kinds:
	 *  sum (qty = Σ term.varbit × mult), variant (a sum whose item id swaps to
	 *  variantItemId when variantVarbit == 1), index (indexVarbit picks the id
	 *  from indexArray, qty 1), type (typeVarbit 0/1 -> emptyId, else filledId;
	 *  qty 0 when type 0). */
	public static class Compute
	{
		public String kind;
		public int itemId;
		public List<Term> terms;
		public int variantVarbit;
		public int variantItemId;
		public int indexVarbit;
		public List<Integer> indexArray;
		public int typeVarbit;
		public int emptyId;
		public int filledId;
	}

	public static class Term
	{
		public int varbit;
		public int mult = 1;
	}

	public static class Mount
	{
		public int object;
		public List<Integer> items;
	}

	public static class VarbitItem
	{
		public int varbit;
		public int itemId;
		/** quantity = varbit value × multiplier (default 1). Lets one mode
		 *  cover item stacks, coin balances (×1000 etc.) and point icons. */
		public int multiplier = 1;
		/** display-name override (minigame points show "Points"/"Reward
		 *  permits" over their icon item, not the icon's real name). */
		public String name;
	}
}
