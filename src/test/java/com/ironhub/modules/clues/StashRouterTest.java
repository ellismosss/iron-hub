package com.ironhub.modules.clues;

import com.google.gson.Gson;
import com.ironhub.data.ClueStepsPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The STASH stocking router: active-tier selection, nearest-neighbour
 * ordering, and the aggregated missing items + build materials. The three
 * Beginner units (Gypsy tent 34736, Fine Clothes 34737 — Varrock; Bob's
 * Axes 34738 — Lumbridge) are the fixtures.
 */
public class StashRouterTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final ClueStepsPack pack = new DataPack(new Gson()).load("clue-steps", ClueStepsPack.class);

	/** The full Beginner outfit set: both Varrock steps' pairs + Bob's. */
	private static final Map<Integer, Integer> BEGINNER_OUTFITS = Map.of(
		1635, 1, 1654, 1,   // gold ring + necklace (Gypsy tent)
		1949, 1, 1007, 1,   // chef's hat + red cape (Fine Clothes)
		1351, 1, 1061, 1);  // bronze axe + leather boots (Bob's Axes)

	private StashRouter.Plan plan(AccountState state, WorldPoint from)
	{
		// AccountState IS a StateView; the module's storage-aware wrapper
		// only widens ownership, which these fixtures put in the bank anyway
		return StashRouter.plan(pack, state, state, from);
	}

	@Test
	public void beginnerIsTheActiveTierUntilFilled()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StashRouter.Plan plan = plan(state, null);
		assertEquals("Beginner", plan.tier);
		assertEquals(3, plan.units);
		assertEquals(0, plan.filled);
		assertEquals(3, plan.unbuilt);

		// filling all three advances the router to Easy — one tier at a time
		state.setStashFilled(34736, true);
		state.setStashFilled(34737, true);
		state.setStashFilled(34738, true);
		plan = plan(state, null);
		assertEquals("Easy", plan.tier);
		assertEquals(0, plan.filled);
	}

	@Test
	public void routeOrdersNearestFirst()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, BEGINNER_OUTFITS);
		// standing in Lumbridge: Bob's Axes (3233,3200) beats Varrock
		StashRouter.Plan plan = plan(state, new WorldPoint(3222, 3218, 0));
		assertEquals(3, plan.route.size());
		assertTrue(plan.waiting.isEmpty());
		assertEquals(34738, plan.route.get(0).unit.objectId);
		// then the two Varrock units, chained nearest-to-nearest
		assertTrue(plan.route.get(0).distance >= 0);
		assertTrue(plan.route.get(1).distance >= 0);

		// standing on Varrock square the order flips
		plan = plan(state, new WorldPoint(3212, 3422, 0));
		assertEquals(34736, plan.route.get(0).unit.objectId);
	}

	@Test
	public void loadoutListsTheWholeTierAndMissingListsBuildMaterials()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StashRouter.Plan plan = plan(state, null);
		assertTrue(plan.route.isEmpty());
		assertEquals(3, plan.waiting.size());
		// the loadout is EVERY item the tier wants — all six Beginner
		// pieces, A-Z like the bank withdrawal it is
		assertEquals(6, plan.loadout.size());
		for (int i = 1; i < plan.loadout.size(); i++)
		{
			assertTrue("loadout must be A-Z", String.CASE_INSENSITIVE_ORDER.compare(
				plan.loadout.get(i - 1).label, plan.loadout.get(i).label) <= 0);
		}
		assertTrue(plan.loadout.stream().anyMatch(l ->
			l.label.equals("Gold ring") && l.needed == 1 && l.have == 0 && !l.met()));
		assertTrue(plan.loadout.stream().anyMatch(l -> l.label.equals("Bronze axe")));
		// missing carries the build side
		assertTrue(plan.missing.stream().anyMatch(l -> l.contains("Level 12 Construction")));
		assertTrue(plan.missing.stream().anyMatch(l -> l.contains("6 planks")));
		assertTrue(plan.missing.stream().anyMatch(l -> l.contains("30 nails")));
		assertTrue(plan.missing.stream().anyMatch(l -> l.contains("A hammer")));
		assertTrue(plan.missing.stream().anyMatch(l -> l.contains("A saw")));
	}

	/** Filling a STASH keeps the outfit inside it — two Easy steps wearing
	 *  a gold ring need TWO gold rings, and one owned is only 1/2. */
	@Test
	public void sharedItemsCountPerStep()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		for (com.ironhub.data.ClueStepsPack.Stash unit : pack.stash)
		{
			if ("Beginner".equals(unit.tier))
			{
				state.setStashFilled(unit.objectId, true);
			}
		}
		StateFixture.bank(state, Map.of(1635, 1)); // one gold ring
		StashRouter.Plan plan = plan(state, null);
		assertEquals("Easy", plan.tier);
		StashRouter.Loadout ring = plan.loadout.stream()
			.filter(l -> l.label.equals("Gold ring")).findFirst().orElseThrow();
		assertEquals(2, ring.needed);
		assertEquals(1, ring.have);
		assertFalse(ring.met());
	}

	@Test
	public void stockedBuildMaterialsDropOut()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 42, 50_000);
		StateFixture.bank(state, Map.of(ItemID.WOODPLANK, 10,
			ItemID.NAILS_BRONZE, 100, ItemID.HAMMER, 1, ItemID.POH_SAW, 1));
		StashRouter.Plan plan = plan(state, null);
		assertEquals("Beginner", plan.tier);
		assertTrue("only build lines live in missing", plan.missing.isEmpty());
		// the outfits are still wanted by the loadout
		assertTrue(plan.loadout.stream().anyMatch(l ->
			l.label.equals("Gold ring") && !l.met()));
	}

	/** 10 nails must be ONE metal: 6 bronze + 4 iron builds nothing. */
	@Test
	public void mixedNailStacksDoNotCover()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.setStashFilled(34736, true);
		state.setStashFilled(34737, true); // one unbuilt unit left
		StateFixture.bank(state, Map.of(ItemID.NAILS_BRONZE, 6, ItemID.NAILS_IRON, 4));
		StashRouter.Plan plan = plan(state, null);
		assertTrue(plan.missing.stream().anyMatch(l ->
			l.contains("4 nails of one metal to build (6/10)")));
	}

	@Test
	public void filledUnitLeavesTheRoute()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, BEGINNER_OUTFITS);
		state.setStashFilled(34738, true);
		StashRouter.Plan plan = plan(state, new WorldPoint(3222, 3218, 0));
		assertEquals(1, plan.filled);
		assertEquals(2, plan.route.size());
		assertTrue(plan.route.stream().noneMatch(s -> s.unit.objectId == 34738));
	}

	/** Master is the only tier that also asks for gold leaf. */
	@Test
	public void masterBuildAsksForGoldLeaf()
	{
		assertEquals(0, StashRouter.BUILDS.get("Elite").goldLeaves);
		assertEquals(1, StashRouter.BUILDS.get("Master").goldLeaves);
		assertEquals(88, StashRouter.BUILDS.get("Master").level);
		assertEquals(12, StashRouter.BUILDS.get("Beginner").level);
	}
}
