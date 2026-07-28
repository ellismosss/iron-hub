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
		// render shows the grammar (icons, dots, well, mark-filled action)
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
