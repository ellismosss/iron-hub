package com.ironhub.data;

import java.util.List;
import java.util.Map;

/**
 * Combat-style option names per weapon type (COMBAT_WEAPON_CATEGORY varbit
 * value): the combat tab's button labels ("Lunge", "Hack") and attack types
 * (Stab/Slash/Crush) are clientscript string literals, not queryable cache
 * config, so they ship as a pack parsed from the RuneStar cs2 dump at a
 * pinned commit (tools/gen_weapon_styles.py).
 *
 * <p>HONESTY GATE: a live client must cross-check a type's style-kind
 * signature against the cache (the AttackStylesPlugin enum-3908 walk) via
 * {@link #matchesKinds} before trusting a row — a reused or newer type id
 * then falls back to "Attack style N" instead of showing a wrong name.
 */
public class WeaponStylesPack
{
	public int version;
	public String source;
	public Map<String, TypeEntry> types;

	public static class TypeEntry
	{
		public List<Option> options;
	}

	public static class Option
	{
		public int index;
		public String button;
		public String style; // Accurate/Aggressive/Controlled/Defensive/Rapid/Longrange; null = bulwark block
		public String type;  // Stab/Slash/Crush/Ranged/Magic; null = bulwark block
	}

	/** The option for a weapon type + selected style index, or null. */
	public Option option(int weaponType, int styleIndex)
	{
		TypeEntry entry = types.get(String.valueOf(weaponType));
		if (entry == null)
		{
			return null;
		}
		for (Option option : entry.options)
		{
			if (option.index == styleIndex)
			{
				return option;
			}
		}
		return null;
	}

	/**
	 * Whether the pack row's style signature is compatible with the CACHE's
	 * style kinds for this weapon type (param 1407 strings by option index,
	 * null where undefined). The cs2 tooltip styles and cache kinds use
	 * different vocabularies for ranged/magic — Rapid and ranged/magic
	 * Accurate both read "Ranging"/"Casting" in the cache.
	 */
	public boolean matchesKinds(int weaponType, String[] cacheKinds)
	{
		TypeEntry entry = types.get(String.valueOf(weaponType));
		if (entry == null || cacheKinds == null)
		{
			return false;
		}
		for (Option option : entry.options)
		{
			String kind = option.index < cacheKinds.length ? cacheKinds[option.index] : null;
			if (!compatible(option, kind))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean compatible(Option option, String kind)
	{
		if (option.style == null) // bulwark "No attacking!" block
		{
			return true;
		}
		if (kind == null)
		{
			return false;
		}
		switch (option.style)
		{
			case "Accurate":
				return kind.equals("Accurate") || kind.equals("Ranging") || kind.equals("Casting");
			case "Rapid":
				return kind.equals("Ranging");
			case "Longrange":
				return kind.equals("Longrange");
			default: // Aggressive / Controlled / Defensive map 1:1
				return kind.equals(option.style);
		}
	}
}
