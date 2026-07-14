package com.ironhub.state;

import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountStateTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void statsAndQuestsReadBack()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertEquals(1, state.getRealLevel(Skill.AGILITY)); // default

		StateFixture.stat(state, Skill.AGILITY, 72, 1_000_000);
		assertEquals(72, state.getRealLevel(Skill.AGILITY));
		assertEquals(1_000_000, state.getXp(Skill.AGILITY));

		assertEquals(QuestState.NOT_STARTED, state.getQuestState(Quest.DRAGON_SLAYER_II));
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.FINISHED);
		assertEquals(QuestState.FINISHED, state.getQuestState(Quest.DRAGON_SLAYER_II));
	}

	@Test
	public void ownedCountSumsContainers()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(995, 10_000, 4151, 1));
		StateFixture.inventory(state, Map.of(995, 500));
		StateFixture.equipment(state, Map.of(4151, 1));

		assertEquals(10_500, state.ownedCount(995));
		assertEquals(2, state.ownedCount(4151));
		assertEquals(0, state.ownedCount(11832));
		assertTrue(state.getBankTimestamp() > 0);
	}

	@Test
	public void stateSurvivesRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 42L);
		StateFixture.bank(before, Map.of(4151, 1, 995, 10_000));
		StateFixture.itemNames(before, Map.of(4151, "Abyssal whip"));
		before.setUnlocked("fairy_rings", true);
		before.setKillCount("Zulrah", 12);
		before.persist();
		long bankedAt = before.getBankTimestamp();

		// fresh service over the same storage = client restart
		AccountState after = StateFixture.state(temp.getRoot());
		assertEquals(0, after.ownedCount(4151));
		StateFixture.profile(after, 42L);

		assertEquals(1, after.ownedCount(4151));
		assertEquals(10_000, after.ownedCount(995));
		assertEquals("Abyssal whip", after.itemName(4151));
		assertTrue(after.isUnlocked("fairy_rings"));
		assertEquals(12, after.getKillCount("Zulrah"));
		assertEquals(bankedAt, after.getBankTimestamp());
	}

	@Test
	public void profilesDoNotCollide()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		state.setUnlocked("fairy_rings", true);
		state.persist();

		StateFixture.profile(state, 2L); // second account on the same client
		assertFalse(state.isUnlocked("fairy_rings"));

		StateFixture.profile(state, 1L);
		assertTrue(state.isUnlocked("fairy_rings"));
	}
}
