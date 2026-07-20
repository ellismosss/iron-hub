package com.ironhub.modules.sailing;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.BoatUpgradesPack;
import com.ironhub.data.DataPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

public class SailingUpgradesModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final BoatUpgradesPack pack =
		new DataPack(new Gson()).load("boat-upgrades", BoatUpgradesPack.class);

	private SailingUpgradesModule module(AccountState state)
	{
		return new SailingUpgradesModule(state, config,
			new DataPack(new Gson()), new EventBus(), null, null);
	}

	@Test
	public void packIntegrity()
	{
		assertTrue(pack.upgrades.size() >= 90);
		assertEquals(20, pack.parts.size());
		assertEquals(10, pack.detection.schematics.size());

		Set<String> ids = new HashSet<>();
		Map<String, Integer> lastTier = new HashMap<>();
		for (BoatUpgradesPack.Upgrade row : pack.upgrades)
		{
			assertTrue("duplicate row id " + row.id, ids.add(row.id));
			assertFalse(row.id + " has no materials", row.materials.isEmpty());
			for (BoatUpgradesPack.Material m : row.materials)
			{
				assertTrue(row.id + " unresolved material " + m.name, m.itemId > 0);
				assertTrue(row.id + " zero qty " + m.name, m.qty > 0);
			}
			for (String req : row.reqs)
			{
				assertFalse(row.id + " req is manual: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
			// tiers never regress within a part+boat ladder
			String ladder = row.part + ":" + row.boatType;
			Integer prev = lastTier.get(ladder);
			assertTrue(row.id + " tier goes backwards",
				prev == null || row.tier > prev);
			lastTier.put(ladder, row.tier);
		}

		// every facility part is object-detectable; core parts read varbits
		Set<String> core = Set.of("Base", "Hull", "Helm", "Sails", "Keel");
		for (BoatUpgradesPack.Part part : pack.parts)
		{
			boolean detected = pack.detection.facilities.stream()
				.anyMatch(d -> d.part.equals(part.key));
			if ("facility".equals(part.kind))
			{
				assertTrue(part.key + " has no detection objects", detected);
			}
			else
			{
				assertTrue(part.key + " not a core part", core.contains(part.key));
			}
			assertFalse(part.key + " has no benefit copy", part.benefit.isEmpty());
		}

		// the reference's noted quirk survives: Linen trawling net wants more
		// Construction on a sloop than a skiff
		Map<Integer, Integer> linen = new HashMap<>();
		for (BoatUpgradesPack.Upgrade row : pack.upgrades)
		{
			if ("Linen trawling net".equals(row.name))
			{
				linen.put(row.boatType, row.construction);
			}
		}
		assertEquals(2, linen.size());
		assertFalse(linen.get(1).equals(linen.get(2)));

		// the upstream fathom inversion stays fixed: detection tier order
		// matches the catalog (stone = tier 0, pearl = tier 1)
		BoatUpgradesPack.FacilityHit stoneHit = null;
		for (BoatUpgradesPack.FacilityDetection d : pack.detection.facilities)
		{
			if ("Fathom Device".equals(d.part) && d.tier == 0)
			{
				stoneHit = pack.facilityByObjectId(d.objectIds.get(0));
			}
		}
		assertNotNull(stoneHit);
		assertEquals(0, stoneHit.tier);

		// famous anchors
		BoatUpgradesPack.Upgrade dragonKeel = pack.upgrades.stream()
			.filter(u -> "Dragon keel".equals(u.name) && u.boatType == 1)
			.findFirst().orElseThrow();
		assertEquals(97, dragonKeel.sailing);
		assertEquals("Dragon keel schematic", dragonKeel.schematic);
		assertTrue(dragonKeel.reqs.contains("unlock:schematic_dragon_keel"));
	}

	@Test
	public void snapshotQueriesAndAvailability()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SailingUpgradesModule module = module(state);

		assertTrue(module.knownBoats().isEmpty());

		Map<String, Integer> tiers = new HashMap<>();
		tiers.put("Hull", 1);
		tiers.put("Sails", 0);
		tiers.put("Salvaging Hook", 6);
		state.putSailingBoat(1, tiers, 1_000L);

		assertEquals(List.of(1), module.knownBoats());
		assertEquals(1, module.detectedTier(1, "Hull"));
		assertEquals(-1, module.detectedTier(1, "Cannon"));

		// next = the lowest tier above the detected one
		assertEquals(2, module.nextRow(1, "Hull").tier);
		assertEquals("Oak hull", module.currentRow(1, "Hull").name);
		assertEquals(1, module.nextRow(1, "Sails").tier);
		assertNull("ladder done", module.nextRow(1, "Salvaging Hook"));
		assertEquals(0, module.nextRow(1, "Cannon").tier);

		// a raft never lists sloop/skiff-only parts or excluded facilities
		List<String> raftParts = new java.util.ArrayList<>();
		module.partsFor(0).forEach(p -> raftParts.add(p.key));
		assertTrue(raftParts.contains("Base"));
		assertFalse(raftParts.contains("Hull"));
		assertFalse(raftParts.contains("Keel"));
		assertFalse(raftParts.contains("Trawling Net"));
		List<String> skiffParts = new java.util.ArrayList<>();
		module.partsFor(1).forEach(p -> skiffParts.add(p.key));
		assertTrue(skiffParts.contains("Hull"));
		assertFalse(skiffParts.contains("Base"));

		// tiers only rise — a scan that read 0 must not downgrade the hull
		Map<String, Integer> worse = new HashMap<>();
		worse.put("Hull", 0);
		state.putSailingBoat(1, worse, 2_000L);
		assertEquals(1, module.detectedTier(1, "Hull"));
	}

	@Test
	public void goalTrackingAndProof()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SailingUpgradesModule module = module(state);
		module.startUp();

		BoatUpgradesPack.Upgrade teakHull = pack.upgrades.stream()
			.filter(u -> "Teak hull".equals(u.name) && u.boatType == 1)
			.findFirst().orElseThrow();

		module.toggleGoal(teakHull);
		assertTrue(module.isGoal(teakHull));
		assertTrue(state.getSelectedGoals().contains("boat:" + teakHull.id));
		com.ironhub.data.GoalsPack.Goal goal = com.ironhub.modules.goals.GoalPlannerModule
			.toGoal(state.getGoalSeeds().get("boat:" + teakHull.id));
		assertEquals("boat:" + teakHull.id, goal.getId());
		assertFalse(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));
		// the seed carries the level gates, a gather step per material and
		// the build step
		assertEquals(teakHull.reqs.size() + teakHull.materials.size() + 1,
			state.getGoalSeeds().get("boat:" + teakHull.id).steps.size());

		// a boat sync that sees the part built proves the goal
		Map<String, Integer> tiers = new HashMap<>();
		tiers.put("Hull", 2);
		state.putSailingBoat(1, tiers, 1_000L);
		assertTrue(state.isUnlocked(GoalSeeds.boatProofKey(teakHull.id)));
		assertTrue(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));

		// untracking retires the seed
		module.toggleGoal(teakHull);
		assertFalse(state.getGoalSeeds().containsKey("boat:" + teakHull.id));
		module.shutDown();
	}

	@Test
	public void goalOnAnAlreadyBuiltUpgradeProvesImmediately()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SailingUpgradesModule module = module(state);
		module.startUp();

		Map<String, Integer> tiers = new HashMap<>();
		tiers.put("Cannon", 3);
		state.putSailingBoat(2, tiers, 1_000L);

		BoatUpgradesPack.Upgrade ironCannon = pack.upgrades.stream()
			.filter(u -> "Iron cannon".equals(u.name)).findFirst().orElseThrow();
		module.toggleGoal(ironCannon);
		assertTrue(state.isUnlocked(GoalSeeds.boatProofKey(ironCannon.id)));
		module.shutDown();
	}

	/** A sloop-only row never proves off a skiff's part tier. */
	@Test
	public void proofRespectsBoatType()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SailingUpgradesModule module = module(state);
		module.startUp();

		BoatUpgradesPack.Upgrade sloopOak = pack.upgrades.stream()
			.filter(u -> "Oak hull".equals(u.name) && u.boatType == 2)
			.findFirst().orElseThrow();
		module.toggleGoal(sloopOak);

		Map<String, Integer> tiers = new HashMap<>();
		tiers.put("Hull", 3);
		state.putSailingBoat(1, tiers, 1_000L); // the SKIFF has an oak+ hull
		assertFalse(state.isUnlocked(GoalSeeds.boatProofKey(sloopOak.id)));

		state.putSailingBoat(2, tiers, 2_000L); // now the sloop does too
		assertTrue(state.isUnlocked(GoalSeeds.boatProofKey(sloopOak.id)));
		module.shutDown();
	}

	@Test
	public void schematicUnlockGatesTheRow()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.CONSTRUCTION, 90, 6_000_000);
		BoatUpgradesPack.Upgrade dragonKeel = pack.upgrades.stream()
			.filter(u -> "Dragon keel".equals(u.name) && u.boatType == 1)
			.findFirst().orElseThrow();
		// Sailing level can't be seeded past what the enum allows headless?
		// It can — sailing is a normal skill to the fixture.
		StateFixture.stat(state, Skill.SAILING, 99, 14_000_000);

		boolean met = dragonKeel.reqs.stream()
			.allMatch(r -> Requirements.parse(r).isMet(state));
		assertFalse("schematic still locked", met);

		state.setUnlocked("schematic_dragon_keel", true);
		assertTrue(dragonKeel.reqs.stream()
			.allMatch(r -> Requirements.parse(r).isMet(state)));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.SAILING, 40, 40_000);
		StateFixture.stat(state, Skill.CONSTRUCTION, 40, 40_000);
		// bank stock: teak hull materials partly covered (mixed colours)
		Map<Integer, Integer> bank = new HashMap<>();
		BoatUpgradesPack.Upgrade teakHull = pack.upgrades.stream()
			.filter(u -> "Teak hull".equals(u.name) && u.boatType == 1)
			.findFirst().orElseThrow();
		bank.put(teakHull.materials.get(0).itemId, teakHull.materials.get(0).qty);
		StateFixture.bank(state, bank);

		SailingUpgradesModule module = module(state);
		module.startUp();
		SailingUpgradesTab tab = (SailingUpgradesTab) module.buildTab();
		assertNotNull(tab);
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			Map<String, Integer> tiers = new HashMap<>();
			tiers.put("Hull", 1);
			tiers.put("Sails", 1);
			tiers.put("Helm", 0);
			tiers.put("Keel", 0);
			tiers.put("Cargo Hold", 2);
			tiers.put("Salvaging Hook", 6);
			state.putSailingBoat(1, tiers, 1_000L);
			module.toggleGoal(pack.upgrades.stream()
				.filter(u -> "Teak hull".equals(u.name) && u.boatType == 1)
				.findFirst().orElseThrow());
			tab.expand("1:Hull");
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/sailing-upgrades-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
