package com.ironhub.data;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of data/storage-locations.json (regenerate with
 * tools/gen_storage_locations.py — never hand-edit). Schema shape is covered
 * by DataPackTest; this is the semantic half, incl. the POH costume-room
 * allow-lists that disambiguate which storage a costume item lives in.
 */
public class StorageLocationsPackTest
{
	private final StorageLocationsPack pack =
		new DataPack(new Gson()).load("storage-locations", StorageLocationsPack.class);

	@Test
	public void familyLabelsCoverEveryFamilyPresent()
	{
		assertNotNull(pack.familyLabels);
		assertEquals("PoH", pack.familyLabels.get("playerownedhouse"));
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			assertTrue("no label for family " + s.family,
				pack.familyLabels.containsKey(s.family));
		}
	}

	@Test
	public void storageKeysAreUnique()
	{
		Set<String> seen = new HashSet<>();
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			assertTrue("duplicate storage key: " + s.key, seen.add(s.key));
		}
	}

	/** The POH costume room, byte-faithful to the DWMS source: Fancy dress box
	 *  is the acceptance-test storage and must carry its allow-list. */
	@Test
	public void pohCostumeRoomHasAllowLists()
	{
		StorageLocationsPack.Storage fancy = byKey("fancyDressBox");
		assertNotNull("fancyDressBox missing", fancy);
		assertEquals("playerownedhouse", fancy.family);
		assertEquals("POH_COSTUMES", fancy.container);
		assertNotNull("fancyDressBox lost its allow-list", fancy.items);
		assertFalse(fancy.items.isEmpty());

		// every POH_COSTUMES storage except the null-list catch-all attributes
		// its items by allow-list, so each must carry one with positive ids
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			if ("POH_COSTUMES".equals(s.container) && !"uncategorised".equals(s.key))
			{
				assertNotNull(s.key + " missing allow-list", s.items);
				assertFalse(s.key + " empty allow-list", s.items.isEmpty());
				for (int id : s.items)
				{
					assertTrue(s.key + " has non-positive item id", id > 0);
				}
			}
		}
	}

	private StorageLocationsPack.Storage byKey(String key)
	{
		return pack.storages.stream().filter(s -> s.key.equals(key)).findFirst().orElse(null);
	}
}
