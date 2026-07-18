package com.ironhub.modules.clues;

import com.google.gson.Gson;
import com.ironhub.data.ClueStepsPack;
import com.ironhub.data.DataPack;
import com.ironhub.requirements.Requirements;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of the clue-steps pack: every requirement parses non-manual,
 * every STASH link resolves both ways, object ids are unique and real,
 * and the core-parity anchors hold.
 */
public class ClueStepsPackTest
{
	private static ClueStepsPack load()
	{
		return new DataPack(new Gson()).load("clue-steps", ClueStepsPack.class);
	}

	@Test
	public void everyRequirementParsesNonManual()
	{
		ClueStepsPack pack = load();
		for (ClueStepsPack.Clue clue : pack.clues)
		{
			for (String req : clue.reqs)
			{
				assertFalse(clue.id + " req is manual: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
		}
	}

	@Test
	public void stashLinksResolveBothWays()
	{
		ClueStepsPack pack = load();
		Set<String> unitKeys = new HashSet<>();
		Set<Integer> objectIds = new HashSet<>();
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			assertTrue("duplicate unit key " + unit.key, unitKeys.add(unit.key));
			assertTrue("duplicate object id " + unit.objectId, objectIds.add(unit.objectId));
			if (unit.clueId != null)
			{
				ClueStepsPack.Clue clue = pack.clue(unit.clueId);
				assertNotNull(unit.key + " links a missing clue " + unit.clueId, clue);
				assertEquals(unit.key, clue.stash);
				assertEquals(unit.key + " tier disagrees with its clue", unit.tier, clue.tier);
			}
		}
		for (ClueStepsPack.Clue clue : pack.clues)
		{
			if (clue.stash != null)
			{
				assertTrue(clue.id + " references unknown unit " + clue.stash,
					unitKeys.contains(clue.stash));
			}
		}
	}

	@Test
	public void clueIdsAreUniqueAndCountsHold()
	{
		ClueStepsPack pack = load();
		Set<String> ids = new HashSet<>();
		int withItems = 0;
		for (ClueStepsPack.Clue clue : pack.clues)
		{
			assertTrue("duplicate clue id " + clue.id, ids.add(clue.id));
			if (!clue.reqs.isEmpty())
			{
				withItems++;
			}
		}
		assertTrue("clue floor", pack.clues.size() >= 110);
		assertTrue("stash floor", pack.stash.size() >= 110);
		assertTrue("most clues gate on items: " + withItems, withItems >= 100);
	}

	@Test
	public void coreParityAnchorsHold()
	{
		ClueStepsPack pack = load();
		// the Kharazi hard step: any stole AND any heraldic rune shield
		ClueStepsPack.Clue kharazi = null;
		for (ClueStepsPack.Clue clue : pack.clues)
		{
			if (clue.text.contains("Kharazi Jungle") && clue.text.contains("stole"))
			{
				kharazi = clue;
			}
		}
		assertNotNull(kharazi);
		assertEquals(2, kharazi.reqs.size());
		assertTrue(kharazi.reqs.get(0).startsWith("any:item:"));
		assertEquals("NORTHEAST_CORNER_OF_THE_KHARAZI_JUNGLE", kharazi.stash);
		// the two Warriors' Guild banks disambiguated by tier (object-id proof)
		ClueStepsPack.Stash elite = null;
		ClueStepsPack.Stash master = null;
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			if (unit.key.equals("WARRIORS_GUILD_BANK_ELITE"))
			{
				elite = unit;
			}
			if (unit.key.equals("WARRIORS_GUILD_BANK_MASTER"))
			{
				master = unit;
			}
		}
		assertNotNull(elite);
		assertNotNull(master);
		assertEquals("Elite", elite.tier);
		assertEquals("Master", master.tier);
	}
}
