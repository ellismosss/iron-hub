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
		public String family;
		public String key;
		public String name;
		public boolean members;
		public boolean automatic;
		/** gameval InventoryID constant name this storage reads, or null. */
		public String container;
		/** allow-list of item ids owned by this storage, or null. */
		public List<Integer> items;
	}
}
