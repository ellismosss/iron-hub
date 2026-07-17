package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/banked-xp.json} (schema:
 * {@code data/schemas/banked-xp.schema.json}): bankable items mapped to
 * training methods and XP per item.
 */
@Data
public class BankedXpPack
{
	private int version;
	private List<Entry> entries;
	private List<Modifier> modifiers;

	@Data
	public static class Entry
	{
		private String skill;    // Skill enum name, any case
		private String method;   // shown in tooltips
		private String activity; // upstream Activity enum constant; modifiers join on it
		private int itemId;
		private String name;
		private double xpEach;
		private int level;       // activity level requirement; 0/absent = none
		private List<ItemQty> secondaries; // consumed per action; null = none representable
		private int outputId;    // produced item; 0/absent = none
		private double outputQty;
	}

	@Data
	public static class ItemQty
	{
		private int itemId;
		private double qty;
	}

	@Data
	public static class Modifier
	{
		private String skill;
		private String name;
		private String type;  // "multiplier" | "additive"
		private double value; // xp multiplier on a banked item
		private List<String> appliesTo; // Entry.activity names; null with appliesToAll = whole skill
		private boolean appliesToAll;
		private List<String> ignores;   // only with appliesToAll
		private String exclusiveGroup;  // ticking one unticks the group's others (upstream compatibleWith); null = independent
	}
}
