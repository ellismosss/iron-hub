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
	public void secondariesOutputsAndModifiers()
	{
		// Secondaries anchor (upstream: PRAYER_POTION consumes Secondaries.PRAYER_POTION
		// = 1x snape grass (231), outputs Prayer potion(3) (139))
		BankedXpPack.Entry prayerPotion = pack.getEntries().stream()
			.filter(e -> e.getActivity().equals("PRAYER_POTION"))
			.findFirst().orElseThrow(IllegalStateException::new);
		assertEquals(1, prayerPotion.getSecondaries().size());
		assertEquals(231, prayerPotion.getSecondaries().get(0).getItemId());
		assertEquals(1.0, prayerPotion.getSecondaries().get(0).getQty(), 0.001);
		assertEquals(139, prayerPotion.getOutputId());
		assertEquals(1.0, prayerPotion.getOutputQty(), 0.001);

		// Modifier anchor (upstream: "Lit Gilded Altar (350% xp)", 3.5f, BONES)
		BankedXpPack.Modifier gilded = pack.getModifiers().stream()
			.filter(m -> m.getName().equals("Lit Gilded Altar (350% xp)"))
			.findFirst().orElseThrow(IllegalStateException::new);
		assertEquals("Prayer", gilded.getSkill());
		assertEquals("multiplier", gilded.getType());
		assertEquals(3.5, gilded.getValue(), 0.001);
		assertTrue(gilded.getAppliesTo().contains("DRAGON_BONES"));

		// Floors
		assertTrue(pack.getModifiers().size() >= 8);
		assertTrue(pack.getEntries().stream().filter(e -> e.getSecondaries() != null).count() >= 100);
		// Every appliesTo/ignores name joins onto a real entry activity
		java.util.Set<String> activities = pack.getEntries().stream()
			.map(BankedXpPack.Entry::getActivity)
			.collect(java.util.stream.Collectors.toSet());
		for (BankedXpPack.Modifier m : pack.getModifiers())
		{
			if (m.getAppliesTo() != null)
			{
				assertTrue(m.getName(), activities.containsAll(m.getAppliesTo()));
			}
		}
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
