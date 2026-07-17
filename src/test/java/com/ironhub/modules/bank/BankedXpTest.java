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
		// 100 wyrmling bones: Wildy altar (147) must win over Bury (21), not sum
		StateFixture.bank(state, Map.of(28899, 100));

		Map<Skill, BankedXp.Result> totals = BankedXp.compute(state, pack);
		assertEquals(14_700, totals.get(Skill.PRAYER).xp, 0.01);
		assertTrue(totals.get(Skill.PRAYER).methods.contains("Wildy altar"));
		assertFalse(totals.containsKey(Skill.COOKING)); // zero-XP skills omitted
	}

	@Test
	public void portedPackCoverageAndAnchors()
	{
		// The Banked Experience port carries hundreds of (item, activity) rows
		assertTrue("only " + pack.getEntries().size() + " entries",
			pack.getEntries().size() >= 600);

		// Anchors verified against the upstream Activity enum (wiki values):
		// Yew logs -> Firemaking 202.5 xp at level 60
		assertTrue(pack.getEntries().stream().anyMatch(e ->
			e.getSkill().equals("Firemaking") && e.getItemId() == 1515
				&& e.getXpEach() == 202.5 && e.getLevel() == 60));
		// Dragon bones -> Prayer 72 xp, no level gate
		assertTrue(pack.getEntries().stream().anyMatch(e ->
			e.getSkill().equals("Prayer") && e.getItemId() == 536
				&& e.getXpEach() == 72.0 && e.getLevel() == 0));
		// Superior dragon bones -> Prayer 150 xp at level 70
		assertTrue(pack.getEntries().stream().anyMatch(e ->
			e.getSkill().equals("Prayer") && e.getItemId() == 22124
				&& e.getXpEach() == 150.0 && e.getLevel() == 70));
	}

	@Test
	public void sortedLargestFirstAndSharedItemsCountPerSkill()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// yew logs feed Firemaking (202.5), Fletching (75) and Hunter (1020/bird house)
		StateFixture.bank(state, Map.of(1515, 10, 207, 1000));

		Map<Skill, BankedXp.Result> totals = BankedXp.compute(state, pack);
		assertEquals(2025, totals.get(Skill.FIREMAKING).xp, 0.01);
		assertEquals(750, totals.get(Skill.FLETCHING).xp, 0.01);
		assertEquals(10_200, totals.get(Skill.HUNTER).xp, 0.01);
		assertEquals(7500, totals.get(Skill.HERBLORE).xp, 0.01);
		// largest first
		assertEquals(Skill.HUNTER, totals.keySet().iterator().next());
	}
}
