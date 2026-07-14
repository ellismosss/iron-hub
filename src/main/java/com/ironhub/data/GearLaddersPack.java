package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/gear-ladders.json} (schema:
 * {@code data/schemas/gear-ladders.schema.json}): per-style, per-slot
 * upgrade ladders in progression order, iron-obtainable only.
 */
@Data
public class GearLaddersPack
{
	private int version;
	private List<Style> styles;

	@Data
	public static class Style
	{
		private String style; // Melee | Range | Mage
		private List<Slot> slots;
	}

	@Data
	public static class Slot
	{
		private String slot;
		private List<Rung> ladder; // progression order
	}

	@Data
	public static class Rung
	{
		private int itemId;
		private String name;
		private List<String> requirements; // Requirements.parse() form
	}
}
