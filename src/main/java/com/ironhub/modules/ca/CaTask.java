package com.ironhub.modules.ca;

/**
 * One Combat Achievement task, read live from the game cache (enums +
 * structs) with completion decoded from the CA_TASK_COMPLETED varp
 * bitfield. Community completion rate joins in from the bundled wiki
 * snapshot (data/ca-completion.json).
 */
class CaTask
{
	final int id;
	final String name;
	final String description;
	final CaTier tier;
	final String type; // Stamina, Perfection, Kill Count, Mechanical, Restriction, Speed
	final String boss; // "" when the task has no boss
	boolean completed;
	Double communityPct; // null = unknown

	/** Lowercased search haystack, built once — matches() lowercased three
	 *  fields per task per keystroke, ~2,500 throwaway strings per filter
	 *  pass (2026-07-20 audit). */
	private final String haystack;

	CaTask(int id, String name, String description, CaTier tier, String type, String boss,
		boolean completed)
	{
		this.id = id;
		this.name = name;
		this.description = description;
		this.tier = tier;
		this.type = type;
		this.boss = boss;
		this.completed = completed;
		this.haystack = (name + "\n" + description + "\n" + boss).toLowerCase();
	}

	String wikiUrl()
	{
		return "https://oldschool.runescape.wiki/w/" + name.replace(" ", "_").replace("'", "%27");
	}

	boolean matches(String search)
	{
		return search == null || search.isEmpty()
			|| haystack.contains(search.toLowerCase());
	}
}
