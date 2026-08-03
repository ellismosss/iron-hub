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

	/** Where's my stuff (DWMS port): a tracked storage snapshot names where an
	 *  item was last seen, counts as owned, a live container wins over it, and
	 *  it all survives a restart. */
	@Test
	public void suppliesWatchlistAndThresholdsSurviveRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 77L);

		// a default the player removes stays off; a non-default they add stays on
		int prayerPot = 2434, cannonball = 2;
		before.untrackSupply(prayerPot, true);      // remove a default
		before.trackSupply(cannonball, false);      // add a non-default
		before.setSupplyThreshold(cannonball, 200); // red under 200

		assertFalse(before.isSupplyTracked(prayerPot, true));   // default now off
		assertTrue(before.isSupplyTracked(cannonball, false));  // added on
		assertTrue(before.isSupplyTracked(385, true));          // untouched default stays
		assertEquals(200, before.getSupplyThreshold(cannonball));
		assertEquals(0, before.getSupplyThreshold(385));        // no threshold set

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 77L);
		assertFalse(after.isSupplyTracked(prayerPot, true));
		assertTrue(after.isSupplyTracked(cannonball, false));
		assertEquals(200, after.getSupplyThreshold(cannonball));

		// re-adding a removed default clears the removal; clearing a threshold
		after.trackSupply(prayerPot, true);
		assertTrue(after.isSupplyTracked(prayerPot, true));
		after.setSupplyThreshold(cannonball, 0);
		assertEquals(0, after.getSupplyThreshold(cannonball));
	}

	@Test
	public void storageContentsTrackAndSurviveRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 99L);
		before.putStorageContents("fancyDressBox", "Fancy dress box",
			"playerownedhouse", "Fancy dress box (PoH)", Map.of(23330, 1),
			Map.of(23330, "Rune scimitar (Guthix)"), 1_000L);

		assertTrue(before.ownedAnywhere(23330));
		assertEquals("Fancy dress box (PoH)", before.whereOwned(23330));
		assertEquals(0, before.ownedCount(23330));          // not in bank/inv/worn
		org.junit.Assert.assertNull(before.whereOwned(99_999)); // untracked item

		// a live container copy wins over the stored snapshot
		StateFixture.bank(before, Map.of(23330, 1));
		assertEquals("Bank", before.whereOwned(23330));
		assertEquals("Fancy dress box (PoH)", before.storedLabel(23330)); // still tracked

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 99L);
		assertEquals("Fancy dress box (PoH)", after.storedLabel(23330));
		assertTrue(after.ownedAnywhere(23330));
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

	@Test
	public void rateFoldNeverReentersPersistDuringTheLogoutFlush()
	{
		// the logout flush runs persistNow with gameState already off
		// LOGGED_IN; a persist() inside the fold core re-entered persistNow
		// there and recursed until stack overflow, losing the flush
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		org.mockito.Mockito.when(client.getGameState())
			.thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
		int[] saves = {0};
		AccountState state = new AccountState(client, null,
			new ProfileStore(new com.google.gson.Gson(), r -> { saves[0]++; r.run(); },
				temp.getRoot()), null);
		StateFixture.profile(state, 42L);
		saves[0] = 0;

		state.foldRateSample(net.runelite.api.Skill.FISHING, 50_000, 0.5);
		assertEquals(0, saves[0]); // the fold core itself must not write

		state.persistNow(); // the flush snapshots the folded rates exactly once
		assertEquals(1, saves[0]);
	}

	@Test
	public void midSessionEnableActivatesTheProfileBeforePersisting()
	{
		// plugin toggled on while already logged in: no GameStateChanged
		// fires, so the first tick must activate the profile — without it
		// every persist() no-ops and the next login replay restores the
		// on-disk state over the user's edits
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		org.mockito.Mockito.when(client.getGameState())
			.thenReturn(net.runelite.api.GameState.LOGGED_IN);
		org.mockito.Mockito.when(client.getAccountHash()).thenReturn(77L);
		AccountState state = new AccountState(client, null,
			StateFixture.store(temp.getRoot()), null);

		state.onGameTick(); // first tick after the mid-session enable
		state.setUnlocked("mid_session_edit", true);
		state.persistNow();

		AccountState reloaded = StateFixture.state(temp.getRoot());
		StateFixture.profile(reloaded, 77L);
		assertTrue(reloaded.isUnlocked("mid_session_edit"));
	}

	@Test
	public void captureSetupSplitsUnstackableQuantitiesAcrossSlots()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		// three sharks in three slots (unstackable), one stack of 100 runes
		StateFixture.inventorySlots(state, new int[]{385, 385, 385, 554, -1});
		StateFixture.inventory(state, Map.of(385, 3, 554, 100));

		PersistedState.SavedSetup setup = state.captureSetup();
		assertEquals(1, setup.inventoryQty[0]);
		assertEquals(1, setup.inventoryQty[1]);
		assertEquals(1, setup.inventoryQty[2]);
		assertEquals(100, setup.inventoryQty[3]);
		assertEquals(0, setup.inventoryQty[4]);
		// carrying exactly what was captured means nothing to withdraw —
		// the old per-slot totals (3+3+3 sharks) flagged a phantom shortfall
		assertTrue(state.setupItemsToWithdraw(setup).isEmpty());
	}

	@Test
	public void clogCatalogReadsAreStableSnapshots()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PersistedState.ClogTab bosses = new PersistedState.ClogTab();
		bosses.name = "Bosses";
		state.setClogCatalog(java.util.List.of(bosses));

		java.util.List<PersistedState.ClogTab> view = state.getClogCatalog();
		PersistedState.ClogTab raids = new PersistedState.ClogTab();
		raids.name = "Raids";
		state.setClogCatalog(java.util.List.of(bosses, raids));

		// an earlier read stays what it was — EDT rebuilds iterate it while
		// the client thread publishes a replacement
		assertEquals(1, view.size());
		assertEquals(2, state.getClogCatalog().size());
	}

	@Test
	public void profileSwitchDropsTheConsumptionBaseline()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.inventory(state, Map.of(385, 10)); // ten sharks
		StateFixture.checkpointSupplies(state);

		StateFixture.profile(state, 2L); // account switch
		StateFixture.inventory(state, Map.of(385, 4));
		state.ingestLoot("Zulrah", Map.of(12934, 1));

		// the old account's baseline must not attribute six phantom sharks
		// to the new account's first kill
		assertTrue(state.getConsumptionLog().isEmpty());
		assertTrue(state.suppliesFor("Zulrah").isEmpty());
	}

	/** 2026-08-03: ironmen can't trade, so loot economics price at high
	 *  alch for them, GE for mains, and coins are always face value. */
	@Test
	public void ironmenValueLootAtHighAlch() throws Exception
	{
		net.runelite.client.game.ItemManager itemManager =
			org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);
		net.runelite.api.ItemComposition scimitar =
			org.mockito.Mockito.mock(net.runelite.api.ItemComposition.class);
		org.mockito.Mockito.when(scimitar.getName()).thenReturn("Rune scimitar");
		org.mockito.Mockito.when(scimitar.getHaPrice()).thenReturn(15_360);
		org.mockito.Mockito.when(itemManager.getItemComposition(org.mockito.Mockito.anyInt()))
			.thenReturn(scimitar);
		org.mockito.Mockito.when(itemManager.getItemPrice(1333)).thenReturn(25_000);

		AccountState iron = StateFixture.state(temp.newFolder(), itemManager);
		StateFixture.profile(iron, 1L);
		StateFixture.varbit(iron, net.runelite.api.Varbits.ACCOUNT_TYPE, 1); // ironman
		iron.ingestLoot("Zulrah", Map.of(1333, 2));
		assertEquals(2 * 15_360L, iron.lootValueFor("Zulrah"));
		iron.ingestLoot("Zulrah", Map.of(net.runelite.api.gameval.ItemID.COINS, 5_000));
		assertEquals(2 * 15_360L + 5_000L, iron.lootValueFor("Zulrah"));

		AccountState main = StateFixture.state(temp.newFolder(), itemManager);
		StateFixture.profile(main, 2L);
		StateFixture.varbit(main, net.runelite.api.Varbits.ACCOUNT_TYPE, 0); // main
		main.ingestLoot("Zulrah", Map.of(1333, 2));
		assertEquals(2 * 25_000L, main.lootValueFor("Zulrah"));
	}

	@Test
	public void legacyGoalMigrationPersistsTheFullyRestoredProfile()
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		PersistedState legacy = new PersistedState();
		PersistedState.CustomGoal goal = new PersistedState.CustomGoal();
		goal.name = "60 Attack";
		goal.req = "skill:Attack:60";
		legacy.customGoals.put("custom:skill:Attack:60", goal);
		PersistedState.GoalRecord record = new PersistedState.GoalRecord();
		record.goalId = "diary:x";
		record.name = "done";
		legacy.goalRecords.add(record);
		legacy.measuredRates.put("Attack", 50_000.0);
		store.save(9L, legacy);

		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L); // triggers the one-time migration

		// the migration's persist rewrote the profile — it must contain the
		// FULLY restored state, not just the fields restored before it ran
		PersistedState written = store.load(9L);
		assertTrue(written.goalSeeds.containsKey("custom:skill:Attack:60"));
		assertEquals(1, written.goalRecords.size());
		assertEquals(50_000.0, written.measuredRates.get("Attack"), 0.01);
	}

	/** Merge-accept pins its pair TOGETHER (Luke's 2026-08-03 ruling) —
	 *  per-goal setGoalPinned enforces the single-pin rule and each call
	 *  wiped the previous, leaving only the last goal pinned. */
	@Test
	public void mergeAcceptBulkPinKeepsBothWhileSinglePinsStayExclusive()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);

		state.setGoalPinned("a", true);
		state.setGoalsPinned(java.util.List.of("b", "c"));
		assertEquals(java.util.List.of("b", "c"), state.getPinnedGoals());
		assertFalse(state.isGoalPinned("a"));

		// unpinning one of the pair leaves its partner
		state.setGoalPinned("b", false);
		assertEquals(java.util.List.of("c"), state.getPinnedGoals());

		// an individual pin still clears the field — the one-pin rule holds
		state.setGoalPinned("d", true);
		assertEquals(java.util.List.of("d"), state.getPinnedGoals());

		// and the pair survives a restart
		state.setGoalsPinned(java.util.List.of("b", "c"));
		state.persistNow();
		AccountState reloaded = StateFixture.state(temp.getRoot());
		StateFixture.profile(reloaded, 42L);
		assertEquals(java.util.List.of("b", "c"), reloaded.getPinnedGoals());
	}

	@Test
	public void publishedSnapshotsNeverTearUnderConcurrentReads() throws Exception
	{
		// no profile on purpose: persist() no-ops, keeping the hammer tight.
		// Pre-fix, putSailingBoat/putStorageContents mutated the held
		// snapshot in place while an EDT reader's copy() iterated it.
		AccountState state = StateFixture.state(temp.getRoot());
		java.util.concurrent.atomic.AtomicReference<Throwable> failed =
			new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicBoolean stop =
			new java.util.concurrent.atomic.AtomicBoolean();
		Thread reader = new Thread(() ->
		{
			try
			{
				while (!stop.get())
				{
					state.getSailingBoats().values().forEach(b -> b.partTiers.size());
					state.getStorageContents().values().forEach(s -> s.items.size());
				}
			}
			catch (Throwable t)
			{
				failed.set(t);
			}
		});
		reader.start();
		for (int i = 0; i < 20_000 && failed.get() == null; i++)
		{
			state.putSailingBoat(1, Map.of("p" + (i % 8), i), i); // tiers always rise
			state.putStorageContents("k", "n", "f", "l", Map.of(1, i, i % 8 + 2, i), null, i);
		}
		stop.set(true);
		reader.join(10_000);
		assertTrue(String.valueOf(failed.get()), failed.get() == null);
	}
}
