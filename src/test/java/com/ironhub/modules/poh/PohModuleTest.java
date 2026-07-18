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
		assertTrue(pack.spaces.size() >= 12);
		java.util.Set<String> tierIds = new java.util.HashSet<>();
		java.util.Set<Integer> objectIds = new java.util.HashSet<>();
		for (PohPack.Space space : pack.spaces)
		{
			int lastLevel = 0;
			for (PohPack.Tier tier : space.tiers)
			{
				assertTrue("duplicate tier id " + tier.id, tierIds.add(tier.id));
				assertFalse(tier.id + " has no object ids", tier.objectIds.isEmpty());
				for (Integer id : tier.objectIds)
				{
					assertTrue(tier.id + " reuses object id " + id, objectIds.add(id));
				}
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
			.filter(s -> s.id.equals("pool")).findFirst().orElseThrow();
		assertEquals(5, pool.tiers.size());
		assertEquals(65, pool.tiers.get(0).level);
		assertEquals(90, pool.tiers.get(4).level);
		PohPack.Space box = pack.spaces.stream()
			.filter(s -> s.id.equals("jewellery_box")).findFirst().orElseThrow();
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
			.filter(s -> s.id.equals("jewellery_box")).findFirst().orElseThrow()
			.tiers.get(2);

		// spawn seen BEFORE the welcome message buffers, never marks
		spawnObject(module, ornate.objectIds.get(0));
		assertFalse(state.isPohBuilt(ornate.id));
		assertEquals(1, module.pendingTiers().size());

		// a friend's house: no welcome message, next load clears the buffer
		module.onGameStateChanged(loading());
		assertEquals(0, module.pendingTiers().size());
		assertFalse(state.isPohBuilt(ornate.id));

		// own house: spawn then the welcome message commits the buffer
		spawnObject(module, ornate.objectIds.get(0));
		module.onChatMessage(welcome());
		assertTrue(state.isPohBuilt(ornate.id));

		// once confirmed, further spawns commit live (building mode swaps)
		PohPack.Tier fancy = pack.spaces.stream()
			.filter(s -> s.id.equals("jewellery_box")).findFirst().orElseThrow()
			.tiers.get(1);
		spawnObject(module, fancy.objectIds.get(0));
		assertTrue(state.isPohBuilt(fancy.id));
		module.shutDown();
	}

	@Test
	public void ladderStatusAndManualMark()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		PohPack.Space pool = pack.spaces.stream()
			.filter(s -> s.id.equals("pool")).findFirst().orElseThrow();

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
			.filter(s -> s.id.equals("jewellery_box")).findFirst().orElseThrow()
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
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.toggleBuilt(pack.spaces.get(0).tiers.get(0)); // restoration pool built
			module.toggleBuilt(pack.spaces.get(0).tiers.get(1));
			module.toggleGoal(pack.spaces.get(0).tiers.get(3)); // track a later tier
			tab.expand("pool");
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 150);
		java.io.File out = new java.io.File("build/reports/poh-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}

	private static void spawnObject(PohModule module, int objectId)
	{
		net.runelite.api.GameObject object = org.mockito.Mockito.mock(net.runelite.api.GameObject.class);
		org.mockito.Mockito.when(object.getId()).thenReturn(objectId);
		net.runelite.api.events.GameObjectSpawned event = new net.runelite.api.events.GameObjectSpawned();
		event.setGameObject(object);
		module.onGameObjectSpawned(event);
	}

	private static net.runelite.api.events.GameStateChanged loading()
	{
		net.runelite.api.events.GameStateChanged event = new net.runelite.api.events.GameStateChanged();
		event.setGameState(net.runelite.api.GameState.LOADING);
		return event;
	}

	private static ChatMessage welcome()
	{
		return new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"Welcome to your house.", "", 0);
	}
}
