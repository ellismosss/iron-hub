package com.ironhub.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;

/**
 * Typed model of {@code data/recommended-equipment.json} — the wiki's own
 * recommended-gear tables for 452 activities (strategy pages + slayer-task
 * pages), bundled offline by tools/gen_recommended_equipment.py. One
 * activity entry per table: page, style label ("Melee", "Bursting/
 * Barraging"), ranked item lists per slot.
 *
 * <p>{@link #match(String)} joins a monster or slayer-task name to its
 * entries; task names are category plurals while monster names are
 * singular, so matching tries both directions ("Dust devil" ↔ "Slayer
 * task/Dust devils"). A miss returns an empty list — never a guess.
 */
@Data
public class RecommendedEquipmentPack
{
	/** Worn-equipment slot order for display (the game's own ordering). */
	public static final String[] SLOT_ORDER = {
		"head", "cape", "neck", "ammo", "weapon", "2h", "body", "shield",
		"legs", "hands", "feet", "ring", "special"};

	private int version;
	private String source;
	private List<Activity> activities;

	@Data
	public static class Activity
	{
		private String page;
		private String style;
		private Map<String, List<Rec>> slots;
	}

	@Data
	public static class Rec
	{
		private String name;
		/** null when the wiki names a family with no single id
		 *  ("Cape of Accomplishment (t)") — ownership undetectable. */
		private Integer itemId;

		/** The name without a wiki section anchor ("God capes#Imbuing" →
		 *  "God capes") — anchors are link plumbing, not display copy. */
		public String displayName()
		{
			return name == null ? "" : name.split("#", 2)[0].trim();
		}
	}

	/** Lazy normalized-name index; volatile because pack instances are
	 *  shared across threads (2026-07-20 audit rule). */
	private transient volatile Map<String, List<Activity>> byName;

	public List<Activity> match(String name)
	{
		if (name == null || name.isBlank())
		{
			return List.of();
		}
		Map<String, List<Activity>> index = byName;
		if (index == null)
		{
			index = new HashMap<>();
			if (activities != null)
			{
				for (Activity a : activities)
				{
					index.computeIfAbsent(pageKey(a.page), k -> new ArrayList<>()).add(a);
				}
			}
			byName = index;
		}
		for (String candidate : variants(name.trim().toLowerCase(Locale.ROOT)))
		{
			List<Activity> hit = index.get(candidate);
			if (hit != null)
			{
				return hit;
			}
		}
		return List.of();
	}

	/** "Zulrah/Strategies" → "zulrah"; "Slayer task/Dust devils" → "dust devils". */
	static String pageKey(String page)
	{
		String key = page == null ? "" : page;
		if (key.endsWith("/Strategies"))
		{
			key = key.substring(0, key.length() - "/Strategies".length());
		}
		if (key.startsWith("Slayer task/"))
		{
			key = key.substring("Slayer task/".length());
		}
		return key.trim().toLowerCase(Locale.ROOT);
	}

	/** Singular/plural variants both ways — the corpus is singular, slayer
	 *  assignments are category plurals (the selectExternal lesson). */
	static List<String> variants(String base)
	{
		List<String> v = new ArrayList<>();
		v.add(base);
		if (base.endsWith("ies"))
		{
			v.add(base.substring(0, base.length() - 3) + "y");
		}
		if (base.endsWith("es"))
		{
			v.add(base.substring(0, base.length() - 2));
		}
		if (base.endsWith("s"))
		{
			v.add(base.substring(0, base.length() - 1));
		}
		if (base.endsWith("y"))
		{
			v.add(base.substring(0, base.length() - 1) + "ies");
		}
		v.add(base + "s");
		v.add(base + "es");
		return v;
	}
}
