package com.ironhub.modules.slayer;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.SlayerTasksPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of the slayer suite pack: every requirement string parses
 * non-manual (an unparseable string is a never-met gate), every master row
 * joins a catalog task, every unlock's varbit constant resolves against
 * the pinned client, and the core-parity anchors hold.
 */
public class SlayerTasksPackTest
{
	private static SlayerTasksPack load()
	{
		return new DataPack(new Gson()).load("slayer-tasks", SlayerTasksPack.class);
	}

	private static void assertParses(String context, java.util.List<String> reqs)
	{
		if (reqs == null)
		{
			return;
		}
		for (String req : reqs)
		{
			Requirement parsed = Requirements.parse(req);
			assertFalse(context + " req is manual: " + req, Requirements.isManual(parsed));
		}
	}

	@Test
	public void everyRequirementParsesNonManual()
	{
		SlayerTasksPack pack = load();
		for (SlayerTasksPack.Task task : pack.tasks)
		{
			assertParses(task.name, task.reqs);
			if (task.locations != null)
			{
				for (SlayerTasksPack.Location location : task.locations)
				{
					assertParses(task.name + "/" + location.name, location.reqs);
				}
			}
		}
		for (SlayerTasksPack.Master master : pack.masters)
		{
			assertParses(master.name, master.reqs);
			for (SlayerTasksPack.Assignment row : master.tasks)
			{
				assertParses(master.name + "/" + row.task, row.reqs);
			}
		}
	}

	@Test
	public void everyMasterRowJoinsACatalogTask()
	{
		SlayerTasksPack pack = load();
		for (SlayerTasksPack.Master master : pack.masters)
		{
			assertTrue(master.name + " suspiciously few rows", master.tasks.size() >= 20);
			for (SlayerTasksPack.Assignment row : master.tasks)
			{
				assertNotNull(master.name + " row has no catalog task: " + row.task,
					pack.task(row.task));
			}
		}
	}

	@Test
	public void mastersAreTheNineWithUniqueFocusIds()
	{
		SlayerTasksPack pack = load();
		assertEquals(9, pack.masters.size());
		Set<Integer> focus = new HashSet<>();
		for (SlayerTasksPack.Master master : pack.masters)
		{
			assertTrue("duplicate focus id " + master.focusId, focus.add(master.focusId));
		}
		assertTrue(pack.masterByFocus(7).wilderness); // Krystilia (core's constant)
		assertEquals("Turael", pack.masterByFocus(1).name);
		assertEquals("Turael", pack.masterByName("Aya").name); // dialog alias
	}

	@Test
	public void everyUnlockVarbitResolvesOnThePinnedClient() throws Exception
	{
		SlayerTasksPack pack = load();
		Set<String> keys = new HashSet<>();
		Set<Integer> varbits = new HashSet<>();
		for (SlayerTasksPack.Unlock unlock : pack.unlocks)
		{
			assertTrue("duplicate unlock key " + unlock.key, keys.add(unlock.key));
			assertFalse("unlock key contains colon: " + unlock.key, unlock.key.contains(":"));
			int id = VarbitID.class.getField(unlock.varbit).getInt(null);
			assertTrue("duplicate varbit " + unlock.varbit, varbits.add(id));
			// the graph flag round-trips: unlock:<key> must parse as an unlock leaf
			assertFalse(Requirements.isManual(Requirements.parse("unlock:" + unlock.key)));
		}
		assertTrue("unlock floor", pack.unlocks.size() >= 30);
	}

	@Test
	public void turaelRegistryIsComplete()
	{
		SlayerTasksPack pack = load();
		int count = 0;
		for (SlayerTasksPack.Task task : pack.tasks)
		{
			if (task.turael == null)
			{
				continue;
			}
			count++;
			assertNotNull(task.name + " turael point", task.turael.worldPoint());
			assertFalse(task.name + " turael locations", task.turael.locations.isEmpty());
		}
		assertEquals(24, count);
	}

	@Test
	public void coreParityAnchorsHold()
	{
		SlayerTasksPack pack = load();
		// core Task.java: GARGOYLES(..., 9, ItemID.SLAYER_ROCK_HAMMER, ...)
		SlayerTasksPack.Task gargoyles = pack.task("Gargoyles");
		assertNotNull(gargoyles.finisher);
		assertEquals(9, gargoyles.finisher.threshold);
		// alternate targets ride along for highlight matching
		assertTrue(pack.task("Abyssal demons").targets.contains("Abyssal Sire"));
		// slayer level folded into the task gate
		assertEquals(java.util.List.of("skill:Slayer:65"), pack.task("Dust devils").reqs);
		// wiki assignment row survives the join
		SlayerTasksPack.Assignment duradel =
			pack.masterByName("Duradel").assignment("abyssal demons");
		assertNotNull(duradel);
		assertEquals(12, duradel.weight);
	}

	@Test
	public void locationCoordinatesArePlausible()
	{
		SlayerTasksPack pack = load();
		int withCoords = 0;
		for (SlayerTasksPack.Task task : pack.tasks)
		{
			if (task.locations == null)
			{
				continue;
			}
			for (SlayerTasksPack.Location location : task.locations)
			{
				if (location.worldPoint() == null)
				{
					continue;
				}
				withCoords++;
				assertTrue(task.name + "/" + location.name + " x out of world",
					location.x >= 1000 && location.x <= 4200);
				assertTrue(task.name + "/" + location.name + " y out of world",
					location.y >= 2000 && location.y <= 13000);
			}
		}
		assertTrue("suspiciously few routable locations: " + withCoords, withCoords >= 300);
	}
}
