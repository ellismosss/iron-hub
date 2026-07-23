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
	public void topicListenersSkipTaggedNoiseButHearBroadcasts()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		int[] bankOnly = new int[1];
		int[] everything = new int[1];
		state.addListener(() -> bankOnly[0]++, AccountState.Topic.BANK);
		state.addListener(() -> everything[0]++);
		bankOnly[0] = 0;
		everything[0] = 0;

		// a BANK-tagged change reaches both
		state.ingestBank(java.util.Map.of(995, 1000));
		org.junit.Assert.assertEquals(1, bankOnly[0]);
		org.junit.Assert.assertEquals(1, everything[0]);

		// a SKILLS-tagged change skips the bank-scoped listener
		StateFixture.stat(state, net.runelite.api.Skill.ATTACK, 50, 101_333);
		org.junit.Assert.assertEquals(1, bankOnly[0]);
		org.junit.Assert.assertEquals(2, everything[0]);

		// an untagged change is a broadcast — scoped listeners always hear it
		state.setSlayerTask("Dust devils");
		org.junit.Assert.assertEquals(2, bankOnly[0]);
		org.junit.Assert.assertEquals(3, everything[0]);
	}

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
		StateFixture.playerName(before, "Iron Luke");
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
		assertEquals("Iron Luke", after.playerName());
		assertTrue(after.isUnlocked("fairy_rings"));
		assertEquals(12, after.getKillCount("Zulrah"));
		assertEquals(bankedAt, after.getBankTimestamp());
	}

	/** Goal-level priority, pins (ordered) and per-route task order (G5)
	 *  persist profile-scoped and reach the router's constraints. */
	@Test
	public void goalPriorityPinsAndOrderSurviveRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 7L);
		before.setGoalPriority("bowfa", "high");
		before.setGoalPriority("ca:340", "medium");
		before.setGoalPinned("quest_cape", true); // one active pin at a time
		before.setRouteTaskOrder("bowfa", java.util.List.of("train:Agility:70", "quest:Song of the Elves"));

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 7L);
		assertEquals("high", after.getGoalPriority("bowfa"));
		assertEquals("medium", after.getGoalPriority("ca:340"));
		assertEquals("normal", after.getGoalPriority("unset")); // default, never stored
		assertEquals(java.util.List.of("quest_cape"), after.getPinnedGoals());
		assertTrue(after.isGoalPinned("quest_cape"));
		assertEquals(java.util.List.of("train:Agility:70", "quest:Song of the Elves"),
			after.getRouteTaskOrder("bowfa"));

		// the constraints the router reads carry all three
		com.ironhub.engine.PlanConstraints c = after.plannerConstraints();
		assertEquals("high", c.goalPriority.get("bowfa"));
		assertEquals(java.util.List.of("quest_cape"), c.pinnedGoals);
		assertEquals(2, c.routeTaskOrder.get("bowfa").size());

		// exactly one active pin: pinning a goal replaces the prior goal pin…
		after.setGoalPinned("bowfa", true);
		assertEquals(java.util.List.of("bowfa"), after.getPinnedGoals());
		// …and pinning a TASK clears the goal pin (goal OR task, never both)
		after.setTaskPinned("train:Agility:70", true);
		assertTrue(after.isTaskPinned("train:Agility:70"));
		assertTrue(after.getPinnedGoals().isEmpty());

		// unpin + reset to normal clears cleanly
		after.setTaskPinned("train:Agility:70", false);
		after.setGoalPriority("bowfa", "normal");
		assertFalse(after.isTaskPinned("train:Agility:70"));
		assertEquals("normal", after.getGoalPriority("bowfa"));
	}

	/** Pin/snooze reconcile (Luke, 2026-07-24): clearSnoozes lifts a snooze,
	 *  which is what lets re-pinning a goal bring its snoozed step back. */
	@Test
	public void clearSnoozesLiftsSnoozeSoRePinningTakesEffect()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 3L);
		state.togglePlannerSnooze("obtain:item22994"); // "not right now" on a task
		assertTrue(state.isPlannerSnoozed("obtain:item22994"));
		// re-pinning the goal clears the snooze on its steps
		state.clearSnoozes(java.util.Set.of("obtain:item22994", "unrelated"));
		assertFalse(state.isPlannerSnoozed("obtain:item22994"));
		// idempotent: clearing nothing does not error
		state.clearSnoozes(java.util.Set.of("obtain:item22994"));
		assertFalse(state.isPlannerSnoozed("obtain:item22994"));
	}

	/** G8: the ACCOUNT_TYPE varbit maps to the VERIFIED enum ordinal (UIM is 2,
	 *  not 3 — memory is wrong) and persists so UIM honesty holds before the
	 *  first login varbit read. */
	@Test
	public void accountTypePersistsAndMapsToTheVerifiedEnum()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 42L);
		StateFixture.varbit(before, net.runelite.api.Varbits.ACCOUNT_TYPE, 2);
		assertTrue(before.isUltimateIronman());
		assertTrue(before.isIronman());
		assertEquals(net.runelite.api.vars.AccountType.ULTIMATE_IRONMAN, before.accountTypeEnum());

		// reload the profile: UIM survives before any varbit fires
		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 42L);
		assertEquals(2, after.accountType());
		assertTrue(after.isUltimateIronman());

		// value 3 is HARDCORE ironman — NOT ultimate (the memory trap)
		StateFixture.varbit(after, net.runelite.api.Varbits.ACCOUNT_TYPE, 3);
		assertEquals(net.runelite.api.vars.AccountType.HARDCORE_IRONMAN, after.accountTypeEnum());
		assertFalse(after.isUltimateIronman());
		assertTrue(after.isIronman());

		// value 0 is a normal account — not an ironman at all
		StateFixture.varbit(after, net.runelite.api.Varbits.ACCOUNT_TYPE, 0);
		assertFalse(after.isIronman());
		assertFalse(after.isUltimateIronman());
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
