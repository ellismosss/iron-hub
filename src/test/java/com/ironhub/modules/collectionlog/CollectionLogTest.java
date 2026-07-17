package com.ironhub.modules.collectionlog;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.ClogPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class CollectionLogTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static final int ABYSSAL_ORPHAN = 13262;

	private CollectionLogModule module(AccountState state, Client client)
	{
		CollectionLogModule module = new CollectionLogModule(state, client, null,
			new EventBus(), new IronHubConfig()
		{
		}, new DataPack(new Gson()), null);
		module.startUp();
		return module;
	}

	private static ChatMessage chat(String message)
	{
		return new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", message, "", 0);
	}

	@Test
	public void titleParsing()
	{
		assertArrayEquals(new int[]{246, 1568},
			CollectionLogModule.parseTitle("Collection Log - 246/1,568"));
		assertArrayEquals(new int[]{1568, 1568},
			CollectionLogModule.parseTitle("Collection Log - 1,568/1,568"));
		assertNull(CollectionLogModule.parseTitle("Bank of Gielinor"));
		assertNull(CollectionLogModule.parseTitle(null));
	}

	@Test
	public void chatDropsResolveMarkAndProveGoals()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		CollectionLogModule module = module(state, null);

		// the goal exists before the drop; the chat message must prove it
		state.addClogGoal(ABYSSAL_ORPHAN, "Abyssal orphan", "Killing abyssal sire (on task)",
			List.of("skill:Slayer:85"));
		module.onChatMessage(chat("New item added to your collection log: Abyssal orphan"));

		assertTrue(state.getClogObtained().contains(ABYSSAL_ORPHAN));
		assertTrue(state.isUnlocked("clogitem_" + ABYSSAL_ORPHAN));

		// unknown names never invent an item
		module.onChatMessage(chat("New item added to your collection log: Definitely not real"));
		assertEquals(1, state.getClogObtained().size());

		// unrelated chat is ignored
		module.onChatMessage(chat("Your Slayer level is now 86."));
		assertEquals(1, state.getClogObtained().size());
	}

	@Test
	public void chatAliasesResolveTheSharedSlot()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		CollectionLogModule module = module(state, null);

		// "Farmer's jacket" never matches the slot name "Farmer's shirt/jacket"
		module.onChatMessage(chat("New item added to your collection log: Farmer's jacket"));
		assertTrue(state.getClogObtained().contains(13642));
	}

	@Test
	public void fullSyncHarvestsCanonicalizesAndPinsTheBaseline()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		Client client = Mockito.mock(Client.class);
		when(client.getTickCount()).thenReturn(10);
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(42);
		CollectionLogModule module = module(state, client);

		module.triggerFullSync();
		ScriptEvent orphan = Mockito.mock(ScriptEvent.class);
		when(orphan.getArguments()).thenReturn(new Object[]{2240, ABYSSAL_ORPHAN});
		ScriptPreFired fired = new ScriptPreFired(4100);
		fired.setScriptEvent(orphan);
		module.onScriptPreFired(fired);
		// Body-type-B Farmer's boro trousers must canonicalize to the A id
		ScriptEvent trousers = Mockito.mock(ScriptEvent.class);
		when(trousers.getArguments()).thenReturn(new Object[]{2240, 13641});
		ScriptPreFired fired2 = new ScriptPreFired(4100);
		fired2.setScriptEvent(trousers);
		module.onScriptPreFired(fired2);

		// not consumed until the settle ticks pass
		module.onGameTick(new GameTick());
		assertTrue(state.getClogObtained().isEmpty());

		when(client.getTickCount()).thenReturn(13);
		module.onGameTick(new GameTick());
		assertTrue(state.getClogObtained().contains(ABYSSAL_ORPHAN));
		assertTrue(state.getClogObtained().contains(13640));
		assertEquals(42, state.getClogBaseline());
		assertTrue(state.getClogSyncedMs() > 0);
	}

	@Test
	public void syncDriftDetection()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		CollectionLogModule module = module(state, null);

		// varp unseen: don't nag
		assertTrue(module.inSync());

		// slots exist but never synced: nudge
		StateFixture.varp(state, VarPlayerID.COLLECTION_COUNT, 246);
		assertFalse(module.inSync());

		// full sync pins the baseline
		state.recordClogFullSync(246);
		assertTrue(module.inSync());

		// a live drop advances the count — the chat handler bumps in lockstep
		StateFixture.varp(state, VarPlayerID.COLLECTION_COUNT, 247);
		assertFalse(module.inSync());
		module.onChatMessage(chat("New item added to your collection log: Abyssal orphan"));
		assertTrue(module.inSync());
	}

	@Test
	public void clogStatePersistsAcrossRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 21L);
		before.recordCollectionLog(246, 1568);
		before.markClogObtained(List.of(ABYSSAL_ORPHAN));
		before.setClogSkipped(17, true);
		before.recordClogFullSync(246);
		before.addClogGoal(2577, "Ranger boots", "Medium clues", List.of());

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 21L);
		assertEquals(246, after.getCollectionLogSlots());
		assertEquals(1568, after.getCollectionLogTotal());
		assertTrue(after.getClogObtained().contains(ABYSSAL_ORPHAN));
		assertTrue(after.getClogSkipped().contains(17));
		assertEquals(246, after.getClogBaseline());
		assertTrue(after.getSelectedGoals().contains("clog:2577"));
		assertEquals("Ranger boots", after.getClogGoals().get("2577").name);
	}

	@Test
	public void clogGoalsCompileLikeEveryOtherGoal()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		state.addClogGoal(ABYSSAL_ORPHAN, "Abyssal orphan", "Killing abyssal sire (on task)",
			List.of("skill:Slayer:85"));

		PersistedState.ClogGoal seed = state.getClogGoals().get(String.valueOf(ABYSSAL_ORPHAN));
		GoalsPack.Goal goal = GoalPlannerModule.toClogGoal(String.valueOf(ABYSSAL_ORPHAN), seed);
		assertEquals("clog:" + ABYSSAL_ORPHAN, goal.getId());
		assertEquals("Abyssal orphan", goal.getName());
		assertEquals((Integer) ABYSSAL_ORPHAN, goal.icon());
		assertEquals(2, goal.getSteps().size()); // the Slayer gate + the obtain step
		assertEquals("85 Slayer", goal.getSteps().get(0).getLabel());

		// not achieved until the module proves the unlock
		assertFalse(GoalPlannerModule.isAchieved(goal, state));
		state.setUnlocked("clogitem_" + ABYSSAL_ORPHAN, true);
		assertTrue(GoalPlannerModule.isAchieved(goal, state));

		// allGoals surfaces it while selected, removeGoal retires the seed
		GoalsPack empty = new DataPack(new Gson()).load("goals", GoalsPack.class);
		com.ironhub.data.GearProgressionPack gear =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		assertTrue(GoalPlannerModule.allGoals(empty, gear, state).stream()
			.anyMatch(g -> g.getId().equals("clog:" + ABYSSAL_ORPHAN)));
		GoalPlannerModule.removeGoal(state, "clog:" + ABYSSAL_ORPHAN);
		assertTrue(state.getClogGoals().isEmpty());
		assertFalse(state.getSelectedGoals().contains("clog:" + ABYSSAL_ORPHAN));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		state.recordCollectionLog(246, 1568);
		StateFixture.varp(state, VarPlayerID.COLLECTION_COUNT, 246);
		state.recordClogFullSync(246);
		CollectionLogModule module = module(state, null);
		ClogPack pack = module.pack();
		// own a few slots and skip an activity so every row state renders
		state.markClogObtained(List.of(
			pack.activities.get(0).items.get(0).itemId,
			pack.activities.get(1).items.get(0).itemId));
		state.setClogSkipped(pack.activities.get(5).index, true);
		state.addClogGoal(ABYSSAL_ORPHAN, "Abyssal orphan", "Killing abyssal sire (on task)",
			List.of("skill:Slayer:85"));

		JComponent tab = module.buildTab();
		assertNotNull(tab);
		// open the top activity's slot card + the skipped sink so the
		// expansion surfaces render too
		((CollectionLogTab) tab).expandTopForRender();
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 300);
		java.io.File out = new java.io.File("build/reports/collectionlog-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
