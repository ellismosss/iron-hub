package com.ironhub.data;

import java.util.List;

/**
 * Items with a dedicated storage home (data/bank-storage.json, generated
 * by tools/gen_bank_storage.py from the Wasted Bank Space hub plugin at
 * its pinned commit — BSD-2, Riley McGee). Powers the Bank-hub "Bank
 * space saver" module: anything in the bank that also appears here could
 * live in its dedicated storage instead of a bank slot.
 *
 * <p>{@code bis} marks best-in-slot gear (armour case / cape rack /
 * magic wardrobe entries) the player may deliberately keep banked —
 * hidden from flagging by default, toggleable.</p>
 */
public class BankStoragePack
{
	public int version;
	public String source;
	public List<Location> locations;

	public static class Location
	{
		public String id;
		public String name;
		public List<Entry> items;
	}

	public static class Entry
	{
		public int id;
		public String name; // baked at generation — offline/headless display
		public boolean bis;
	}
}
