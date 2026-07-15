package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.FarmingPatch;
import com.ironhub.modules.farming.rl.FarmingWorld;
import com.ironhub.modules.farming.rl.PatchImplementation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of the Easy Farming-derived run pack against the vendored
 * farming world: every stop's tile must actually sit in a region with a
 * patch of the stop's category — a wrong coordinate would break both the
 * proximity checklist and the live patch states.
 */
public class FarmRunsPackTest
{
	private static final Map<String, PatchImplementation> CATEGORY_IMPLEMENTATIONS = Map.of(
		"herb", PatchImplementation.HERB,
		"tree", PatchImplementation.TREE,
		"fruit", PatchImplementation.FRUIT_TREE,
		"hops", PatchImplementation.HOPS);

	@Test
	public void everyStopSitsOnARegionWithItsPatch()
	{
		FarmRunsPack pack = new DataPack(new Gson()).load("farm-runs", FarmRunsPack.class);
		FarmingWorld world = new FarmingWorld();

		// region -> implementations present
		Map<Integer, Set<PatchImplementation>> byRegion = new java.util.HashMap<>();
		for (Set<FarmingPatch> patches : world.getTabs().values())
		{
			for (FarmingPatch patch : patches)
			{
				byRegion.computeIfAbsent(patch.getRegion().getRegionID(), r -> new HashSet<>())
					.add(patch.getImplementation());
			}
		}

		Set<String> ids = new HashSet<>();
		for (FarmRunsPack.Location location : pack.locations)
		{
			assertTrue("duplicate id " + location.id, ids.add(location.id));
			PatchImplementation wanted = CATEGORY_IMPLEMENTATIONS.get(location.category);
			assertNotNull(location.id, wanted);
			int region = location.worldPoint().getRegionID();
			Set<PatchImplementation> present = byRegion.getOrDefault(region, Set.of());
			assertTrue(location.id + ": no " + wanted + " patch in region " + region
				+ " (found " + present + ")", present.contains(wanted));
			assertTrue(location.id + " has no teleports", !location.teleports.isEmpty());
		}
		assertTrue(pack.locations.size() >= 29);
	}
}
