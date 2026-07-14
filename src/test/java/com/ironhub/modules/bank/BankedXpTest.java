package com.ironhub.modules.bank;

import com.google.gson.Gson;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankedXpTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final BankedXpPack pack = new DataPack(new Gson()).load("banked-xp", BankedXpPack.class);

	@Test
	public void bestMethodPerItemCountsOnce()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// 100 dragon bones: gilded altar (252) must win over bury (72), not sum
		StateFixture.bank(state, Map.of(536, 100));

		Map<Skill, BankedXp.Result> totals = BankedXp.compute(state, pack);
		assertEquals(25_200, totals.get(Skill.PRAYER).xp, 0.01);
		assertTrue(totals.get(Skill.PRAYER).methods.contains("gilded altar"));
		assertFalse(totals.containsKey(Skill.COOKING)); // zero-XP skills omitted
	}

	@Test
	public void sortedLargestFirstAndSharedItemsCountPerSkill()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// yew logs feed both Firemaking (202.5) and Fletching (75)
		StateFixture.bank(state, Map.of(1515, 10, 207, 1000));

		Map<Skill, BankedXp.Result> totals = BankedXp.compute(state, pack);
		assertEquals(2025, totals.get(Skill.FIREMAKING).xp, 0.01);
		assertEquals(750, totals.get(Skill.FLETCHING).xp, 0.01);
		assertEquals(7500, totals.get(Skill.HERBLORE).xp, 0.01);
		// largest first
		assertEquals(Skill.HERBLORE, totals.keySet().iterator().next());
	}
}
