package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/clue-items.json}: emote clue steps and the
 * items they require (starter set; expands like the gear ladders).
 */
@Data
public class ClueItemsPack
{
	private int version;
	private List<Clue> clues;

	@Data
	public static class Clue
	{
		private String tier;
		private String clue;
		private List<String> requirements; // Requirements.parse() form
	}
}
