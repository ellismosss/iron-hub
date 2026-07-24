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
import net.runelite.api.Skill;
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
		return new PohModule(state, config, new DataPack(new Gson()), new EventBus(), null, null);
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

	/**
	 * Building mode is the ownership proof (Luke's call): you can only enter
	 * it in your OWN house, so a house you are visiting never marks anything.
	 * Read as non-zero rather than == 1 — the constant's name is authoritative
	 * but its truthy value is not confirmed anywhere available, and guessing a
	 * specific value is how the previous gate failed.
	 */
	@Test
	public void buildingModeIsTheOwnershipProof()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);

		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		PohModule module = new PohModule(state, config, new DataPack(new Gson()),
			new EventBus(), client, null);

		org.mockito.Mockito.when(client.getVarbitValue(2176)).thenReturn(0);
		assertFalse("visiting a house must never count", module.buildingMode());

		org.mockito.Mockito.when(client.getVarbitValue(2176)).thenReturn(1);
		assertTrue(module.buildingMode());

		// no client at all (headless) must not claim ownership
		assertFalse(module(state).buildingMode());
	}

	/** The sweep must read all FOUR object kinds: rugs arrive as ground
	 *  objects and mounted heads / wall charts as decorative and wall ones, so
	 *  the GameObject-only reader this replaced could never see them. */
	@Test
	public void theSweepReadsEveryObjectKind()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();

		module.scanTileForTest(tile(0,
			new int[]{6752},   // game object    — crude wooden chair (Parlour)
			6759,              // ground object  — brown rug
			0, 0));
		module.scanTileForTest(tile(1,
			new int[]{13148},  // game object    — wooden bed (Bedroom)
			0,
			6762,              // wall object    — a rug id, stands in for wall furniture
			0));
		assertEquals("every kind should be buffered", 4, module.pendingObjects().size());
		module.commitForTest();

		assertTrue(state.isPohBuilt("parlour__chairs:crude_wooden_chair"));
		assertTrue("a rug is a GROUND object — invisible to the old reader",
			state.isPohBuilt("parlour__rug:brown_rug"));
		assertTrue(state.isPohBuilt("bedroom__bed:wooden_bed"));
		module.shutDown();
	}

	/**
	 * A portal only carries POH_PORTAL_&lt;wood&gt;_EMPTY while it has no
	 * destination set. Choose one and the game swaps in a per-destination
	 * object (POH_PORTAL_TEAK_VARROCK and 46 others), so every portal anyone
	 * actually uses was invisible to detection until gen_poh.py absorbed those
	 * variants from the client's own symbol table.
	 */
	@Test
	public void aConfiguredPortalIsStillARecognisedPortal()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();

		// 13615 = POH_PORTAL_TEAK_VARROCK: a teak portal set to Varrock
		module.scanTileForTest(tile(0, new int[]{13615}, 0, 0, 0));
		module.commitForTest();
		assertTrue("a teak portal with a destination is a built teak portal",
			state.isPohBuilt("portal_chamber__portals:teak_portal"));

		// and the Leagues reskin must not be claimed by the base furniture:
		// the wiki gave Marble and Raging echoes the same ids, so either one
		// marked BOTH built and the tab reported the reskin to normal players
		assertFalse("a marble portal is not a Raging echoes portal",
			state.isPohBuilt("portal_chamber__portals:raging_echoes_portal"));
		module.shutDown();
	}

	/**
	 * The game builds the IDENTICAL object for a rug in a parlour and a rug in
	 * a bedroom, so the object id alone cannot say which hotspot was built. A
	 * POH room is one 8x8 chunk, so the furniture a rug shares its room with
	 * is what attributes it.
	 */
	@Test
	public void sharedFurnitureIsAttributedToTheRoomItIsIn()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		module.startUp();

		int brownRug = 6759;          // Parlour, Bedroom, Chapel, Portal nexus
		int crudeChair = 6752;        // Parlour only
		int woodenBed = 13148;        // Bedroom only

		module.scanTileForTest(tile(0, new int[]{crudeChair, brownRug}, 0, 0, 0));
		module.scanTileForTest(tile(1, new int[]{woodenBed}, 0, 0, 0));
		module.commitForTest();

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

		module.scanTileForTest(tile(0, new int[]{6759}, 0, 0, 0)); // a rug, alone
		module.commitForTest();

		assertFalse(state.isPohBuilt("parlour__rug:brown_rug"));
		assertFalse(state.isPohBuilt("bedroom__rug:brown_rug"));
		module.shutDown();
	}

	/** End to end: a sweep detects what is built AND the sidebar redraws
	 *  itself off the state listener (Luke's ask). */
	@Test
	public void detectionUpdatesTheSidebar() throws Exception
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

		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.scanTileForTest(tile(0, new int[]{6752, 6759}, 0, 0, 0));
			module.commitForTest();
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain the rebuild

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

	/**
	 * A hotspot holds ONE piece of furniture — a Gilded altar replaces the Oak
	 * altar rather than stacking on it — so building only the TOP tier is the
	 * normal case, not an edge case. nextTier used to answer "the first
	 * unbuilt tier from the bottom", which for a Gilded altar was "Oak altar":
	 * every hotspot then read as unbuilt forever and the tab showed 0/137 with
	 * no green ticks even when detection had marked everything correctly.
	 * ladderStatusAndManualMark missed it by only ever building bottom-up.
	 */
	@Test
	public void onlyTheTopTierBuiltStillReadsAsBuilt()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PohModule module = module(state);
		PohPack.Space altar = pack.spaces.stream()
			.filter(s -> s.id.equals("chapel__altar")).findFirst().orElseThrow();
		assertTrue("the altar ladder should have several tiers", altar.tiers.size() > 2);

		PohPack.Tier top = altar.tiers.get(altar.tiers.size() - 1);
		module.toggleBuilt(top);   // a Gilded altar, and nothing below it

		assertTrue("anything standing at the hotspot means built", module.isBuilt(altar));
		assertEquals("the built tier is the one that exists", top, module.builtTier(altar));
		assertNull("the top tier is up, so there is nothing to upgrade to",
			module.nextTier(altar));

		// and one tier down: the next upgrade is the tier ABOVE it, never tier 0
		module.toggleBuilt(top);
		PohPack.Tier below = altar.tiers.get(altar.tiers.size() - 2);
		module.toggleBuilt(below);
		assertTrue(module.isBuilt(altar));
		assertEquals(top, module.nextTier(altar));
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
		module.scanTileForTest(tile(0, new int[]{ornate.objectIds.get(0)}, 0, 0, 0));
		module.commitForTest();
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

		// nothing synced yet: the tab must say how to sync rather than just
		// showing an empty 0/137 grid (which read as "broken")
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		javax.imageio.ImageIO.write(SwingRender.render(tab), "png",
			new java.io.File("build/reports/house-unsynced.png"));

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

	/**
	 * A mocked scene tile in house room {@code room} — a POH room is one 8x8
	 * chunk, so the room index just shifts the world coordinates by 8. Pass 0
	 * for an object kind the tile does not have.
	 */
	private static net.runelite.api.Tile tile(int room, int[] gameObjects,
		int groundObject, int wallObject, int decorativeObject)
	{
		net.runelite.api.Tile tile = org.mockito.Mockito.mock(net.runelite.api.Tile.class);
		org.mockito.Mockito.when(tile.getWorldLocation()).thenReturn(
			new net.runelite.api.coords.WorldPoint(7000 + room * 8, 7000, 0));
		net.runelite.api.GameObject[] objects =
			new net.runelite.api.GameObject[gameObjects.length];
		for (int i = 0; i < gameObjects.length; i++)
		{
			net.runelite.api.GameObject object =
				org.mockito.Mockito.mock(net.runelite.api.GameObject.class);
			org.mockito.Mockito.when(object.getId()).thenReturn(gameObjects[i]);
			objects[i] = object;
		}
		org.mockito.Mockito.when(tile.getGameObjects()).thenReturn(objects);
		if (groundObject > 0)
		{
			net.runelite.api.GroundObject ground =
				org.mockito.Mockito.mock(net.runelite.api.GroundObject.class);
			org.mockito.Mockito.when(ground.getId()).thenReturn(groundObject);
			org.mockito.Mockito.when(tile.getGroundObject()).thenReturn(ground);
		}
		if (wallObject > 0)
		{
			net.runelite.api.WallObject wall =
				org.mockito.Mockito.mock(net.runelite.api.WallObject.class);
			org.mockito.Mockito.when(wall.getId()).thenReturn(wallObject);
			org.mockito.Mockito.when(tile.getWallObject()).thenReturn(wall);
		}
		if (decorativeObject > 0)
		{
			net.runelite.api.DecorativeObject decor =
				org.mockito.Mockito.mock(net.runelite.api.DecorativeObject.class);
			org.mockito.Mockito.when(decor.getId()).thenReturn(decorativeObject);
			org.mockito.Mockito.when(tile.getDecorativeObject()).thenReturn(decor);
		}
		return tile;
	}
}
