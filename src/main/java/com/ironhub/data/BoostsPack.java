package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/boosts.json}: temporary skill boosts — how
 * much each source boosts which skills, and the requirement-graph gate
 * for having the boost available (owning the pie/saw, quest unlocks).
 * Visible boosts don't stack with each other; invisible ones (crystal
 * saw) stack on top of one visible boost.
 */
@Data
public class BoostsPack
{
	private int version;
	private List<Boost> boosts;

	@Data
	public static class Boost
	{
		private String name;
		private List<String> skills; // Skill enum names
		private int boost;
		private boolean invisible;
		private String gate; // Requirements.parse() form
	}
}
