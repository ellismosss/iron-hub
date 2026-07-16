package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.SkillUnlocksPack;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of data/skill-unlocks.json (regenerate with
 * tools/gen_skill_unlocks.py — never hand-edit). Schema shape is covered by
 * DataPackTest; this is the semantic half: the wiki parse must leave clean,
 * renderable sentences behind, anchored on unlocks everyone knows.
 */
public class SkillUnlocksPackTest
{
	private final SkillUnlocksPack pack =
		new DataPack(new Gson()).load("skill-unlocks", SkillUnlocksPack.class);

	@Test
	public void knownUnlocksAnchorTheParse()
	{
		assertTrue("Farming 15 unlocks oak trees",
			pack.unlocks("Farming", 15).stream().anyMatch(u -> u.toLowerCase().contains("oak")));
		assertTrue("Farming 99 has the cape",
			pack.unlocks("Farming", 99).stream().anyMatch(u -> u.contains("cape")));
		assertTrue("Herblore 38 unlocks prayer potions",
			pack.unlocks("Herblore", 38).stream().anyMatch(u -> u.contains("rayer potion")));
	}

	@Test
	public void unknownSkillOrLevelIsEmptyNeverNull()
	{
		assertTrue(pack.unlocks("Farming", 100).isEmpty());
		assertTrue(pack.unlocks("Sailing", 15).isEmpty());
	}

	/** Every line must render in the panel: no leftover wiki markup, plain
	 *  printable ASCII (the bundled fonts drop anything fancier). */
	@Test
	public void everyLineIsCleanRenderableText()
	{
		for (Map.Entry<String, Map<String, List<String>>> skill : pack.skills.entrySet())
		{
			for (Map.Entry<String, List<String>> level : skill.getValue().entrySet())
			{
				for (String line : level.getValue())
				{
					String where = skill.getKey() + " " + level.getKey() + ": " + line;
					assertFalse("markup left in " + where,
						line.contains("{{") || line.contains("[[") || line.contains("|"));
					for (char c : line.toCharArray())
					{
						assertTrue("non-ascii in " + where, c >= 32 && c < 127);
					}
				}
			}
		}
	}
}
