package com.ironhub.modules.poh;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.PohPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PohModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final PohPack pack = new DataPack(new Gson()).load("poh", PohPack.class);

	private PohModule module(AccountState state)
	{
		return new PohModule(state, config, new DataPack(new Gson()), new EventBus(), null);
	}

	@Test
	public void packIntegrity()
	{
		assertTrue(pack.spaces.size() >= 100);   // the full 24-room catalog
		java.util.Set<String> tierIds = new java.util.HashSet<>();
		for (PohPack.Space space : pack.spaces)
		{
			int lastLevel = 0;
			for (PohPack.Tier tier : space.tiers)
			{
				assertTrue("duplicate tier id " + tier.id, tierIds.add(tier.id));
				assertFalse(tier.id + " has no object ids", tier.objectIds.isEmpty());
				// object ids are NOT globally unique: shared furniture (a rug, a
				// fireplace) is buildable in several rooms' hotspots, so the same
				// built object appears as a tier in each.
				for (String req : tier.reqs)
				{
					assertFalse(tier.id + " req is manual: " + req,
						Requirements.isManual(Requirements.parse(req)));
				}
				// ladders never regress in Construction level
				assertTrue(tier.id + " level goes backwards", tier.level >= lastLevel);
				lastLevel = tier.level;
			}
		}
		// anchors: the famous levels
		PohPack.Space pool = pack.spaces.stream()
			.filter(s -> s.id.equals("superior_garden__pool")).findFirst().orElseThrow();
		assertEquals(5, pool.tiers.size());
		assertEquals(65, pool.tiers.get(0).level);
		assertEquals(90, pool.tiers.get(4).level);
		PohPack.Space box = pack.spaces.stream()
			.filter(s -> s.id.equals("achievement_gallery__jewellery_box")).findFirst().orElseThrow();
		assertEquals(91, box.tiers.get(2).level);
	}

	@Test
	public void detectionCommitsOnlyInOwnHouse()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();
		PohPack.Tier ornate = pack.spaces.stream()
			.filter(s -> s.id.equals("achievement_gallery__jewellery_box")).findFirst().orElseThrow()
			.tiers.get(2);

		// spawn seen BEFORE the welcome message buffers, never marks
		spawnObject(module, ornate.objectIds.get(0));
		assertFalse(state.isPohBuilt(ornate.id));
		assertEquals(1, module.pendingObjects().size());

		// a friend's house: no welcome message, next load clears the buffer
		module.onGameStateChanged(loading());
		assertEquals(0, module.pendingObjects().size());
		assertFalse(state.isPohBuilt(ornate.id));

		// own house: spawn then the welcome message commits the buffer
		spawnObject(module, ornate.objectIds.get(0));
		module.onChatMessage(welcome());
		assertTrue(state.isPohBuilt(ornate.id));

		// once confirmed, further spawns commit live (building mode swaps)
		PohPack.Tier fancy = pack.spaces.stream()
			.filter(s -> s.id.equals("achievement_gallery__jewellery_box")).findFirst().orElseThrow()
			.tiers.get(1);
		spawnObject(module, fancy.objectIds.get(0));
		assertTrue(state.isPohBuilt(fancy.id));
		module.shutDown();
	}

	/** The REAL client sequence entering a house: LOADING → furniture spawns
	 *  during the load → LOGGED_IN fires after it → THEN the welcome chat.
	 *  Clearing the buffer on LOGGED_IN wiped every buffered spawn moments
	 *  before confirmation — detection never marked anything in-client
	 *  (Luke's report, 2026-07-23). */
	@Test
	public void detectionSurvivesTheLoggedInAfterLoading()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();
		PohPack.Tier ornate = pack.spaces.stream()
			.filter(s -> s.id.equals("achievement_gallery__jewellery_box")).findFirst().orElseThrow()
			.tiers.get(2);

		module.onGameStateChanged(loading());
		spawnObject(module, ornate.objectIds.get(0));
		module.onGameStateChanged(stateChange(net.runelite.api.GameState.LOGGED_IN));
		assertEquals("LOGGED_IN after a load must not wipe the buffer",
			1, module.pendingObjects().size());
		module.onChatMessage(welcome());
		assertTrue(state.isPohBuilt(ornate.id));

		// logging out fully still resets the confirmation
		module.onGameStateChanged(stateChange(net.runelite.api.GameState.LOGIN_SCREEN));
		assertEquals(0, module.pendingObjects().size());
		module.shutDown();
	}

	/**
	 * The game builds the IDENTICAL object for a rug in a parlour and a rug in
	 * a bedroom, so the object id alone cannot say which hotspot was built. A
	 * POH room is one 8x8 chunk, so the furniture a rug shares its room with
	 * is what attributes it — otherwise one rug marked every room's rug built
	 * (or, before that, one arbitrary room's).
	 */
	@Test
	public void sharedFurnitureIsAttributedToTheRoomItIsIn()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();

		int brownRug = 6759;          // buildable in Parlour, Bedroom, Chapel, Portal nexus
		int crudeChair = 6752;        // Parlour only
		int woodenBed = 13148;        // Bedroom only

		// room 0 is the parlour (a chair and a rug), room 1 the bedroom (a bed)
		spawnObject(module, crudeChair, 0);
		spawnObject(module, brownRug, 0);
		spawnObject(module, woodenBed, 1);
		module.onChatMessage(welcome());

		assertTrue(state.isPohBuilt("parlour__chairs:crude_wooden_chair"));
		assertTrue(state.isPohBuilt("bedroom__bed:wooden_bed"));
		assertTrue("the rug is in the parlour", state.isPohBuilt("parlour__rug:brown_rug"));
		assertFalse("the bedroom has no rug — never mark one there",
			state.isPohBuilt("bedroom__rug:brown_rug"));
		assertFalse("there is no chapel at all", state.isPohBuilt("chapel__rug:brown_rug"));
		module.shutDown();
	}

	/** A room holding nothing but shared furniture cannot be identified, so it
	 *  marks nothing rather than guessing — the manual mark is the escape
	 *  hatch (the honesty rule: never invent what we cannot read). */
	@Test
	public void unidentifiableRoomMarksNothingRatherThanGuessing()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();

		spawnObject(module, 6759, 0);   // a brown rug, alone in its room
		module.onChatMessage(welcome());

		assertFalse(state.isPohBuilt("parlour__rug:brown_rug"));
		assertFalse(state.isPohBuilt("bedroom__rug:brown_rug"));
		module.shutDown();
	}

	/** End to end: walking into your house detects what is built AND the
	 *  sidebar redraws itself off the state listener (Luke's ask). */
	@Test
	public void enteringTheHouseUpdatesTheSidebar() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.CONSTRUCTION, 84, 3_000_000);
		PohModule module = module(state);
		module.startUp();
		PohTab tab = (PohTab) module.buildTab();
		javax.swing.SwingUtilities.invokeAndWait(() -> tab.expand("parlour__chairs"));
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage before = SwingRender.render(tab);

		// the house loads its furniture, then the game confirms it is ours
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.onGameStateChanged(loading());
			spawnObject(module, 6752, 0);    // crude wooden chair, parlour
			spawnObject(module, 6759, 0);    // brown rug, same room
			module.onChatMessage(welcome());
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain the queued rebuild

		assertTrue(state.isPohBuilt("parlour__chairs:crude_wooden_chair"));
		BufferedImage after = SwingRender.render(tab);
		assertFalse("the sidebar must redraw when detection marks something",
			sameImage(before, after));
		module.shutDown();
	}

	private static boolean sameImage(BufferedImage a, BufferedImage b)
	{
		if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
		{
			return false;
		}
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}

	@Test
	public void ladderStatusAndManualMark()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		PohPack.Space pool = pack.spaces.stream()
			.filter(s -> s.id.equals("superior_garden__pool")).findFirst().orElseThrow();

		assertNull(module.builtTier(pool));
		assertEquals(pool.tiers.get(0), module.nextTier(pool));

		module.toggleBuilt(pool.tiers.get(0));
		module.toggleBuilt(pool.tiers.get(1));
		assertEquals(pool.tiers.get(1), module.builtTier(pool));
		assertEquals(pool.tiers.get(2), module.nextTier(pool));

		module.toggleBuilt(pool.tiers.get(1)); // unmark
		assertEquals(pool.tiers.get(0), module.builtTier(pool));
		module.shutDown();
	}

	@Test
	public void goalTrackingAndProof()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();
		PohPack.Tier ornate = pack.spaces.stream()
			.filter(s -> s.id.equals("achievement_gallery__jewellery_box")).findFirst().orElseThrow()
			.tiers.get(2);

		// track it: a poh: goal seed + selection, not yet achieved
		module.toggleGoal(ornate);
		assertTrue(module.isGoal(ornate));
		assertTrue(state.getSelectedGoals().contains("poh:" + ornate.id));
		com.ironhub.data.GoalsPack.Goal goal = com.ironhub.modules.goals.GoalPlannerModule
			.toGoal(state.getGoalSeeds().get("poh:" + ornate.id));
		assertEquals("poh:" + ornate.id, goal.getId());
		assertFalse(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));

		// building it in-game marks the pohtier_ proof → achieved
		spawnObject(module, ornate.objectIds.get(0));
		module.onChatMessage(welcome());
		assertTrue(state.isPohBuilt(ornate.id));
		assertTrue(state.isUnlocked(com.ironhub.state.GoalSeeds.pohProofKey(ornate.id)));
		assertTrue(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));

		// untracking retires the seed and selection
		module.toggleGoal(ornate);
		assertFalse(state.getGoalSeeds().containsKey("poh:" + ornate.id));
		assertFalse(state.getSelectedGoals().contains("poh:" + ornate.id));
		module.shutDown();
	}

	/** A tier already built when the goal is added lands its proof at add time. */
	@Test
	public void goalOnAnAlreadyBuiltTierProvesImmediately()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();
		PohPack.Tier tier = pack.spaces.get(0).tiers.get(0);
		module.toggleBuilt(tier); // built before the goal exists

		module.toggleGoal(tier);
		assertTrue(state.isUnlocked(com.ironhub.state.GoalSeeds.pohProofKey(tier.id)));
		assertTrue(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(
			com.ironhub.modules.goals.GoalPlannerModule.toGoal(
				state.getGoalSeeds().get("poh:" + tier.id)), state));
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.CONSTRUCTION, 84, 3_000_000);
		PohModule module = module(state);
		module.startUp();
		PohTab tab = (PohTab) module.buildTab();
		assertNotNull(tab);
		PohPack.Space pool = pack.spaces.stream()
			.filter(s -> s.id.equals("superior_garden__pool")).findFirst().orElseThrow();
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.toggleBuilt(pool.tiers.get(0)); // restoration pool built
			module.toggleBuilt(pool.tiers.get(1));
			module.toggleGoal(pool.tiers.get(3)); // track a later tier
			tab.expand("superior_garden__pool");
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 150);
		java.io.File out = new java.io.File("build/reports/house-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}

	private static void spawnObject(PohModule module, int objectId)
	{
		spawnObject(module, objectId, 0);
	}

	/** Spawn an object in house room {@code room} — a POH room is one 8x8
	 *  chunk, so the room index just shifts the world coordinates by 8. */
	private static void spawnObject(PohModule module, int objectId, int room)
	{
		net.runelite.api.GameObject object = org.mockito.Mockito.mock(net.runelite.api.GameObject.class);
		org.mockito.Mockito.when(object.getId()).thenReturn(objectId);
		org.mockito.Mockito.when(object.getWorldLocation()).thenReturn(
			new net.runelite.api.coords.WorldPoint(7000 + room * 8, 7000, 0));
		net.runelite.api.events.GameObjectSpawned event = new net.runelite.api.events.GameObjectSpawned();
		event.setGameObject(object);
		module.onGameObjectSpawned(event);
	}

	private static net.runelite.api.events.GameStateChanged loading()
	{
		return stateChange(net.runelite.api.GameState.LOADING);
	}

	private static net.runelite.api.events.GameStateChanged stateChange(net.runelite.api.GameState gs)
	{
		net.runelite.api.events.GameStateChanged event = new net.runelite.api.events.GameStateChanged();
		event.setGameState(gs);
		return event;
	}

	private static ChatMessage welcome()
	{
		return new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"Welcome to your house.", "", 0);
	}
}
