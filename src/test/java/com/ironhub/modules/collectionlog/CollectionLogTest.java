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
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clog(ABYSSAL_ORPHAN, "Abyssal orphan",
			"Killing abyssal sire (on task)", List.of("skill:Slayer:85")));
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
		before.addGoalSeed(com.ironhub.state.GoalSeeds.clog(2577, "Ranger boots", "Medium clues", List.of()));

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 21L);
		assertEquals(246, after.getCollectionLogSlots());
		assertEquals(1568, after.getCollectionLogTotal());
		assertTrue(after.getClogObtained().contains(ABYSSAL_ORPHAN));
		assertTrue(after.getClogSkipped().contains(17));
		assertEquals(246, after.getClogBaseline());
		assertTrue(after.getSelectedGoals().contains("clog:2577"));
		assertEquals("Ranger boots", after.getGoalSeeds().get("clog:2577").name);
	}

	@Test
	public void clogGoalsCompileLikeEveryOtherGoal()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clog(ABYSSAL_ORPHAN, "Abyssal orphan",
			"Killing abyssal sire (on task)", List.of("skill:Slayer:85")));

		PersistedState.GoalSeed seed = state.getGoalSeeds().get("clog:" + ABYSSAL_ORPHAN);
		GoalsPack.Goal goal = GoalPlannerModule.toGoal(seed);
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
		assertTrue(state.getGoalSeeds().isEmpty());
		assertFalse(state.getSelectedGoals().contains("clog:" + ABYSSAL_ORPHAN));
	}

	/**
	 * The catalog comes out of the game's own cache: five tab structs, each
	 * naming an enum of page structs, each page naming an enum of slot ids.
	 * This pins the param ids the log's cs2 uses — get one wrong and the
	 * browser is silently empty in the client, where no test can see it.
	 */
	@Test
	public void catalogReadsTheGamesOwnStructs()
	{
		Client client = Mockito.mock(Client.class);
		int[] tabStructs = {471, 472, 473, 474, 475};
		String[] tabNames = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
		for (int i = 0; i < tabStructs.length; i++)
		{
			// one page per tab, wide enough that the five clear the slot floor
			int pageStruct = 5000 + i;
			int pageEnum = 6000 + i;
			int itemEnum = 7000 + i;
			net.runelite.api.StructComposition tab =
				Mockito.mock(net.runelite.api.StructComposition.class);
			when(tab.getStringValue(682)).thenReturn(tabNames[i]);
			when(tab.getIntValue(683)).thenReturn(pageEnum);
			when(client.getStructComposition(tabStructs[i])).thenReturn(tab);

			net.runelite.api.EnumComposition pages =
				Mockito.mock(net.runelite.api.EnumComposition.class);
			when(pages.getIntVals()).thenReturn(new int[]{pageStruct});
			when(client.getEnum(pageEnum)).thenReturn(pages);

			net.runelite.api.StructComposition page =
				Mockito.mock(net.runelite.api.StructComposition.class);
			when(page.getStringValue(689)).thenReturn(tabNames[i] + " page");
			when(page.getIntValue(690)).thenReturn(itemEnum);
			when(client.getStructComposition(pageStruct)).thenReturn(page);

			int[] items = new int[250];
			for (int slot = 0; slot < items.length; slot++)
			{
				items[slot] = 100 + i * 1000 + slot;
			}
			net.runelite.api.EnumComposition itemList =
				Mockito.mock(net.runelite.api.EnumComposition.class);
			when(itemList.getIntVals()).thenReturn(items);
			when(client.getEnum(itemEnum)).thenReturn(itemList);
		}

		List<PersistedState.ClogTab> catalog = ClogCatalog.load(client);
		assertEquals(5, catalog.size());
		assertEquals("Bosses", catalog.get(0).name);
		assertEquals("Bosses page", catalog.get(0).pages.get(0).name);
		assertEquals(250, catalog.get(0).pages.get(0).items.length);

		// a cache-layout change reads short: return nothing rather than
		// replace a good snapshot with a broken one
		Client drifted = Mockito.mock(Client.class);
		assertTrue(ClogCatalog.load(drifted).isEmpty());
	}

	/** The grid is the log's own: five to a row, 42x36 cells, and every slot
	 *  present whether you own it or not (the game ghosts, never hides). */
	@Test
	public void theItemGridLaysOutLikeTheInterface()
	{
		List<ClogItemGrid.Cell> cells = new java.util.ArrayList<>();
		for (int i = 0; i < 11; i++)
		{
			cells.add(new ClogItemGrid.Cell(100 + i, "Slot " + i, i % 2 == 0, i));
		}
		ClogItemGrid grid = new ClogItemGrid(cells, new com.ironhub.ui.components.SpriteCache(
			null, () -> { }));
		assertEquals(ClogItemGrid.COLUMNS * ClogItemGrid.CELL_WIDTH,
			grid.getPreferredSize().width);
		assertEquals(3 * ClogItemGrid.CELL_HEIGHT, grid.getPreferredSize().height);
		assertEquals(cells.get(0), grid.cellAt(new java.awt.Point(4, 4)));
		assertEquals(cells.get(6), grid.cellAt(new java.awt.Point(
			ClogItemGrid.CELL_WIDTH + 4, ClogItemGrid.CELL_HEIGHT + 4)));
		assertNull("past the last cell is empty space",
			grid.cellAt(new java.awt.Point(4 * ClogItemGrid.CELL_WIDTH,
				2 * ClogItemGrid.CELL_HEIGHT)));
		// the game's own abbreviations
		assertEquals("999", ClogItemGrid.count(999));
		assertEquals("65535", ClogItemGrid.count(65_535)); // exact below 100K
		assertEquals("100K", ClogItemGrid.count(100_000));
		assertEquals("10M", ClogItemGrid.count(10_000_000));
	}

	/** Only a live drop dates a slot: an import learns WHAT you own, never
	 *  when, so "Latest collections" can never invent an order. */
	@Test
	public void onlyLiveDropsAreDated()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		CollectionLogModule module = module(state, null);

		state.markClogObtained(java.util.Map.of(2577, 3), false); // imported
		assertEquals(0, state.clogObtainedAt(2577));
		assertEquals(3, state.clogQuantity(2577));

		module.onChatMessage(chat("New item added to your collection log: Abyssal orphan"));
		assertTrue(state.clogObtainedAt(ABYSSAL_ORPHAN) > 0);

		// counts only climb: a live drop knows nothing about the running total
		state.markClogObtained(java.util.Map.of(2577, 1), true);
		assertEquals(3, state.clogQuantity(2577));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 21L);
		state.recordCollectionLog(1190, 1706);
		StateFixture.varp(state, VarPlayerID.COLLECTION_COUNT, 1190);
		state.recordClogFullSync(1190);
		CollectionLogModule module = module(state, null);
		state.setClogCatalog(catalogFixture(module.pack()));

		// own most of the first pages, with counts and a couple of dated
		// slots so the hero, the tiles and Latest collections all say
		// something real
		java.util.Map<Integer, Integer> owned = new java.util.LinkedHashMap<>();
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			for (PersistedState.ClogPage page : tab.pages)
			{
				for (int i = 0; i < page.items.length; i += 2)
				{
					owned.put(page.items[i], i == 0 ? 79 : 1);
				}
			}
		}
		state.markClogObtained(owned, false);
		PersistedState.ClogPage first = state.getClogCatalog().get(0).pages.get(0);
		state.markClogObtained(java.util.Map.of(first.items[1], 2), true);
		state.markClogObtained(java.util.Map.of(first.items[3], 1), true);
		state.recordClogPageCounts(first.name, List.of("High-level Gambles: 2,733"));
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clog(ABYSSAL_ORPHAN, "Abyssal orphan",
			"Killing abyssal sire (on task)", List.of("skill:Slayer:85")));

		CollectionLogTab tab = (CollectionLogTab) module.buildTab();
		assertNotNull(tab);
		render(tab, "collectionlog-overview");

		String firstTab = state.getClogCatalog().get(0).name;
		tab.showForRender(firstTab, null);
		render(tab, "collectionlog-category");

		tab.showForRender(firstTab, first.name);
		java.awt.image.BufferedImage page = render(tab, "collectionlog-page");
		assertTrue(page.getHeight() > 300);
		module.shutDown();
	}

	private static java.awt.image.BufferedImage render(JComponent tab, String name)
		throws Exception
	{
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		java.io.File out = new java.io.File("build/reports/" + name + ".png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		return image;
	}

	/** A stand-in for the game's catalog, built from the ranking pack so the
	 *  page names, slot ids and sizes are real. */
	private static List<PersistedState.ClogTab> catalogFixture(ClogPack pack)
	{
		String[] names = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
		List<PersistedState.ClogTab> tabs = new java.util.ArrayList<>();
		int activity = 0;
		for (String name : names)
		{
			PersistedState.ClogTab tab = new PersistedState.ClogTab();
			tab.name = name;
			for (int i = 0; i < 8 && activity < pack.activities.size(); i++, activity++)
			{
				ClogPack.Activity source = pack.activities.get(activity);
				PersistedState.ClogPage page = new PersistedState.ClogPage();
				page.name = source.name;
				page.items = source.items.stream().mapToInt(item -> item.itemId).toArray();
				tab.pages.add(page);
			}
			tabs.add(tab);
		}
		return tabs;
	}
}
