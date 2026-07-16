package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.CropState;
import com.ironhub.modules.farming.rl.PatchImplementation;
import com.ironhub.modules.farming.rl.PatchState;
import com.ironhub.modules.farming.rl.Produce;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FarmingRunModuleTest
{
	private static final int FALADOR_REGION = 12083;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private FarmingRunModule module(AccountState state, ConfigManager configManager, Notifier notifier)
	{
		FarmingRunModule module = new FarmingRunModule(state, null, new EventBus(),
			null, null, null, config, null, new DataPack(new Gson()), notifier,
			configManager, null, null, null, null, null);
		module.startUp();
		return module;
	}

	private static int herbValue(Produce produce, CropState cropState, int stage)
	{
		for (int value = 0; value < 256; value++)
		{
			PatchState state = PatchImplementation.HERB.forVarbitValue(value);
			if (state != null && state.getProduce() == produce
				&& state.getCropState() == cropState && state.getStage() == stage)
			{
				return value;
			}
		}
		throw new IllegalStateException("no herb varbit value");
	}

	/** A tree varbit value for a real sapling freshly planted (stage 0 growing)
	 *  — decodes to the GROWING view, not the past-estimate PREDICTED_READY. */
	private static int treeValue()
	{
		for (int value = 0; value < 256; value++)
		{
			PatchState state = PatchImplementation.TREE.forVarbitValue(value);
			if (state != null && state.getCropState() == CropState.GROWING
				&& state.getStage() == 0
				&& state.getProduce() != Produce.WEEDS && state.getProduce().getItemID() > 0)
			{
				return value;
			}
		}
		throw new IllegalStateException("no tree varbit value");
	}

	@Test
	public void formatting()
	{
		assertEquals("5:40", FarmingRunModule.formatDuration(340_000));
		assertEquals("0:05", FarmingRunModule.formatDuration(5_400));
		assertEquals("no runs logged yet", FarmingRunModule.statsLine(List.of()));
		assertEquals("avg 5:17 · best 4:55 · 2 runs logged",
			FarmingRunModule.statsLine(List.of(340_000L, 295_000L)));
	}

	@Test
	public void advancesOnPlantThenCompostNotOnArrival()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		ConfigManager configManager = TimetrackingFixture.configManager();
		FarmingRunModule module = module(state, configManager, null);
		long now = Instant.now().getEpochSecond();

		module.startTemplate("Herb run");
		// a fresh account can only reach the four ungated herb patches; the
		// wiki route starts at Falador
		assertEquals(4, module.stops().size());
		assertEquals("herb/falador", module.nextStop().location.id);

		// arrival (patch still empty/harvestable) does NOT advance
		module.refreshTracking();
		assertEquals("herb/falador", module.nextStop().location.id);

		// plant a seed there: patch reads growing — but herbs need compost, so
		// still no advance
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), now);
		module.refreshTracking();
		assertEquals("herb/falador", module.nextStop().location.id);

		// compost at the wrong place is ignored
		module.onCompostApplied(99999);
		assertEquals("herb/falador", module.nextStop().location.id);

		// compost at this stop: planted + composted -> advance to the next
		module.onCompostApplied(FALADOR_REGION);
		assertTrue(module.isVisited("herb/falador"));
		assertEquals("herb/ardougne", module.nextStop().location.id);
		module.shutDown();
	}

	@Test
	public void treesWaitForCompostAndDoNotAutoAdvanceOnPreExistingGrowth()
	{
		// The bug: a tree/fruit stop advanced the moment its patch read
		// "growing" — but that growth is from a PREVIOUS run (trees take
		// hours), so a fresh run started half-done and cascaded to complete.
		// Every category must now wait for a live compost at the stop.
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		ConfigManager configManager = TimetrackingFixture.configManager();
		FarmingRunModule module = module(state, configManager, null);
		int lumbridgeTreeRegion = 12594; // first stop of the wiki tree route
		long now = Instant.now().getEpochSecond();
		StateFixture.bank(state, Map.of(5370, 9)); // oak saplings so tree stops survive culling

		module.startTemplate("Tree run");
		assertEquals("tree/lumbridge", module.nextStop().location.id);

		// the Lumbridge tree is ALREADY growing (planted last run) — must not advance
		TimetrackingFixture.patch(configManager, lumbridgeTreeRegion, VarbitID.FARMING_TRANSMIT_A,
			treeValue(), now);
		module.refreshTracking();
		assertEquals("tree/lumbridge", module.nextStop().location.id);

		// only a compost worked here moves the run on (trees gate on it too now)
		module.onCompostApplied(lumbridgeTreeRegion);
		assertTrue(module.isVisited("tree/lumbridge"));
		assertEquals("tree/varrock", module.nextStop().location.id);
		module.shutDown();
	}

	@Test
	public void allotmentFlowerHerbRunSpansAllThreeAtEachArea()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		assertTrue(module.templateNames().contains("Allotment, flower & herb run"));

		module.startTemplate("Allotment, flower & herb run");
		assertTrue(module.multiCategory());
		// Falador first: allotment, flower, then herb (all plant seeds, not
		// saplings, so nothing is sapling-culled)
		assertEquals("allotment/falador", module.stops().get(0).location.id);
		assertEquals("flower/falador", module.stops().get(1).location.id);
		assertEquals("herb/falador", module.stops().get(2).location.id);
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("allotment")));
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("flower")));
		assertEquals("Falador · allotment", module.stopLabel(stop(module, "allotment/falador")));
		module.shutDown();
	}

	@Test
	public void supercompostRunIsAManualCompostBinChecklist()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		assertTrue(module.templateNames().contains("Supercompost run"));

		module.startTemplate("Supercompost run");
		// Port Phasmatys needs Priest in Peril, so a fresh account gets three
		// compost-bin stops; the run is a single category with plain labels
		assertEquals(3, module.stops().size());
		assertFalse(module.multiCategory());
		assertEquals("compost/catherby", module.stops().get(0).location.id);
		assertEquals("Catherby", module.stopLabel(stop(module, "compost/catherby")));
		// compost bins have no crop, so they never auto-advance — only markThrough
		module.markThrough("compost/ardougne");
		assertEquals(2, module.visitedCount());
		module.shutDown();
	}

	@Test
	public void birdhouseRunGatesOnFossilIslandAndIsManual()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		assertTrue(module.templateNames().contains("Birdhouse run"));

		// no Fossil Island access -> no stops
		module.startTemplate("Birdhouse run");
		assertEquals(0, module.stops().size());
		module.endRun(false);

		// Bone Voyage + Hunter -> all four sites, in the wiki's efficient order
		StateFixture.quest(state, net.runelite.api.Quest.BONE_VOYAGE,
			net.runelite.api.QuestState.FINISHED);
		StateFixture.stat(state, net.runelite.api.Skill.HUNTER, 5, 400);
		module.startTemplate("Birdhouse run");
		assertEquals(4, module.stops().size());
		assertEquals("birdhouse/verdant-valley-ne", module.stops().get(0).location.id);
		// bird houses have no crop state, so they never auto-advance — manual skip
		module.markThrough("birdhouse/verdant-valley-sw");
		assertEquals(2, module.visitedCount());
		module.shutDown();
	}

	@Test
	public void hopAndBushRunInterleavesHopsAndBushes()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		assertTrue(module.templateNames().contains("Hop & bush run"));

		// hops and bushes plant seeds (no sapling list) — never sapling-culled
		module.startTemplate("Hop & bush run");
		assertEquals("bush/champions-guild", module.stops().get(0).location.id);
		assertTrue(module.multiCategory());
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("hops")));
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("bush")));
		// Etceteria bush needs the Fremennik Trials — culled for a fresh account
		assertFalse(module.stops().stream().anyMatch(s -> s.location.id.equals("bush/etceteria")));
		module.shutDown();
	}

	@Test
	public void hardwoodRunGatesOnAccessAndSaplings()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		assertTrue(module.templateNames().contains("Hardwood run"));

		// fresh account: no Fossil Island access and no saplings -> nothing to do
		module.startTemplate("Hardwood run");
		assertEquals(0, module.stops().size());
		module.endRun(false);

		// Bone Voyage + teak saplings -> the Fossil Island stop is in; Locus Oasis
		// still needs Varlamore access, Anglers' Retreat needs its quest/levels
		StateFixture.quest(state, net.runelite.api.Quest.BONE_VOYAGE,
			net.runelite.api.QuestState.FINISHED);
		StateFixture.bank(state, Map.of(21477, 2)); // teak saplings
		module.startTemplate("Hardwood run");
		assertTrue(module.stops().stream().anyMatch(s -> s.location.id.equals("hardwood/fossil-island")));
		assertFalse(module.stops().stream().anyMatch(s -> s.location.id.equals("hardwood/locus-oasis")));
		module.shutDown();
	}

	@Test
	public void runIsCulledToOwnedSaplingsAndAwayFromGrowingPatches()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		ConfigManager configManager = TimetrackingFixture.configManager();
		FarmingRunModule module = module(state, configManager, null);
		long now = Instant.now().getEpochSecond();

		// fresh account: lumbridge, varrock, falador, taverley, gnome-stronghold
		// are accessible (farming-guild needs 65, auburnvale needs a quest). With
		// no tree saplings there's nothing to plant — the run culls to empty.
		module.startTemplate("Tree run");
		assertEquals(0, module.stops().size());
		module.endRun(false);

		// two oak saplings — keep only the first two accessible stops (wiki order)
		StateFixture.bank(state, Map.of(5370, 2));
		module.startTemplate("Tree run");
		assertEquals(2, module.stops().size());
		assertEquals("tree/lumbridge", module.stops().get(0).location.id);
		assertEquals("tree/varrock", module.stops().get(1).location.id);
		module.endRun(false);

		// plenty of saplings, but Falador's tree is confirmed still growing —
		// that stop drops out (nothing to harvest yet), the rest stay
		StateFixture.bank(state, Map.of(5370, 9));
		TimetrackingFixture.patch(configManager, 11828, VarbitID.FARMING_TRANSMIT_A,
			treeValue(), now);
		module.refreshTracking();
		module.startTemplate("Tree run");
		assertFalse(module.stops().stream().anyMatch(s -> s.location.id.equals("tree/falador")));
		assertTrue(module.stops().stream().anyMatch(s -> s.location.id.equals("tree/lumbridge")));
		module.shutDown();
	}

	@Test
	public void teleportItemsCountRunePouchAndHigherWhistleTiers()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		int lawRune = 563;

		// runes carried in the rune pouch count toward a teleport's runes
		StateFixture.runePouch(state, Map.of(lawRune, 10));
		assertEquals(10, module.carriedCount(lawRune));

		// an enhanced quetzal whistle satisfies a basic-whistle requirement
		// (the tiers have different names, so aren't ItemVariationMapping-grouped)
		StateFixture.inventory(state, Map.of(
			net.runelite.api.gameval.ItemID.HG_QUETZALWHISTLE_ENHANCED, 1));
		assertEquals(1, module.carriedCount(
			net.runelite.api.gameval.ItemID.HG_QUETZALWHISTLE_BASIC));
		module.shutDown();
	}

	@Test
	public void overviewListsSeenPatchesPerCategory()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		long now = Instant.now().getEpochSecond();
		// two herb patches with data: Falador ready, Ardougne growing
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), now);
		TimetrackingFixture.patch(configManager, 10548, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), now);
		FarmingRunModule module = module(state, configManager, null);
		module.refreshTracking();

		java.util.Map<com.ironhub.modules.farming.rl.Tab, List<FarmingRunModule.OverviewPatch>> ov =
			module.overviewByCategory();
		// the HERB category appears (it has seen patches) and lists EVERY herb
		// patch, the two seeded ones with a crop state and the rest as unknown
		List<FarmingRunModule.OverviewPatch> herbs = ov.get(com.ironhub.modules.farming.rl.Tab.HERB);
		assertNotNull(herbs);
		assertTrue("expected all herb patches", herbs.size() > 2);
		FarmingRunModule.OverviewPatch falador = herbs.stream()
			.filter(p -> p.name.equals("Falador")).findFirst().orElseThrow();
		FarmingRunModule.OverviewPatch ardougne = herbs.stream()
			.filter(p -> p.name.equals("Ardougne")).findFirst().orElseThrow();
		assertEquals(com.ironhub.modules.farming.rl.CropState.HARVESTABLE, falador.cropState);
		assertEquals(com.ironhub.modules.farming.rl.CropState.GROWING, ardougne.cropState);
		assertTrue("unseen patches read as unknown",
			herbs.stream().anyMatch(p -> p.cropState == null));
		module.shutDown();
	}

	/**
	 * The tiles must sit in the same place every session. They used to be
	 * ordered by however the tracker's Tab-keyed map iterated — and a HashMap
	 * keyed by an enum iterates by IDENTITY hash, which differs on every JVM
	 * run, so the strip rearranged itself between logins.
	 */
	@Test
	public void overviewTilesAreAlwaysInTabOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		long now = Instant.now().getEpochSecond();
		// seed three categories, deliberately NOT in Tab order
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), now);
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_A,
			allotmentValue(), now);
		FarmingRunModule module = module(state, configManager, null);
		module.refreshTracking();

		List<com.ironhub.modules.farming.rl.Tab> tiles =
			new java.util.ArrayList<>(module.overviewByCategory().keySet());
		List<com.ironhub.modules.farming.rl.Tab> sorted = new java.util.ArrayList<>(tiles);
		sorted.sort(java.util.Comparator.naturalOrder());
		assertEquals("tiles must be in Tab order, not a map's iteration order",
			sorted, tiles);
		assertTrue("expected at least two tiles to order", tiles.size() >= 2);

		// and the same call twice must not shuffle
		assertEquals(tiles, new java.util.ArrayList<>(module.overviewByCategory().keySet()));
		module.shutDown();
	}

	/** Any planted allotment value — swept from the real decoder, not guessed. */
	private static int allotmentValue()
	{
		for (int value = 0; value < 256; value++)
		{
			PatchState state = PatchImplementation.ALLOTMENT.forVarbitValue(value);
			if (state != null && state.getCropState() == CropState.GROWING
				&& state.getProduce() != Produce.WEEDS)
			{
				return value;
			}
		}
		throw new AssertionError("no growing allotment value in the decoder");
	}

	/**
	 * Every run in the picker shows an icon. RUN_ICONS is keyed by display
	 * name, so renaming a run in the pack would silently drop its picture —
	 * this is what catches that.
	 */
	@Test
	public void everyRunHasAnIcon()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		for (String name : module.templateNames())
		{
			assertTrue("no icon for run: " + name, module.runIcon(name) > 0);
		}
		// bird houses and compost bins are not farming patches, so categoryTab
		// says null on purpose — the icon lookup must not rely on it
		assertNull(FarmingRunModule.categoryTab("birdhouse"));
		assertNull(FarmingRunModule.categoryTab("compost"));
		assertEquals(com.ironhub.modules.farming.rl.Tab.BIRD_HOUSE,
			FarmingRunModule.iconTab("birdhouse"));
		assertEquals(com.ironhub.modules.farming.rl.Tab.BIG_COMPOST,
			FarmingRunModule.iconTab("compost"));
		module.shutDown();
	}

	/**
	 * A custom run is neither a curated route nor a category template, so
	 * asking it for its locations used to fall through to templateLocations(null)
	 * and throw — which every picker row now does, to know its icon and whether
	 * it is ready.
	 */
	@Test
	public void customRunsResolveTheirOwnStops()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		state.saveFarmRun("My run", List.of("herb/falador", "herb/ardougne"));
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);

		assertEquals(2, module.runLocations("My run").size());
		assertTrue("a custom run needs an icon too", module.runIcon("My run") > 0);
		assertFalse(module.runReady("My run")); // no patch data seeded
		module.startCustom("My run");
		assertEquals(2, module.stops().size());
		module.endRun(false);

		// and a name that is no run at all is simply empty, not an exception
		assertTrue(module.runLocations("no such run").isEmpty());
		assertEquals(0, module.runIcon("no such run"));
		assertFalse(module.runReady("no such run"));
		module.shutDown();
	}

	@Test
	public void overviewTilesMergeCalquatCelastrusIntoTreeAndSpecials()
	{
		assertEquals(com.ironhub.modules.farming.rl.Tab.TREE,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.CALQUAT));
		assertEquals(com.ironhub.modules.farming.rl.Tab.TREE,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.CELASTRUS));
		assertEquals(com.ironhub.modules.farming.rl.Tab.SPECIAL,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.HESPORI));
		assertEquals(com.ironhub.modules.farming.rl.Tab.SPECIAL,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.MUSHROOM));
		assertEquals(com.ironhub.modules.farming.rl.Tab.SPECIAL,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.BELLADONNA));
		assertEquals(com.ironhub.modules.farming.rl.Tab.SPECIAL,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.CACTUS));
		// unmerged categories keep their own tile
		assertEquals(com.ironhub.modules.farming.rl.Tab.HERB,
			FarmingRunModule.displayGroup(com.ironhub.modules.farming.rl.Tab.HERB));
	}

	@Test
	public void runReadyWhenAPatchIsHarvestable()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		ConfigManager configManager = TimetrackingFixture.configManager();
		FarmingRunModule module = module(state, configManager, null);
		assertFalse(module.runReady("Herb run")); // no data yet

		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		module.refreshTracking();
		assertTrue(module.runReady("Herb run"));   // a herb patch is harvestable
		assertFalse(module.runReady("Tree run"));   // no tree data
		module.shutDown();
	}

	@Test
	public void bankHighlightMarksSetupItemsStillToWithdraw()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);

		// no run active -> nothing to highlight
		assertTrue(module.farmBankHighlight().isEmpty());

		// a setup: worn cape + inventory of teleport tabs and seeds
		com.ironhub.state.PersistedState.SavedSetup setup = new com.ironhub.state.PersistedState.SavedSetup();
		setup.equipment = Map.of("CAPE", 1052);
		setup.inventory = new int[]{8013, 5291};
		setup.inventoryQty = new int[]{3, 5};
		state.saveFarmRunSetup("Herb run", setup);
		module.startTemplate("Herb run");

		// carrying none of it -> all three ids highlighted
		java.util.Set<Integer> hl = module.farmBankHighlight();
		assertTrue(hl.contains(1052));
		assertTrue(hl.contains(8013));
		assertTrue(hl.contains(5291));

		// wear the cape and carry the tabs -> they drop out; seeds still needed
		StateFixture.equipmentSlots(state, new int[]{0, 1052});
		StateFixture.inventory(state, Map.of(8013, 3));
		hl = module.farmBankHighlight();
		assertFalse(hl.contains(1052));
		assertFalse(hl.contains(8013));
		assertTrue(hl.contains(5291));
		module.shutDown();
	}

	@Test
	public void manualSkipMarksThroughAndCanCompleteTheRun()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		module.startTemplate("Herb run"); // falador, ardougne, catherby, kourend

		// skip through Catherby: marks falador, ardougne, catherby done
		module.markThrough("herb/catherby");
		assertEquals(3, module.visitedCount());
		assertEquals("herb/kourend", module.nextStop().location.id);

		// skip the last: the run completes and is recorded
		module.markThrough("herb/kourend");
		assertFalse(module.running());
		assertEquals(1, state.getHerbRunsMs().size());
		module.shutDown();
	}

	@Test
	public void combinedTreeAndFruitRunGroupsCoLocatedPatches()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);

		List<FarmRunsPack.Location> combined =
			module.templateLocations(List.of("tree", "fruit"));
		assertEquals(14, combined.size()); // 7 tree + 7 fruit
		// Farming Guild and Gnome Stronghold have both patches — the fruit one
		// slots in right after its tree twin so you do both at that site
		int fgTree = indexOf(combined, "tree/farming-guild");
		assertEquals("fruit/farming-guild", combined.get(fgTree + 1).id);
		int gsTree = indexOf(combined, "tree/gnome-stronghold");
		assertEquals("fruit/gnome-stronghold", combined.get(gsTree + 1).id);
		// fruit-only sites come after every tree site
		assertTrue(indexOf(combined, "fruit/brimhaven")
			> indexOf(combined, "tree/auburnvale"));
		module.shutDown();
	}

	@Test
	public void combinedRunLabelsDisambiguateThePatchType()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		StateFixture.stat(state, net.runelite.api.Skill.FARMING, 85, 8_771_558);
		StateFixture.quest(state, net.runelite.api.Quest.CHILDREN_OF_THE_SUN,
			net.runelite.api.QuestState.FINISHED);
		// enough saplings that no stop is sapling-culled (5370 oak, 5496 apple)
		StateFixture.bank(state, Map.of(5370, 20, 5496, 20));
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);

		module.startTemplate("All trees run");
		assertTrue(module.multiCategory());
		FarmingRunModule.Stop fgTree = stop(module, "tree/farming-guild");
		FarmingRunModule.Stop fgFruit = stop(module, "fruit/farming-guild");
		assertEquals("Farming Guild · tree", module.stopLabel(fgTree));
		assertEquals("Farming Guild · fruit tree", module.stopLabel(fgFruit));

		// a single-category run keeps the plain name
		module.endRun(false);
		module.startTemplate("Tree run");
		assertFalse(module.multiCategory());
		assertEquals("Falador", module.stopLabel(stop(module, "tree/falador")));
		module.shutDown();
	}

	@Test
	public void comboTreeRunFollowsTheCuratedRouteAndSpansCategories() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		StateFixture.stat(state, net.runelite.api.Skill.FARMING, 85, 8_771_558);
		StateFixture.quest(state, net.runelite.api.Quest.CHILDREN_OF_THE_SUN,
			net.runelite.api.QuestState.FINISHED);
		// a sapling of every category so nothing is sapling-culled
		StateFixture.bank(state, Map.of(5370, 20, 5496, 20, 5503, 20, 22856, 20));
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);

		assertTrue(module.templateNames().contains("All trees run"));
		module.startTemplate("All trees run");
		assertTrue(module.multiCategory());

		// the curated order is preserved through culling — fruit tree first,
		// then its tree twin at the Gnome Stronghold
		assertEquals("fruit/gnome-stronghold", module.stops().get(0).location.id);
		assertEquals("tree/gnome-stronghold", module.stops().get(1).location.id);

		// the run genuinely spans calquat and celastrus, and labels its types
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("calquat")));
		assertTrue(module.stops().stream().anyMatch(s -> s.location.category.equals("celastrus")));
		assertEquals("Farming Guild · celastrus",
			module.stopLabel(stop(module, "celastrus/farming-guild")));

		// a long run's overlay must still fit the 250x200 budget (capped list)
		FarmingRunOverlay overlay = new FarmingRunOverlay(module);
		java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
			300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = canvas.createGraphics();
		g.setColor(new java.awt.Color(58, 66, 48));
		g.fillRect(0, 0, 300, 300);
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		java.awt.Dimension size = overlay.render(g);
		g.dispose();
		assertNotNull(size);
		assertTrue("combo overlay height " + size.height, size.height <= 200);
		java.io.File out = new java.io.File("build/reports/farming-run-combo-overlay.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(canvas, "png", out);
		module.shutDown();
	}

	private static int indexOf(List<FarmRunsPack.Location> locations, String id)
	{
		for (int i = 0; i < locations.size(); i++)
		{
			if (locations.get(i).id.equals(id))
			{
				return i;
			}
		}
		return -1;
	}

	private static FarmingRunModule.Stop stop(FarmingRunModule module, String id)
	{
		return module.stops().stream().filter(s -> s.location.id.equals(id))
			.findFirst().orElseThrow();
	}

	@Test
	public void customRunsPersistAndResolve()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		state.saveFarmRun("Quick herbs", List.of("herb/falador", "herb/catherby", "no/such-place"));

		module.startCustom("Quick herbs");
		assertTrue(module.running());
		assertEquals(2, module.stops().size()); // the unknown id is skipped
		assertEquals("herb/falador", module.stops().get(0).location.id);
		module.endRun(false);

		state.deleteFarmRun("Quick herbs");
		assertTrue(state.getFarmRuns().isEmpty());
		module.shutDown();
	}

	@Test
	public void savedSetupCapturesGearAndInventoryAndPersists()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 5L);
		// worn: a cape (slot 1) and a ring (slot 12); inventory: teleport
		// tabs + seeds with quantities
		int[] worn = new int[14];
		worn[net.runelite.api.EquipmentInventorySlot.CAPE.getSlotIdx()] = 1052;
		worn[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx()] = 13126;
		StateFixture.equipmentSlots(before, worn);
		StateFixture.inventorySlots(before, new int[]{8013, 5291, 5291, 0});
		StateFixture.inventory(before, Map.of(8013, 3, 5291, 5));

		com.ironhub.state.PersistedState.SavedSetup setup = before.captureSetup();
		assertEquals((Integer) 1052, setup.equipment.get("CAPE"));
		assertEquals((Integer) 13126, setup.equipment.get("RING"));
		assertEquals(8013, setup.inventory[0]);
		assertEquals(3, setup.inventoryQty[0]);
		assertEquals(5, setup.inventoryQty[1]); // 5 grimy... seeds stacked
		before.saveFarmRunSetup("My herbs", setup);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 5L);
		com.ironhub.state.PersistedState.SavedSetup loaded = after.getFarmRunSetup("My herbs");
		assertNotNull(loaded);
		assertEquals((Integer) 1052, loaded.equipment.get("CAPE"));
		assertEquals(8013, loaded.inventory[0]);

		// deleting the run drops its setup too
		after.saveFarmRun("My herbs", List.of("herb/falador"));
		after.deleteFarmRun("My herbs");
		assertNull(after.getFarmRunSetup("My herbs"));
	}

	@Test
	public void teleportsArePickedFromWhatYouOwn()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		FarmRunsPack.Location falador = module.pack().location("herb/falador");

		// nothing owned: fall back to an access-based option (house portal)
		assertEquals("house", module.pickTeleport(falador).supplier);

		// an Explorer's ring in the BANK is enough to plan around
		StateFixture.bank(state, Map.of(13126, 1));
		assertEquals("Explorers_ring", module.pickTeleport(falador).id);

		// with only a charged glory banked, variation mapping matches the
		// pack's Glory(1) requirement and the glory option is picked
		StateFixture.bank(state, Map.of(1712, 1));
		assertEquals("Amulet_of_Glory", module.pickTeleport(falador).id);

		// carrying nothing: the ring teleport reads as missing until worn
		StateFixture.bank(state, Map.of(13126, 1));
		FarmingRunModule.Stop stop = new FarmingRunModule.Stop(falador, module.pickTeleport(falador));
		assertEquals(1, module.missingItems(stop).size());
		StateFixture.equipmentSlots(state, new int[]{13126});
		assertTrue(module.missingItems(stop).isEmpty());
		module.shutDown();
	}

	@Test
	public void preferredTeleportOverridesTheAutoPick()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		FarmRunsPack.Location ardougne = module.pack().location("herb/ardougne");

		// with the spellbook runes owned, auto-pick would use the spellbook
		// teleport; the player's Ardy-cloak preference wins even unowned
		state.setFarmTeleportPref("herb/ardougne", "Ardy_cloak");
		assertEquals("Ardy_cloak", module.pickTeleport(ardougne).id);

		// clearing the preference returns to the owned-first auto-pick
		state.setFarmTeleportPref("herb/ardougne", null);
		assertFalse("Ardy_cloak".equals(module.pickTeleport(ardougne).id));
		module.shutDown();
	}

	@Test
	public void readinessNotifiesOncePerTransitionAndNeverOnLogin()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		Notifier notifier = Mockito.mock(Notifier.class);

		// stored state is ALREADY harvestable before the module starts —
		// the first refresh must seed silently, never replay old readiness
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		FarmingRunModule module = module(state, configManager, notifier);
		module.refreshTracking();
		Mockito.verifyNoInteractions(notifier);
		assertEquals(1, FarmingRunModule.sharedReadyPatches());

		// harvested + replanted: re-arms
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 0), Instant.now().getEpochSecond());
		module.refreshTracking();
		Mockito.verifyNoInteractions(notifier);

		// grows back to harvestable: exactly one notification
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), Instant.now().getEpochSecond());
		module.refreshTracking();
		module.refreshTracking();
		Mockito.verify(notifier, Mockito.times(1)).notify("Herb Patches ready to harvest.");
		module.shutDown();
		assertEquals(0, FarmingRunModule.sharedReadyPatches());
	}

	@Test
	public void abandonedRunsAreNotRecorded()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		FarmingRunModule module = module(state, TimetrackingFixture.configManager(), null);
		module.startTemplate("Herb run");
		module.endRun(false);
		assertTrue(state.getHerbRunsMs().isEmpty());
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ConfigManager configManager = TimetrackingFixture.configManager();
		long now = Instant.now().getEpochSecond();
		// the core plugin's "prefer soonest" — a category counts as harvestable
		// once ANY of its patches is, which is what puts a run in the ready state
		// the picker renders green
		configManager.setConfiguration(TimeTrackingConfig.CONFIG_GROUP,
			TimeTrackingConfig.PREFER_SOONEST, "true");
		// one ready herb patch, one part-grown (a partial tile arc), + bird houses
		TimetrackingFixture.patch(configManager, FALADOR_REGION, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.HARVESTABLE, 0), now);
		TimetrackingFixture.patch(configManager, 10548, VarbitID.FARMING_TRANSMIT_D,
			herbValue(Produce.RANARR, CropState.GROWING, 2), now);
		int seeded = 0;
		while (com.ironhub.modules.farming.rl.hunter.BirdHouseState.fromVarpValue(seeded)
			!= com.ironhub.modules.farming.rl.hunter.BirdHouseState.SEEDED)
		{
			seeded++;
		}
		TimetrackingFixture.birdHouse(configManager,
			com.ironhub.modules.farming.rl.hunter.BirdHouseSpace.MEADOW_NORTH.getVarp(),
			seeded, now - 10 * 60);

		FarmingRunModule module = module(state, configManager, null);
		module.refreshTracking();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		((FarmingTab) tab).expandOverview(com.ironhub.modules.farming.rl.Tab.HERB); // show the per-patch list
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/farming-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);

		// mid-run: the stop checklist replaces the run picker, and the
		// overlay renders within its 250x200 budget
		StateFixture.bank(state, Map.of(13126, 1)); // Explorer's ring planned
		module.startTemplate("Herb run");
		// ardougne is culled (its patch reads growing above), so mark the first
		// surviving stop through for the mid-run render
		module.markThrough(module.stops().get(0).location.id);
		SwingUtilities.invokeAndWait(((FarmingTab) tab)::rebuild); // Swing is single-threaded
		java.awt.image.BufferedImage active = SwingRender.render((JPanel) tab);
		javax.imageio.ImageIO.write(active, "png", new java.io.File("build/reports/farming-run-active.png"));

		// gained some Farming xp and grimy herbs SINCE the run started, so
		// the overlay's progress line shows the tracking
		StateFixture.stat(state, net.runelite.api.Skill.FARMING, 80, 2_200_000);
		StateFixture.inventory(state, Map.of(207, 6)); // 6 grimy ranarr
		assertTrue(module.farmingXpGained() > 0);
		assertEquals(6, module.herbsHarvested());

		FarmingRunOverlay overlay = new FarmingRunOverlay(module);
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
			new java.io.File("build/reports/farming-run-overlay.png"));
		module.shutDown();
	}
}
