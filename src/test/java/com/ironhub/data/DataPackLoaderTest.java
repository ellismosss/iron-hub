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
		assertEquals(1, pack.getVersion());
		assertFalse(pack.getDailies().isEmpty());

		for (DailiesPack.Daily daily : pack.getDailies())
		{
			assertFalse("blank id", daily.getId().trim().isEmpty());
			assertFalse("blank name", daily.getName().trim().isEmpty());
			assertNotNull(daily.getLocation());

			for (String raw : daily.getRequirements())
			{
				Requirement requirement = Requirements.parse(raw);
				assertNotNull(requirement);
				// typed prefixes must resolve — a manual fallback here means a
				// typo in the pack (misspelled quest/skill name)
				if (raw.startsWith("quest:") || raw.startsWith("skill:"))
				{
					assertFalse("unresolvable requirement: " + raw, Requirements.isManual(requirement));
				}
			}
		}
	}

	@Test(expected = IllegalStateException.class)
	public void missingPackFailsFast()
	{
		dataPack.load("no-such-pack", DailiesPack.class);
	}
}
