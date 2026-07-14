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
	}

	String wikiUrl()
	{
		return "https://oldschool.runescape.wiki/w/" + name.replace(" ", "_").replace("'", "%27");
	}

	boolean matches(String search)
	{
		if (search == null || search.isEmpty())
		{
			return true;
		}
		String term = search.toLowerCase();
		return name.toLowerCase().contains(term)
			|| description.toLowerCase().contains(term)
			|| boss.toLowerCase().contains(term);
	}
}
