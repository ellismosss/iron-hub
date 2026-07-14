package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.HerbPatchesPack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HerbPatchesPackTest
{
	@Test
	public void packLoadsWithNineLocatedPatches()
	{
		HerbPatchesPack pack = new DataPack(new Gson()).load("herb-patches", HerbPatchesPack.class);
		assertEquals(9, pack.getPatches().size());
		pack.getPatches().forEach(p ->
		{
			assertNotNull(p.getId());
			assertNotNull(p.getLocation());
		});
	}
}
