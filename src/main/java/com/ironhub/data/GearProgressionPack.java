package com.ironhub.data;

import java.util.List;
import java.util.Locale;
import lombok.Data;

/**
 * Typed model of {@code data/gear-progression.json}: the Ladlor-style
 * progression chart — phases of ordered groups whose items carry real
 * item ids (icon + ownership detection), categories for filtering and
 * requirement-graph strings.
 */
@Data
public class GearProgressionPack
{
	private int version;
	private List<Phase> phases;

	@Data
	public static class Phase
	{
		private int phase;
		private String name;
		private List<Group> groups;
	}

	@Data
	public static class Group
	{
		private String label;
		private List<Item> items;
	}

	@Data
	public static class Item
	{
		private String name;
		/** Icon + ownership detection (any ItemVariationMapping variant counts). */
		private Integer itemId;
		/** Icon only — POH furniture etc.; obtained via a manual mark. */
		private Integer iconItemId;
		private List<String> categories;
		private List<String> requirements; // Requirements.parse() form
		private String wiki; // wiki page override; defaults to name

		/** Icon item id regardless of detection mode. */
		public int icon()
		{
			return itemId != null ? itemId : iconItemId;
		}

		/** Ownership is manual (right-click mark) rather than item-detected. */
		public boolean isManual()
		{
			return itemId == null;
		}

		/** Stable key used for goal ids and manual-mark unlock flags. */
		public String slug()
		{
			return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
				.replaceAll("^_|_$", "");
		}

		/** Goal-planner id when this item is targeted. */
		public String goalId()
		{
			return "gear:" + slug();
		}

		/** Unlock flag holding the manual obtained mark (no colon — the key round-trips through {@code unlock:} requirement strings, which split on colons). */
		public String markKey()
		{
			return "gearmark_" + slug();
		}

		public String wikiPage()
		{
			return wiki != null ? wiki : name.replace(' ', '_');
		}
	}
}
