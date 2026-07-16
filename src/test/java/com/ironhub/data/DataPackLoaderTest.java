package com.ironhub.data;

import com.google.gson.Gson;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DataPackLoaderTest
{
	private final DataPack dataPack = new DataPack(new Gson());

	@Test
	public void dailiesPackLoadsAndRequirementsParse()
	{
		DailiesPack pack = dataPack.load("dailies", DailiesPack.class);
		assertFalse(pack.dailies.isEmpty());

		for (DailiesPack.Daily daily : pack.dailies)
		{
			assertFalse("blank id", daily.id.trim().isEmpty());
			assertFalse("blank name", daily.name.trim().isEmpty());
			assertNotNull(daily.point);
			assertNotNull(daily.worldPoint());

			for (String raw : daily.reqs)
			{
				Requirement requirement = Requirements.parse(raw);
				assertNotNull(requirement);
				// a manual fallback here means a typo in the pack (misspelled
				// quest/diary name) — see DailiesPackTest for the full sweep
				assertFalse("unresolvable requirement: " + raw, Requirements.isManual(requirement));
			}
		}
	}

	@Test
	public void goalAchievedProofsResolve()
	{
		GoalsPack pack = dataPack.load("goals", GoalsPack.class);
		for (GoalsPack.Goal goal : pack.getGoals())
		{
			if (goal.getAchieved() == null)
			{
				continue;
			}
			for (String raw : goal.getAchieved())
			{
				// a proof that falls back to manual can never fire — a typo'd id
				// would silently regress achieved-goal detection
				assertFalse("unresolvable achieved proof: " + raw,
					Requirements.isManual(Requirements.parse(raw)));
			}
		}
	}

	@Test
	public void gearProgressionPackResolves()
	{
		GearProgressionPack pack = dataPack.load("gear-progression", GearProgressionPack.class);
		int items = 0;
		for (GearProgressionPack.Phase phase : pack.getPhases())
		{
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				for (GearProgressionPack.Item item : group.getItems())
				{
					items++;
					assertTrue("no icon id: " + item.getName(), item.icon() > 0);
					assertFalse("blank slug: " + item.getName(), item.slug().isEmpty());
					for (String raw : item.getRequirements())
					{
						Requirement requirement = Requirements.parse(raw);
						assertNotNull(requirement);
						// typed prefixes must resolve (a manual fallback = pack typo);
						// free-text (diary) requirements are intentionally manual
						if (raw.startsWith("quest:") || raw.startsWith("skill:")
							|| raw.startsWith("skillb:") || raw.startsWith("any:")
							|| raw.startsWith("itemx:"))
						{
							assertFalse("unresolvable requirement: " + raw + " (" + item.getName() + ")",
								Requirements.isManual(requirement));
						}
					}
				}
			}
		}
		assertTrue("suspiciously small gear pack: " + items, items > 100);
	}

	@Test
	public void boostGatesAndSkillsResolve()
	{
		BoostsPack pack = dataPack.load("boosts", BoostsPack.class);
		for (BoostsPack.Boost boost : pack.getBoosts())
		{
			// an unresolvable gate would silently never grant the boost
			assertFalse("unresolvable gate: " + boost.getGate() + " (" + boost.getName() + ")",
				Requirements.isManual(Requirements.parse(boost.getGate())));
			for (String skill : boost.getSkills())
			{
				net.runelite.api.Skill.valueOf(skill.toUpperCase(java.util.Locale.ROOT)); // throws on typo
			}
		}
	}

	@Test(expected = IllegalStateException.class)
	public void missingPackFailsFast()
	{
		dataPack.load("no-such-pack", DailiesPack.class);
	}
}
