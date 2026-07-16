package com.ironhub.modules.dailies;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DailiesModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static final long DAY = 86_400_000L;
	// Thu 2026-01-01 12:00 UTC
	private static final long NOON = 1_767_268_800_000L;

	private DailiesPack pack()
	{
		return new DataPack(new Gson()).load("dailies", DailiesPack.class);
	}

	private DailiesModule module(AccountState state)
	{
		return module(state, null);
	}

	private DailiesModule module(AccountState state, net.runelite.client.Notifier notifier)
	{
		DailiesModule module = new DailiesModule(state, null, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, null, null, notifier, null, null, null, null, null, null, null);
		module.startUp();
		return module;
	}

	private AccountState state()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		return state;
	}

	@Test
	public void resetIsMidnightUtc()
	{
		assertEquals(NOON - 12 * 3_600_000L, DailyTracker.startOfUtcDay(NOON));
	}

	/**
	 * The semantics ported from core's Daily Task Indicator, and the one most
	 * likely to get "fixed" backwards: the claim varbit reads 0 while the
	 * daily is UNCLAIMED.
	 */
	@Test
	public void claimVarbitZeroMeansClaimable()
	{
		AccountState state = state();
		DailiesPack.Daily zaff = pack().daily("zaff_battlestaves");
		assertTrue("Zaff needs no diary per the wiki", zaff.reqs.isEmpty());

		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, zaff, false, NOON));
		StateFixture.varbit(state, zaff.detection.varbit, 1);
		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, zaff, false, NOON));
	}

	/** A reset crossed while logged in leaves the varbit stale — core assumes
	 *  available rather than hiding a daily you can actually claim. */
	@Test
	public void crossedResetBeatsAStaleClaimVarbit()
	{
		AccountState state = state();
		DailiesPack.Daily zaff = pack().daily("zaff_battlestaves");
		StateFixture.varbit(state, zaff.detection.varbit, 1);

		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, zaff, false, NOON));
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, zaff, true, NOON));
	}

	/** Robin counts what you took, and the cap is your Morytania legs tier. */
	@Test
	public void countModeCapsAtTheBestDiaryTier()
	{
		AccountState state = state();
		DailiesPack.Daily robin = pack().daily("robin_bonemeal");
		int claimed = robin.detection.varbit;

		StateFixture.bank(state, Map.of(526, 100)); // stocked: this is about the count varbit
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1);
		assertEquals(13, DailyTracker.quantity(state, robin));
		StateFixture.varbit(state, claimed, 12);
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, robin, false, NOON));
		StateFixture.varbit(state, claimed, 13);
		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, robin, false, NOON));

		// hard legs raise the cap — the same 13 taken is no longer the lot
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_HARD, 1);
		assertEquals(26, DailyTracker.quantity(state, robin));
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, robin, false, NOON));
	}

	@Test
	public void lockedUntilTheDiaryIsDone()
	{
		AccountState state = state();
		DailiesPack.Daily dynamite = pack().daily("thirus_dynamite");
		assertEquals(DailyTracker.State.LOCKED,
			DailyTracker.stateOf(state, dynamite, false, NOON));

		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, dynamite, false, NOON));
		assertEquals(20, DailyTracker.quantity(state, dynamite));
	}

	/** Quantity tracks the best tier met, not the first. */
	@Test
	public void quantityPicksTheBestMetTier()
	{
		AccountState state = state();
		DailiesPack.Daily zaff = pack().daily("zaff_battlestaves");
		assertEquals("base amount needs no diary", 5, DailyTracker.quantity(state, zaff));

		StateFixture.varbit(state, Varbits.DIARY_VARROCK_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_MEDIUM, 1);
		assertEquals(30, DailyTracker.quantity(state, zaff));
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_ELITE, 1);
		assertEquals(120, DailyTracker.quantity(state, zaff));
	}

	/** 120 staves at 7,000 each — the bring line scales off the tier. */
	@Test
	public void bringLineScalesWithTheTier()
	{
		AccountState state = state();
		DailiesModule module = module(state);
		DailiesPack.Daily zaff = module.pack().daily("zaff_battlestaves");

		assertEquals("35,000 coins", module.bringLine(zaff));
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_ELITE, 1);
		assertEquals("840,000 coins", module.bringLine(zaff));
	}

	/**
	 * No varbit tracks the Tears of Guthix cooldown, so before we have watched
	 * a visit the honest answer is "unknown" — never a guess in either
	 * direction, and never counted as outstanding.
	 */
	@Test
	public void tearsOfGuthixIsUnknownUntilWeSeeAVisit()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesPack.Daily tears = pack().daily("tears_of_guthix");

		assertEquals(DailyTracker.State.UNKNOWN,
			DailyTracker.stateOf(state, tears, false, NOON));

		// an unknown event is never counted as outstanding: with everything
		// else deselected, nothing is claimable even though Tears is ticked
		for (DailiesPack.Daily daily : pack().dailies)
		{
			state.setDailySelected(daily.id, daily.id.equals(tears.id));
		}
		assertEquals(0, DailiesModule.outstanding(state, pack()));
	}

	/**
	 * Juna's reminder is the game telling us outright, so it beats every timer
	 * we could keep: it resolves the UNKNOWN with no history at all, and it
	 * repeats daily while you stay eligible, so a missed login costs nothing.
	 */
	@Test
	public void junasReminderResolvesTearsOfGuthixWithNoHistory()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesModule module = module(state);
		DailiesPack.Daily tears = module.pack().daily("tears_of_guthix");

		assertEquals(DailyTracker.State.UNKNOWN,
			DailyTracker.stateOf(state, tears, false, NOON));

		// the real string, as both server emulators reproduce it, colour tags on
		module.onChatMessage(gameMessage(
			"<col=ef1020>You are eligible to drink from the Tears of Guthix."));

		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, tears, false, NOON));
		assertTrue(module.cull().stream().anyMatch(d -> d.id.equals(tears.id)));
	}

	/** Playing it retires the eligibility Juna announced — otherwise the run
	 *  would keep offering a stop you just finished. */
	@Test
	public void playingRetiresJunasReminder()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesModule module = module(state);
		DailiesPack.Daily tears = module.pack().daily("tears_of_guthix");

		module.onChatMessage(gameMessage("You are eligible to drink from the Tears of Guthix."));
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, tears, false, NOON));

		StateFixture.varbit(state, tears.detection.varbit, 1);
		module.onVarbitChanged(collecting(tears.detection.varbit)); // in the cave
		module.onVarbitChanged(varbit(tears.detection.varbit, 0));  // and done
		module.onChatMessage(gameMessage("nothing to do with tears"));

		assertEquals(DailyTracker.State.DONE, DailyTracker.stateOf(
			state, tears, false, state.dailyDoneAt(tears.id)));
	}

	/** Chatter must not be able to fake it — the reminder is a game message. */
	@Test
	public void playerChatCannotFakeEligibility()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesModule module = module(state);

		net.runelite.api.events.ChatMessage said = new net.runelite.api.events.ChatMessage();
		said.setType(net.runelite.api.ChatMessageType.PUBLICCHAT);
		said.setMessage("You are eligible to drink from the Tears of Guthix.");
		module.onChatMessage(said);

		assertEquals(DailyTracker.State.UNKNOWN, DailyTracker.stateOf(
			state, module.pack().daily("tears_of_guthix"), false, NOON));
	}

	/** The minigame's "collecting now" varbit going live. */
	private static net.runelite.api.events.VarbitChanged collecting(int varbitId)
	{
		return varbit(varbitId, 1);
	}

	private static net.runelite.api.events.VarbitChanged varbit(int varbitId, int value)
	{
		net.runelite.api.events.VarbitChanged event = new net.runelite.api.events.VarbitChanged();
		event.setVarbitId(varbitId);
		event.setValue(value);
		return event;
	}

	private static net.runelite.api.events.ChatMessage gameMessage(String text)
	{
		net.runelite.api.events.ChatMessage event = new net.runelite.api.events.ChatMessage();
		event.setType(net.runelite.api.ChatMessageType.GAMEMESSAGE);
		event.setMessage(text);
		return event;
	}

	/** Once seen, it returns 7 days later at 00:00 UTC — not on a fixed weekday. */
	@Test
	public void tearsOfGuthixReturnsSevenDaysAfterTheVisit()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesPack.Daily tears = pack().daily("tears_of_guthix");

		state.markDaily(tears.id, true);
		long visit = state.dailyDoneAt(tears.id);
		long back = DailyTracker.startOfUtcDay(visit) + 7 * DAY;

		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, tears, false, visit));
		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, tears, false, back - 1));
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, tears, false, back));
	}

	/**
	 * Miscellania has no "collected today" flag, so the stop is done at 100%
	 * approval — and the varbit is 0..127 (core's MAX_APPROVAL), not 0..100.
	 * Reading it as a percentage directly would call 100 "100%" at 78%.
	 */
	@Test
	public void miscellaniaProgressesAtFullApprovalNotAtVarbit100()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);
		DailiesPack.Daily misc = pack().daily("miscellania");

		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, misc, false, NOON));

		StateFixture.varbit(state, misc.detection.varbit, 100);
		assertEquals("varbit 100 is only 78% — still work to do", 78,
			DailyTracker.approvalPercent(100));
		assertEquals(DailyTracker.State.AVAILABLE,
			DailyTracker.stateOf(state, misc, false, NOON));

		StateFixture.varbit(state, misc.detection.varbit, DailyTracker.MAX_APPROVAL);
		assertEquals(100, DailyTracker.approvalPercent(DailyTracker.MAX_APPROVAL));
		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, misc, false, NOON));
	}

	/**
	 * Robin hands back a bonemeal AND a bucket of slime per bone, neither
	 * noted, so one bone in is two slots out and an inventory holds 14 bones.
	 * Legs 4 is three trips.
	 */
	@Test
	public void robinTripsFollowTheInventoryNotTheTier()
	{
		AccountState state = state();
		DailiesPack.Daily robin = pack().daily("robin_bonemeal");
		assertEquals(Integer.valueOf(14), robin.perTrip);
		assertEquals("and he is the last stop, since you end holding a bank's worth",
			"robin_bonemeal", pack().dailies.get(pack().dailies.size() - 1).id);

		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1);
		assertEquals("13 fits one inventory", 1, DailyTracker.trips(state, robin));
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_HARD, 1);
		assertEquals("26 does not", 2, DailyTracker.trips(state, robin));
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_ELITE, 1);
		assertEquals(3, DailyTracker.trips(state, robin));

		// nothing else claims a trip limit
		assertEquals(1, DailyTracker.trips(state, pack().daily("zaff_battlestaves")));
	}

	/**
	 * Walking in and catching one tear is not a week's XP: the stop waits for
	 * the minigame to END (collecting 1 -> 0), which is when the tears are
	 * drunk and the XP lands.
	 */
	@Test
	public void tearsOfGuthixAdvancesOnFinishingNotOnStarting()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		DailiesModule module = module(state);
		DailiesPack.Daily tears = module.pack().daily("tears_of_guthix");
		int collecting = tears.detection.varbit;

		module.onChatMessage(gameMessage("You are eligible to drink from the Tears of Guthix."));
		assertEquals(DailyTracker.State.AVAILABLE, module.stateOf(tears));

		// started collecting — nothing has been earned yet
		StateFixture.varbit(state, collecting, 1);
		module.onVarbitChanged(varbit(collecting, 1));
		assertEquals("still collecting", DailyTracker.State.AVAILABLE, module.stateOf(tears));
		assertEquals(0, state.dailyDoneAt(tears.id));

		// time up: the tears are drunk
		StateFixture.varbit(state, collecting, 0);
		module.onVarbitChanged(varbit(collecting, 0));
		assertEquals(DailyTracker.State.DONE,
			DailyTracker.stateOf(state, tears, false, state.dailyDoneAt(tears.id)));
	}

	/** Lundail is in the Wilderness, so nobody is opted into it — and the pack
	 *  says so rather than the module hardcoding a name. */
	@Test
	public void wildernessDailiesAreOffUntilAskedFor()
	{
		AccountState state = state();
		DailiesModule module = module(state);
		DailiesPack.Daily lundail = module.pack().daily("lundail_runes");
		assertTrue(lundail.optOut);
		assertNotNull("and it says why when you tick it on", lundail.warning);
		assertFalse(module.selected(lundail));
		assertTrue("everything else is opted in",
			module.selected(module.pack().daily("zaff_battlestaves")));

		state.setDailySelected("lundail_runes", true);
		assertTrue(module.selected(lundail));
		// the choice is explicit now, and survives a reload
		state.persist();
		assertTrue(state.isDailySelected("lundail_runes", false));
	}

	/**
	 * Robin takes any bone, the flax keeper takes flax — no supplies, no trip.
	 * The amount tracks the diary tier, so the same bank can be enough today
	 * and short tomorrow.
	 */
	@Test
	public void supplyShortEventsAreOrangeAndNotStops()
	{
		AccountState state = state();
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1); // 13 bones
		DailiesModule module = module(state);
		DailiesPack.Daily robin = module.pack().daily("robin_bonemeal");

		assertEquals(DailyTracker.State.SHORT, module.stateOf(robin));
		assertEquals(List.of("13 bones (have 0)"), module.missing(robin));
		assertFalse("a trip you cannot supply is not a stop",
			ids(module.cull()).contains("robin_bonemeal"));

		// any bone type counts, and they add up (wiki: "do not need to be of the
		// same type") — 8 big bones + 5 dragon bones is 13
		StateFixture.bank(state, Map.of(532, 8, 536, 5));
		assertEquals(DailyTracker.State.AVAILABLE, module.stateOf(robin));
		assertTrue(module.missing(robin).isEmpty());
		assertTrue(ids(module.cull()).contains("robin_bonemeal"));

		// elite legs want 39 — the same bank is suddenly short
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_HARD, 1);
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_ELITE, 1);
		assertEquals(DailyTracker.State.SHORT, module.stateOf(robin));
		assertEquals(List.of("39 bones (have 13)"), module.missing(robin));
	}

	@Test
	public void flaxKeeperNeedsFlaxAndCoinsAreNeverGated()
	{
		AccountState state = state();
		StateFixture.varbit(state, Varbits.DIARY_KANDARIN_EASY, 1); // 30 flax
		DailiesModule module = module(state);
		DailiesPack.Daily flax = module.pack().daily("flax_bowstring");

		assertEquals(DailyTracker.State.SHORT, module.stateOf(flax));
		assertEquals(List.of("30 noted flax (have 0)"), module.missing(flax));
		StateFixture.bank(state, Map.of(1779, 30));
		assertEquals(DailyTracker.State.AVAILABLE, module.stateOf(flax));

		// Zaff asks for coins but is never gated on them — you can buy fewer
		DailiesPack.Daily zaff = module.pack().daily("zaff_battlestaves");
		assertEquals("35,000 coins", module.bringLine(zaff));
		assertTrue(module.missing(zaff).isEmpty());
		assertEquals(DailyTracker.State.AVAILABLE, module.stateOf(zaff));
	}

	/** The run is only the stops worth travelling to. */
	@Test
	public void runCullsLockedClaimedAndDeselected()
	{
		AccountState state = state();
		DailiesModule module = module(state);
		// Nothing unlocked yet except Zaff, which needs no diary at all.
		assertEquals(List.of("zaff_battlestaves"), ids(module.cull()));

		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		assertEquals(List.of("zaff_battlestaves", "thirus_dynamite"), ids(module.cull()));

		// already claimed today → not a stop
		StateFixture.varbit(state, module.pack().daily("zaff_battlestaves").detection.varbit, 1);
		assertEquals(List.of("thirus_dynamite"), ids(module.cull()));

		// the player's own checklist wins over everything
		state.setDailySelected("thirus_dynamite", false);
		assertTrue(module.cull().isEmpty());
	}

	/** A stop advances when the game says you claimed it — never on arrival. */
	@Test
	public void stopAdvancesWhenTheClaimVarbitFlips()
	{
		AccountState state = state();
		DailiesModule module = module(state);
		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		module.startRun();

		assertEquals(2, module.stops().size());
		assertEquals("zaff_battlestaves", module.nextStop().id);

		module.checkAdvance();
		assertEquals("standing there claims nothing", "zaff_battlestaves", module.nextStop().id);

		StateFixture.varbit(state, module.pack().daily("zaff_battlestaves").detection.varbit, 1);
		module.checkAdvance();
		assertEquals("thirus_dynamite", module.nextStop().id);
		assertEquals(1, module.visitedCount());

		// claiming the last one completes the run
		StateFixture.varbit(state, module.pack().daily("thirus_dynamite").detection.varbit, 1);
		module.checkAdvance();
		assertFalse(module.running());
	}

	/** Skip marks this stop and everything before it — the escape hatch, and
	 *  the only way past Miscellania, which has no claim flag to watch. */
	@Test
	public void skipMarksThroughAndCanFinishTheRun()
	{
		AccountState state = state();
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);
		DailiesModule module = module(state);
		module.startRun();

		assertEquals(List.of("zaff_battlestaves", "miscellania"), ids(module.stops()));
		module.markThrough("miscellania");
		assertFalse("marking through the last stop ends the run", module.running());
	}

	@Test
	public void endingARunEarlyClearsIt()
	{
		AccountState state = state();
		DailiesModule module = module(state);
		module.startRun();
		assertTrue(module.running());

		module.endRun(false);
		assertFalse(module.running());
		assertTrue(module.stops().isEmpty());
	}

	/**
	 * The reset notification fires once when the clock crosses 00:00 UTC, and a
	 * session never opens by announcing a reset that happened before it started
	 * — the login replay a naive "check on startup" would produce every time.
	 */
	@Test
	public void resetNotifiesOnceAndNeverReplaysOnLogin()
	{
		AccountState state = state();
		net.runelite.client.Notifier notifier = org.mockito.Mockito.mock(
			net.runelite.client.Notifier.class);
		DailiesModule module = module(state, notifier);
		// noon UTC today, NOT the wall clock: startUp anchors "already told"
		// to the real current UTC day, and a raw now+DAY+1h straddles a UTC
		// midnight whenever the suite runs in the hour before one — this
		// test used to fail between 00:00 and 01:00 BST and no other time
		long now = DailyTracker.startOfUtcDay(System.currentTimeMillis()) + DAY / 2;

		// a fresh session, same UTC day it started: today's reset is not news
		module.notifyReset(now);
		org.mockito.Mockito.verifyNoInteractions(notifier);

		// the clock rolls over: Zaff alone is claimable, and it is said once
		module.notifyReset(now + DAY);
		org.mockito.Mockito.verify(notifier).notify("1 daily is available again");
		module.notifyReset(now + DAY + 3_600_000L);
		org.mockito.Mockito.verifyNoMoreInteractions(notifier);
	}

	/** Logging in re-baselines the varbits: they are only refreshed from the
	 *  server then, which is what makes crossedReset() meaningful. */
	@Test
	public void loginMarksTheClaimVarbitsFresh()
	{
		DailiesModule module = module(state());
		assertFalse("no login seen yet — nothing to call stale", module.crossedReset());

		net.runelite.api.events.GameStateChanged login =
			new net.runelite.api.events.GameStateChanged();
		login.setGameState(net.runelite.api.GameState.LOGGED_IN);
		module.onGameStateChanged(login);

		assertFalse("just logged in — the varbits are exact", module.crossedReset());
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = state();
		// A mid-game iron: some diaries done, one daily already claimed.
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KANDARIN_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);

		DailiesModule module = module(state);
		StateFixture.varbit(state, module.pack().daily("flax_bowstring").detection.varbit, 1);

		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		write(image, "dailies-tab.png");
		module.shutDown();
	}

	@Test
	public void activeRunTabRendersHeadless() throws Exception
	{
		AccountState state = state();
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_ELITE, 1);
		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);

		DailiesModule module = module(state);
		JComponent tab = module.buildTab();
		module.startRun();
		// startRun queues its rebuild on the EDT — let it land before rendering.
		// Rebuilding here directly would race it and double every row.
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});

		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		write(image, "dailies-run-active.png");
		module.shutDown();
	}

	/** The canvas companion, inside the 250x200 overlay budget. */
	@Test
	public void runOverlayRendersInsideTheBudget() throws Exception
	{
		// A late-game iron with every gate open — the worst case for the
		// overlay's height budget. Each tier is its own varbit: completing
		// Elite does NOT imply Easy, so every gate has to be seeded.
		AccountState state = state();
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_ELITE, 1);
		StateFixture.varbit(state, Varbits.DIARY_KANDARIN_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_ARDOUGNE_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_WESTERN_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_WILDERNESS_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1);
		StateFixture.quest(state, Quest.THE_HAND_IN_THE_SAND, QuestState.FINISHED);
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		// stocked for the two supply-gated stops, or they cull themselves out
		StateFixture.bank(state, Map.of(526, 100, 1779, 300));

		DailiesModule module = module(state);
		// and opted in to the Wilderness trip, which nobody is by default
		state.setDailySelected("lundail_runes", true);
		module.startRun();
		assertEquals("every event open — the overlay's worst case",
			module.pack().dailies.size(), module.stops().size());

		DailiesRunOverlay overlay = new DailiesRunOverlay(module);
		java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
			300, 260, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = canvas.createGraphics();
		g.setColor(new java.awt.Color(58, 66, 48));
		g.fillRect(0, 0, 300, 260);
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		java.awt.Dimension size = overlay.render(g);
		g.dispose();

		assertNotNull(size);
		assertTrue("overlay width " + size.width, size.width <= 250);
		assertTrue("overlay height " + size.height, size.height <= 200);
		javax.imageio.ImageIO.write(canvas, "png",
			new java.io.File("build/reports/dailies-run-overlay.png"));
		module.shutDown();
	}

	private static void write(java.awt.image.BufferedImage image, String name) throws Exception
	{
		java.io.File out = new java.io.File("build/reports/" + name);
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	private static List<String> ids(List<DailiesPack.Daily> dailies)
	{
		return dailies.stream().map(d -> d.id).collect(java.util.stream.Collectors.toList());
	}
}
