package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.ironhub.data.ItemNameIndex;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StrategySolverTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void picksHighestRankedOwnedCandidatePerSlot() throws Exception
	{
		String wikitext;
		try (InputStream in = getClass().getResourceAsStream("/wiki/zulrah-strategies.wikitext"))
		{
			wikitext = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		List<WikiStrategy> strategies = WikiStrategy.parse(wikitext, new ItemNameIndex(new Gson()));
		WikiStrategy magic = strategies.get(0);

		AccountState state = StateFixture.state(temp.getRoot());
		// owns: occult (neck rank 1) + fury (neck rank 2) + ahrim's top (body rank 3)
		StateFixture.bank(state, Map.of(12002, 1, 6585, 1, 4712, 1));

		Map<EquipmentInventorySlot, Integer> best = StrategySolver.solve(state, magic, null);
		// rank order wins: occult over fury
		assertEquals((Integer) 12002, best.get(EquipmentInventorySlot.AMULET));
		assertEquals((Integer) 4712, best.get(EquipmentInventorySlot.BODY));
		// nothing owned for the weapon slot
		assertFalse(best.containsKey(EquipmentInventorySlot.WEAPON));
	}

	@Test
	public void ownedVariantResolvesForExport() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.equipment(state, Map.of(13580, 1)); // Arceuus graceful hood variant
		assertEquals(13580, state.ownedVariantOf(11850)); // base id resolves to the owned variant
		assertEquals(-1, state.ownedVariantOf(11832)); // bandos chestplate: unowned
	}
}
