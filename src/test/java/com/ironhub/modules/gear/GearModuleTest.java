package com.ironhub.modules.gear;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.GearLaddersPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.components.GridTile;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GearModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final GearLaddersPack pack =
		new DataPack(new Gson()).load("gear-ladders", GearLaddersPack.class);

	@Test
	public void ownedThenFirstMetIsNextRestLocked()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(1323, 1)); // iron scimitar owned
		StateFixture.stat(state, Skill.ATTACK, 60, 0);

		List<GearLaddersPack.Rung> weapon = pack.getStyles().get(0).getSlots().get(0).getLadder();
		List<GridTile.State> states = GearProgressionModule.ladderStates(state, weapon);
		assertEquals(GridTile.State.OWNED, states.get(0));  // iron scim
		assertEquals(GridTile.State.NEXT, states.get(1));   // rune scim (40 atk met)
		assertEquals(GridTile.State.LOCKED, states.get(2)); // d scim (quest unmet)
		assertEquals(GridTile.State.LOCKED, states.get(3)); // whip (70 + kc unmet)
	}

	@Test
	public void obtainedSuccessorsImplyTheirPredecessors()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);

		// owning only an Ava's assembler (22109) proves the whole chain
		StateFixture.equipment(state, Map.of(22109, 1));
		java.util.Set<String> obtained = GearProgressionModule.obtainedNames(progression, state);
		assertTrue(obtained.contains("Ava's assembler"));
		assertTrue(obtained.contains("Ava's accumulator")); // consumed making it
		assertTrue(obtained.contains("Ava's attractor"));   // transitively
		assertTrue(!obtained.contains("Book of the dead"));

		// Book of the dead (25818) upgraded from Kharedst's memoirs
		StateFixture.bank(state, Map.of(25818, 1));
		obtained = GearProgressionModule.obtainedNames(progression, state);
		assertTrue(obtained.contains("Kharedst's memoirs"));
	}

	@Test
	public void mountedGloryRequiresOwningAGlory()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		com.ironhub.data.GearProgressionPack.Item mount = progression.getPhases().stream()
			.flatMap(p -> p.getGroups().stream())
			.flatMap(g -> g.getItems().stream())
			.filter(i -> i.getName().equals("Mounted amulet of glory"))
			.findFirst().orElseThrow();
		com.ironhub.requirements.Requirement req = com.ironhub.requirements.Requirements.allOf(
			mount.getRequirements().stream()
				.map(com.ironhub.requirements.Requirements::parse)
				.toArray(com.ironhub.requirements.Requirement[]::new));

		// 47 Construction alone is NOT enough — you must own a glory to mount
		StateFixture.stat(state, net.runelite.api.Skill.CONSTRUCTION, 47, 0);
		assertTrue(!req.isMet(state));
		// the missing line reads as the item name, not a raw id
		assertTrue(req.missing(state).stream()
			.anyMatch(r -> r.describe().equals("Amulet of glory")));

		StateFixture.bank(state, Map.of(1712, 1)); // glory(4) — variant counts
		assertTrue(req.isMet(state));
	}

	@Test
	public void everyIconFileIsBundled()
	{
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		progression.getPhases().forEach(p -> p.getGroups().forEach(g ->
			g.getItems().forEach(i ->
			{
				if (i.getIconFile() != null)
				{
					assertTrue("missing bundled icon: " + i.getIconFile() + " (" + i.getName() + ")",
						getClass().getResource("/data/icons/" + i.getIconFile()) != null);
				}
			})));
	}

	@Test
	public void everyImpliedNameResolvesToAnEntry()
	{
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		java.util.Set<String> names = new java.util.HashSet<>();
		progression.getPhases().forEach(p -> p.getGroups().forEach(g ->
			g.getItems().forEach(i -> names.add(i.getName()))));
		progression.getPhases().forEach(p -> p.getGroups().forEach(g ->
			g.getItems().forEach(i ->
			{
				if (i.getImplies() != null)
				{
					// a typo'd implies target would silently never fire
					assertTrue("unknown implies target on " + i.getName() + ": " + i.getImplies(),
						names.containsAll(i.getImplies()));
				}
			})));
	}

	/**
	 * Tracking a library item makes a GEAR goal, not a Supplies one, named
	 * for the item alone, whose obtain step routes through {@code item:} so
	 * the engine decomposes it into real tasks (Luke: "Stock 1 × Tumeken's
	 * shadow" / "Gather 1 × item 27277" was both the wrong family and the
	 * wrong decomposition).
	 */
	@Test
	public void trackingGearMakesAGearGoalWithRealObtainSteps()
	{
		int shadow = 27277;
		com.ironhub.state.PersistedState.GoalSeed seed = com.ironhub.state.GoalSeeds.gear(
			shadow, "Tumeken's shadow",
			List.of("quest:Beneath Cursed Sands", "skill:Magic:85"));

		assertEquals("gear", seed.family);
		assertEquals("gear:27277", seed.id);
		assertEquals("Tumeken's shadow", seed.name); // NOT "Stock 1 × ..."
		// the reqs became steps, then an obtain step routing through item:
		List<String> labels = new java.util.ArrayList<>();
		List<String> reqs = new java.util.ArrayList<>();
		for (com.ironhub.state.PersistedState.SeedStep step : seed.steps)
		{
			labels.add(step.label);
			reqs.add(step.requirement);
		}
		assertTrue(labels.contains("Obtain Tumeken's shadow"));
		assertTrue("obtain step routes through item: for engine decomposition",
			reqs.contains("item:27277"));
		assertTrue("wield/access reqs are steps", reqs.contains("quest:Beneath Cursed Sands"));
		assertTrue(labels.stream().noneMatch(l -> l.startsWith("Stock ")
			|| l.contains("item 27277")));

		// it renders and buckets as a Gear goal, achieved once owned
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);
		state.addGoalSeed(seed);
		assertTrue(state.getSelectedGoals().contains("gear:27277"));
		com.ironhub.data.GoalsPack.Goal goal =
			com.ironhub.modules.goals.GoalPlannerModule.toGoal(seed);
		assertEquals("Tumeken's shadow", goal.getName());
		assertEquals((Integer) shadow, goal.icon());
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.ATTACK, 40, 0);
		GearProgressionModule module = new GearProgressionModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, null, null);
		module.startUp();
		GearLibraryTab tab = (GearLibraryTab) module.buildTab();

		// the library sorted by value, a weapon slot, and one row expanded
		tab.sortForTest(EquipmentLibrary.Sort.VALUE);
		java.awt.image.BufferedImage image = SwingRender.render(tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/gear-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);

		// a slash-attack sort with a row opened into its stat card
		tab.sortForTest(EquipmentLibrary.Sort.SLASH);
		List<com.ironhub.data.EquipmentPack.Item> top = tab.visibleForTest();
		if (!top.isEmpty())
		{
			tab.expandForTest(top.get(0).primaryId());
		}
		javax.imageio.ImageIO.write(SwingRender.render(tab), "png",
			new java.io.File("build/reports/gear-library-detail.png"));

		// the progression chart, folded away below, opened
		tab.expandChartForTest();
		javax.imageio.ImageIO.write(SwingRender.render(tab), "png",
			new java.io.File("build/reports/gear-chart-section.png"));
		module.shutDown();

		// the other theme wears the same geometry in the vanilla palette;
		// seed one obtained (green bevel) and one targeted (orange bevel) tile
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		List<com.ironhub.data.GearProgressionPack.Item> firstGroup =
			progression.getPhases().get(0).getGroups().get(0).getItems();
		state.setUnlocked(firstGroup.get(0).markKey(), true);
		state.selectGoal(firstGroup.get(1).goalId(), true);
		GearTab stone = new GearTab(state,
			progression,
			new DataPack(new Gson()).load("boosts", com.ironhub.data.BoostsPack.class),
			null, false, hide -> { }, com.ironhub.ui.osrs.OsrsTheme.STONE, null,
			new DataPack(new Gson()).load("item-sources", com.ironhub.data.ItemSourcesPack.class));
		java.awt.image.BufferedImage stoneImage = SwingRender.render(stone);
		assertTrue(stoneImage.getHeight() > 100);
		javax.imageio.ImageIO.write(stoneImage, "png",
			new java.io.File("build/reports/gear-tab-stone.png"));
		stone.dispose();
	}
}
