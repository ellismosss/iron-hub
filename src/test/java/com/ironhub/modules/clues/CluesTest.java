package com.ironhub.modules.clues;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.ClueStepsPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CluesTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final ClueStepsPack pack = new DataPack(new Gson()).load("clue-steps", ClueStepsPack.class);

	/** Vanilla, not the config default. {@code osrsTheme()} defaults to MYSTIC,
	 *  so every render this test wrote came out grey — and the renders exist to
	 *  be judged against the Vanilla design system (Luke, 2026-07-25). */
	private final IronHubConfig config = new IronHubConfig()
	{
		@Override
		public com.ironhub.ui.osrs.OsrsTheme osrsTheme()
		{
			return com.ironhub.ui.osrs.OsrsTheme.STONE;
		}
	};

	private ClueStashModule module(AccountState state)
	{
		return new ClueStashModule(state, config, new DataPack(new Gson()), new EventBus(), null, null, null);
	}

	/** The Lumbridge swamp shack dance: bronze dagger, iron full helm, gold ring. */
	private ClueStepsPack.Clue swampShack()
	{
		return pack.clues.stream()
			.filter(c -> c.text.contains("shack in Lumbridge Swamp"))
			.findFirst().orElseThrow();
	}

	@Test
	public void readinessFollowsOwnedItems()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStepsPack.Clue clue = swampShack();

		assertFalse(ClueStashModule.doable(clue, state));
		assertNotNull(ClueStashModule.blocking(clue, state));

		// bronze dagger 1205, iron full helm 1153, gold ring 1635
		StateFixture.bank(state, Map.of(1205, 1, 1153, 1, 1635, 1));
		assertTrue(ClueStashModule.doable(clue, state));
		assertNull(ClueStashModule.blocking(clue, state));
	}

	@Test
	public void stashDetectionSemantics()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		ClueStashModule module = module(state);
		module.startUp();
		ClueStepsPack.Stash unit = pack.stash.get(0);

		// chat classification is keyword-loose (STASH Tracker parity)
		assertEquals(ClueStashModule.FILLED, ClueStashModule.classify("you deposit your items into the stash unit."));
		assertEquals(ClueStashModule.EMPTIED, ClueStashModule.classify("you withdraw your items from the stash unit."));
		assertEquals(ClueStashModule.BUILT, ClueStashModule.classify("you build a stash unit here."));
		assertEquals(ClueStashModule.NO_CHANGE, ClueStashModule.classify("a stash of gold?"));
		// inspecting an UNBUILT spot must not read as building it (the
		// 2026-07-28 false-mark report): prospective wording is ignored
		assertEquals(ClueStashModule.NO_CHANGE, ClueStashModule.classify(
			"you can build a stash unit here. it requires level 77 construction."));
		assertEquals(ClueStashModule.NO_CHANGE, ClueStashModule.classify(
			"a stash unit can be built here with the right materials."));
		assertEquals(ClueStashModule.NO_CHANGE, ClueStashModule.classify(
			"you need to complete more hard clues before building this stash unit."));

		// filling implies built; manual toggle round-trips
		state.setStashFilled(unit.objectId, true);
		assertTrue(state.isStashBuilt(unit.objectId));
		assertTrue(state.isStashFilled(unit.objectId));
		module.toggleFilled(unit);
		assertFalse(state.isStashFilled(unit.objectId));
		assertTrue(state.isStashBuilt(unit.objectId)); // emptying leaves built

		// persistence round-trip
		state.setStashFilled(unit.objectId, true);
		assertTrue(state.isStashFilled(unit.objectId));
		module.shutDown();
	}

	/**
	 * CL1 2026-08-03: the route card's outfit filter checks what is ON YOUR
	 * PERSON — inventory and worn count, the bank does not (mid-route the
	 * bank is behind you), any: alternatives count, quantities gate.
	 */
	@Test
	public void carriedReqCountsPersonNotBank()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);

		String req = "any:item:1205:1:Bronze dagger|item:1153:1:Iron full helm";
		assertFalse(ClueStashModule.carriedReq(state, req));

		// banked is NOT carried
		StateFixture.bank(state, Map.of(1205, 1));
		assertFalse(ClueStashModule.carriedReq(state, req));

		// worn IS carried (carriedCount reads the SLOT array); so is
		// inventory; either alternative satisfies
		StateFixture.equipmentSlots(state, new int[]{1153});
		assertTrue(ClueStashModule.carriedReq(state, req));
		StateFixture.equipmentSlots(state, new int[]{});
		StateFixture.inventory(state, Map.of(1205, 1));
		assertTrue(ClueStashModule.carriedReq(state, req));

		// a quantity gates: 2 needed, 1 carried
		assertFalse(ClueStashModule.carriedReq(state, "item:1205:2:Bronze daggers"));
	}

	/**
	 * CL2 2026-08-03: "+ Goal" on a tier seeds one goal through the unified
	 * seed system — one step per STASH unit, each proven by its
	 * cluestash_<key> unlock as the unit fills; already-filled units prove
	 * at add time; removal round-trips.
	 */
	@Test
	public void tierGoalSeedsAndProvesPerUnit()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		ClueStashModule module = module(state);
		module.startUp();

		String tier = pack.stash.get(0).tier;
		java.util.List<ClueStepsPack.Stash> units = new java.util.ArrayList<>();
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			if (tier.equalsIgnoreCase(unit.tier))
			{
				units.add(unit);
			}
		}

		// one unit already filled BEFORE tracking: it must prove at add time
		state.setStashFilled(units.get(0).objectId, true);
		module.addTierGoal(tier);
		assertTrue(module.isTierGoal(tier));
		com.ironhub.state.PersistedState.GoalSeed seed =
			state.getGoalSeeds().get(com.ironhub.state.GoalSeeds.clueTierId(tier));
		assertNotNull(seed);
		assertEquals("one step per unit", units.size(), seed.steps.size());
		assertEquals("one proof per unit", units.size(), seed.achieved.size());
		assertTrue("a pre-filled unit proves immediately", state.isUnlocked(
			com.ironhub.state.GoalSeeds.clueStashProof(units.get(0).key)));
		assertFalse("an unfilled unit never proves", state.isUnlocked(
			com.ironhub.state.GoalSeeds.clueStashProof(units.get(1).key)));

		// a unit filling later proves through the state listener
		state.setStashFilled(units.get(1).objectId, true);
		assertTrue(state.isUnlocked(
			com.ironhub.state.GoalSeeds.clueStashProof(units.get(1).key)));

		module.removeTierGoal(tier);
		assertFalse(module.isTierGoal(tier));
		module.shutDown();
	}

	/** Every unit carries its gameval HH_CONSTRUCTED_* built varbit —
	 *  unique, present, the S.T.A.S.H chart's own source (2026-07-29). */
	@Test
	public void everyUnitHasAUniqueBuiltVarbit()
	{
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			assertTrue(unit.key, unit.varbitId > 0);
			assertTrue("duplicate varbit " + unit.varbitId, seen.add(unit.varbitId));
		}
		assertEquals(pack.stash.size(), seen.size());
	}

	/** The built varbits are AUTHORITATIVE both ways: 1 marks built, 0
	 *  clears built AND filled (nothing stands there), so stale or false
	 *  marks heal themselves. */
	@Test
	public void builtVarbitsSetAndClear()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStashModule module = module(state);
		ClueStepsPack.Stash unit = pack.stash.get(0);

		net.runelite.api.events.VarbitChanged event =
			org.mockito.Mockito.mock(net.runelite.api.events.VarbitChanged.class);
		org.mockito.Mockito.when(event.getVarbitId()).thenReturn(unit.varbitId);
		org.mockito.Mockito.when(event.getValue()).thenReturn(1);
		module.onVarbitChanged(event);
		assertTrue(state.isStashBuilt(unit.objectId));

		state.setStashFilled(unit.objectId, true);
		org.mockito.Mockito.when(event.getValue()).thenReturn(0);
		module.onVarbitChanged(event);
		assertFalse(state.isStashBuilt(unit.objectId));
		assertFalse(state.isStashFilled(unit.objectId));

		// an unrelated varbit is ignored
		org.mockito.Mockito.when(event.getVarbitId()).thenReturn(-1);
		org.mockito.Mockito.when(event.getValue()).thenReturn(1);
		module.onVarbitChanged(event);
		assertFalse(state.isStashBuilt(unit.objectId));
	}

	/** One full sweep per session covers the mid-session plugin enable
	 *  that VarbitChanged never fires for. */
	@Test
	public void gameTickSweepsBuiltVarbitsOnce()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		org.mockito.Mockito.when(client.getGameState())
			.thenReturn(net.runelite.api.GameState.LOGGED_IN);
		ClueStepsPack.Stash built = pack.stash.get(0);
		org.mockito.Mockito.when(client.getVarbitValue(built.varbitId)).thenReturn(1);
		ClueStashModule module = new ClueStashModule(state, config, new DataPack(new Gson()),
			new EventBus(), client, null, null);
		module.onGameTick(null);
		assertTrue(state.isStashBuilt(built.objectId));
		assertFalse(state.isStashBuilt(pack.stash.get(1).objectId));
		// swept once — later ticks don't re-read
		org.mockito.Mockito.clearInvocations(client);
		module.onGameTick(null);
		org.mockito.Mockito.verify(client,
			org.mockito.Mockito.never()).getVarbitValue(org.mockito.Mockito.anyInt());
	}

	/** Varbits move while the module is off — a disable/enable cycle must
	 *  sweep the STASH built states fresh, exactly like a new session. */
	@Test
	public void reEnabledModuleSweepsBuiltVarbitsAgain()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		org.mockito.Mockito.when(client.getGameState())
			.thenReturn(net.runelite.api.GameState.LOGGED_IN);
		ClueStepsPack.Stash built = pack.stash.get(0);
		org.mockito.Mockito.when(client.getVarbitValue(built.varbitId)).thenReturn(1);
		ClueStashModule module = new ClueStashModule(state, config, new DataPack(new Gson()),
			new EventBus(), client, null, null);
		module.startUp();
		module.onGameTick(null);
		assertTrue(state.isStashBuilt(built.objectId));

		module.shutDown();
		module.startUp();
		org.mockito.Mockito.clearInvocations(client);
		module.onGameTick(null);
		org.mockito.Mockito.verify(client,
			org.mockito.Mockito.atLeastOnce()).getVarbitValue(built.varbitId);
		module.shutDown();
	}

	@Test
	public void readyToFillNeedsOwnershipAndAnUnfilledUnit()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStashModule module = module(state);
		module.startUp();

		ClueStepsPack.Clue clue = swampShack();
		ClueStepsPack.Stash unit = pack.stash.stream()
			.filter(u -> clue.id.equals(u.clueId)).findFirst().orElseThrow();

		assertFalse(module.readyToFill(unit)); // items not owned
		StateFixture.bank(state, Map.of(1205, 1, 1153, 1, 1635, 1));
		assertTrue(module.readyToFill(unit));
		state.setStashFilled(unit.objectId, true);
		assertFalse(module.readyToFill(unit)); // stored — no longer loose
		module.shutDown();
	}

	@Test
	public void clueGoalLifecycle()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		ClueStashModule module = module(state);
		module.startUp();
		ClueStepsPack.Clue clue = swampShack();

		module.addGoal(clue);
		assertTrue(module.isGoal(clue));
		assertTrue(state.getSelectedGoals().contains("clue:" + clue.id));
		assertFalse(state.isUnlocked("cluestep_" + clue.id)); // not doable yet

		// the goal appears in the planner's goal set with the reqs as steps
		com.ironhub.data.GoalsPack.Goal goal =
			com.ironhub.modules.goals.GoalPlannerModule.toGoal(
				state.getGoalSeeds().get("clue:" + clue.id));
		assertEquals("clue:" + clue.id, goal.getId());
		assertEquals(clue.reqs.size(), goal.getSteps().size());

		// obtaining the items marks the achieved proof
		StateFixture.bank(state, Map.of(1205, 1, 1153, 1, 1635, 1));
		assertTrue(state.isUnlocked("cluestep_" + clue.id));

		module.removeGoal(clue);
		assertFalse(module.isGoal(clue));
		assertFalse(state.getSelectedGoals().contains("clue:" + clue.id));
		module.shutDown();
	}

	/** A clue outfit sitting in a "Where's my stuff" storage (POH costume
	 *  room etc.) counts as owned — the module's owningView (Luke,
	 *  2026-07-28; the old bank+carried-only note was stale once WMS
	 *  landed). */
	@Test
	public void storageOwnedOutfitCountsAsDoable()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStashModule module = module(state);
		ClueStepsPack.Clue clue = swampShack();
		assertFalse(ClueStashModule.doable(clue, module.owningView()));

		// the same outfit, seen only in a POH fancy dress box
		java.util.Map<Integer, Integer> items = new java.util.HashMap<>();
		for (String raw : clue.reqs)
		{
			String alt = raw.startsWith("any:") ? raw.substring(4).split("\\|")[0] : raw;
			items.put(Integer.parseInt(alt.split(":")[1]), 1);
		}
		state.putStorageContents("poh_fancy_dress", "Fancy dress box", "poh",
			"Fancy dress box (PoH)", items, java.util.Map.of(), 1L);
		assertTrue(ClueStashModule.doable(clue, module.owningView()));
		// the raw account view still says no — the storages are the difference
		assertFalse(ClueStashModule.doable(clue, state));
	}

	/** Filling a step's OWN unit satisfies that step — after a filling run
	 *  the deposited outfits are sealed (rightly unavailable for OTHER
	 *  units), but the filled steps must read COMPLETE, not "missing
	 *  items" (Luke's 2026-07-28 report after filling Beginner+Easy). */
	@Test
	public void filledUnitSatisfiesItsOwnStep()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStashModule module = module(state);
		ClueStepsPack.Clue clue = swampShack();
		ClueStepsPack.Stash unit = pack.stash.stream()
			.filter(u -> clue.id.equals(u.clueId)).findFirst().orElseThrow();

		// nothing owned, nothing filled: not satisfied
		assertFalse(module.satisfied(clue));
		// the outfit went INTO the unit: doable stays false (items sealed),
		// but the step is satisfied — its STASH holds the outfit
		state.setStashFilled(unit.objectId, true);
		assertFalse(ClueStashModule.doable(clue, module.owningView()));
		assertTrue(module.satisfied(clue));
	}

	/** An outfit SEALED INSIDE a STASH unit is not available for filling
	 *  another — Luke's 2026-07-28 report: the router asked him to strip
	 *  one STASH to dress the next. WMS mirrors filled units as family
	 *  "stash" storages; the owning view must skip exactly that family. */
	@Test
	public void outfitInsideAnotherStashDoesNotCount()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueStashModule module = module(state);
		ClueStepsPack.Clue clue = swampShack();
		java.util.Map<Integer, Integer> items = new java.util.HashMap<>();
		for (String raw : clue.reqs)
		{
			String alt = raw.startsWith("any:") ? raw.substring(4).split("\\|")[0] : raw;
			items.put(Integer.parseInt(alt.split(":")[1]), 1);
		}
		state.putStorageContents("stash:34742", "Some STASH unit", "stash",
			"Some STASH unit (STASH)", items, java.util.Map.of(), 1L);
		assertFalse(ClueStashModule.doable(clue, module.owningView()));
	}

	/** The tab plans routes on the EDT, and Actor.getWorldLocation()
	 *  ASSERTS the client thread (Luke's 2026-07-28 report: the assertion
	 *  killed rebuildContent — hero card, nothing else). routePlan must
	 *  therefore never touch the client; it reads the position cached by
	 *  onGameTick, which DOES run on the client thread. */
	@Test
	public void routePlanNeverTouchesTheClient()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		net.runelite.api.Player player = org.mockito.Mockito.mock(net.runelite.api.Player.class);
		org.mockito.Mockito.when(client.getLocalPlayer()).thenReturn(player);
		org.mockito.Mockito.when(player.getWorldLocation())
			.thenReturn(new net.runelite.api.coords.WorldPoint(3222, 3218, 0)); // Lumbridge
		ClueStashModule module = new ClueStashModule(state, config,
			new DataPack(new Gson()), new EventBus(), client, null, null);

		// no tick yet: planning must not reach for the client
		org.mockito.Mockito.verifyNoInteractions(client);
		assertEquals("Beginner", module.routePlan("Beginner").tier);
		org.mockito.Mockito.verifyNoInteractions(client);

		// a game tick caches the position; the planner orders from it
		module.onGameTick(null);
		StateFixture.bank(state, new java.util.HashMap<>(Map.of(
			1635, 1, 1654, 1, 1949, 1, 1007, 1, 1351, 1, 1061, 1)));
		// any client read AFTER the tick is the EDT crash again
		org.mockito.Mockito.when(client.getLocalPlayer())
			.thenThrow(new AssertionError("must be called on client thread"));
		StashRouter.Plan plan = module.routePlan("Beginner");
		assertEquals(34738, plan.route.get(0).unit.objectId); // Bob's Axes, Lumbridge
	}

	/** The router auto-paths: the FIRST plan posts the next stop to the
	 *  Shortest Path bridge, a fill advancing the route posts the new next
	 *  — and an unchanged next stop posts NOTHING on later rebuilds. */
	@Test
	public void autoRoutesTheNextStopAndOnlyOnChange() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.bank(state, Map.of(
			1635, 1, 1654, 1, 1949, 1, 1007, 1, 1351, 1, 1061, 1));
		EventBus bus = new EventBus();
		java.util.List<net.runelite.client.events.PluginMessage> posted = new java.util.ArrayList<>();
		bus.register(new Object()
		{
			@net.runelite.client.eventbus.Subscribe
			public void onPluginMessage(net.runelite.client.events.PluginMessage message)
			{
				posted.add(message);
			}
		});
		ClueStashModule module = new ClueStashModule(state, config, new DataPack(new Gson()),
			bus, null, null, new com.ironhub.integrations.ShortestPathBridge(bus, null));
		module.startUp();
		javax.swing.JComponent tab = module.buildTab();
		// routing is OPT-IN (Luke, 2026-07-28): opening the section posts NOTHING
		assertTrue(posted.isEmpty());
		((CluesTab) tab).startRouteForTest("Beginner"); // the tier button
		assertEquals(1, posted.size());
		assertEquals("path", posted.get(0).getName());
		// no player position: the route starts at the tier's first unit
		net.runelite.api.coords.WorldPoint target =
			(net.runelite.api.coords.WorldPoint) posted.get(0).getData().get("target");
		assertEquals(3206, target.getX()); // Gypsy tent entrance, Varrock

		// a rebuild with the same next stop posts nothing new
		((CluesTab) tab).openRouteMissingForTest();
		assertEquals(1, posted.size());

		// filling the routed unit advances the route — and re-routes
		state.setStashFilled(34736, true);
		((CluesTab) tab).openRouteMissingForTest();
		assertEquals(2, posted.size());
		target = (net.runelite.api.coords.WorldPoint) posted.get(1).getData().get("target");
		assertEquals(3209, target.getX()); // Fine Clothes entrance next door
		module.shutDown();
	}

	/** "Follow a set route unless skipped" (Luke, 2026-07-28): the NN
	 *  order freezes when the tier starts — MOVING never re-points the
	 *  router, and a fill advances to the frozen next even when a fresh
	 *  nearest-neighbour pass would now pick a different unit. */
	@Test
	public void frozenRouteSurvivesMovement() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.bank(state, Map.of(
			1635, 1, 1654, 1, 1949, 1, 1007, 1, 1351, 1, 1061, 1));
		EventBus bus = new EventBus();
		java.util.List<net.runelite.client.events.PluginMessage> posted = new java.util.ArrayList<>();
		bus.register(new Object()
		{
			@net.runelite.client.eventbus.Subscribe
			public void onPluginMessage(net.runelite.client.events.PluginMessage message)
			{
				posted.add(message);
			}
		});
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		net.runelite.api.Player player = org.mockito.Mockito.mock(net.runelite.api.Player.class);
		org.mockito.Mockito.when(client.getLocalPlayer()).thenReturn(player);
		org.mockito.Mockito.when(player.getWorldLocation())
			.thenReturn(new net.runelite.api.coords.WorldPoint(3222, 3218, 0)); // Lumbridge
		ClueStashModule module = new ClueStashModule(state, config, new DataPack(new Gson()),
			bus, client, null, new com.ironhub.integrations.ShortestPathBridge(bus, null));
		module.startUp();
		module.onGameTick(null);
		javax.swing.JComponent tab = module.buildTab();
		assertTrue(posted.isEmpty()); // opt-in: nothing until the button
		((CluesTab) tab).startRouteForTest("Beginner");
		// from Lumbridge the frozen order is Bob's Axes → Fine Clothes → Gypsy
		assertEquals(1, posted.size());
		net.runelite.api.coords.WorldPoint target =
			(net.runelite.api.coords.WorldPoint) posted.get(0).getData().get("target");
		assertEquals(3233, target.getX()); // Bob's Axes

		// walk to Varrock square: a fresh NN pass would now pick Gypsy —
		// the frozen route must not re-point
		org.mockito.Mockito.when(player.getWorldLocation())
			.thenReturn(new net.runelite.api.coords.WorldPoint(3212, 3422, 0));
		module.onGameTick(null);
		((CluesTab) tab).openRouteMissingForTest();
		assertEquals(1, posted.size());

		// filling Bob's advances to the FROZEN next (Fine Clothes), not the
		// now-nearest Gypsy tent
		state.setStashFilled(34738, true);
		((CluesTab) tab).openRouteMissingForTest();
		assertEquals(2, posted.size());
		target = (net.runelite.api.coords.WorldPoint) posted.get(1).getData().get("target");
		assertEquals(3209, target.getX()); // Fine Clothes entrance
		module.shutDown();
	}

	@Test
	public void tabRendersBothViewsHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		// swamp-shack outfit pieces + the full Beginner outfits so the
		// router card has ready stops to march through
		StateFixture.bank(state, Map.of(1205, 1, 1153, 1,
			1635, 1, 1654, 1, 1949, 1, 1007, 1, 1351, 1, 1061, 1));
		ClueStashModule module = module(state);
		module.startUp();

		ClueStepsPack.Clue clue = swampShack();
		ClueStepsPack.Stash unit = pack.stash.stream()
			.filter(u -> clue.id.equals(u.clueId)).findFirst().orElseThrow();
		state.setStashBuilt(unit.objectId, true);
		state.setStashFilled(pack.stash.get(5).objectId, true);

		JComponent tab = module.buildTab();
		assertNotNull(tab);
		// ONE combined view since 2026-07-28: open a tier and a step so the
		// render shows the grammar (icons, dots, well, mark-filled action);
		// start a Beginner route so the router card renders too
		((CluesTab) tab).startRouteForTest("Beginner");
		((CluesTab) tab).expandTierForTest(clue.tier);
		((CluesTab) tab).expandStepForTest(clue.id);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue("render too small", image.getHeight() > 150);
		java.io.File out = new java.io.File("build/reports/clues-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);

		// the router card with its missing-for-tier Well open
		((CluesTab) tab).openRouteMissingForTest();
		javax.imageio.ImageIO.write(SwingRender.render((JPanel) tab), "png",
			new java.io.File("build/reports/clues-router.png"));
		module.shutDown();
	}
}
