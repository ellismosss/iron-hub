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

	@Data
	public static class Entry
	{
		private String skill;   // Skill enum name, any case
		private String method;  // shown in tooltips
		private int itemId;
		private String name;
		private double xpEach;
		private int level;      // activity level requirement; 0/absent = none
	}
}
