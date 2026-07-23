package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/qol.json} (schema:
 * {@code data/schemas/qol.schema.json}): curated account QoL unlocks with
 * ownership item ids and requirement strings.
 */
@Data
public class QolPack
{
	private int version;
	private List<Unlock> unlocks;

	@Data
	public static class Unlock
	{
		private String id;
		private String name;
		private List<Integer> itemIds;      // owning any counts as unlocked
		private List<String> requirements;  // Requirements.parse() string form
		private String benefit;             // what it DOES (KB effect prose), nullable
	}
}
