package com.ironhub.modules.loot;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LootTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void monsterTileInitials()
	{
		assertEquals("KQ", LootTab.initials("Kalphite Queen"));
		assertEquals("D", LootTab.initials("Dagannoth"));
		assertEquals("?", LootTab.initials(""));
	}

	/** L3 (2026-08-03, reworked after live test): the pickup classifier —
	 *  ItemStack locations are a dead API, so drops register against the
	 *  NPC's death tile and despawns match by id within MATCH_RADIUS.
	 *  Picked when standing on the despawn tile or the inventory gained
	 *  the id (telegrab), unknown through a scene reload (never guessed),
	 *  left when it just timed out. */
	@Test
	public void pickupClassification()
	{
		net.runelite.api.coords.WorldPoint died =
			new net.runelite.api.coords.WorldPoint(3200, 3200, 0);
		// a large NPC's loot can land tiles away from the death anchor
		net.runelite.api.coords.WorldPoint drop =
			new net.runelite.api.coords.WorldPoint(3202, 3201, 0);
		net.runelite.api.coords.WorldPoint away =
			new net.runelite.api.coords.WorldPoint(3210, 3210, 0);
		net.runelite.api.coords.WorldPoint farAway =
			new net.runelite.api.coords.WorldPoint(3300, 3300, 0);

		LootPickupTracker tracker = new LootPickupTracker();
		tracker.onLoot("Zulrah", 12934, 100, died);
		LootPickupTracker.Classified onTile =
			tracker.onDespawn(12934, drop, drop, false, false);
		assertEquals(LootPickupTracker.Fate.PICKED, onTile.fate);
		assertEquals("Zulrah", onTile.source);
		assertEquals(100, onTile.quantity);

		tracker.onLoot("Zulrah", 12934, 50, died);
		LootPickupTracker.Classified telegrab =
			tracker.onDespawn(12934, drop, away, false, true);
		assertEquals(LootPickupTracker.Fate.PICKED, telegrab.fate);

		tracker.onLoot("Zulrah", 2402, 1, died);
		LootPickupTracker.Classified reload =
			tracker.onDespawn(2402, drop, drop, true, false);
		assertEquals(LootPickupTracker.Fate.UNKNOWN, reload.fate);

		tracker.onLoot("Zulrah", 995, 5000, died);
		LootPickupTracker.Classified timedOut =
			tracker.onDespawn(995, drop, away, false, false);
		assertEquals(LootPickupTracker.Fate.LEFT, timedOut.fate);

		// an item the tracker never registered is not ours to classify
		org.junit.Assert.assertNull(tracker.onDespawn(4151, drop, drop, false, false));
		// same id, but nowhere near any registered kill: someone else's drop
		tracker.onLoot("Zulrah", 995, 100, died);
		org.junit.Assert.assertNull(tracker.onDespawn(995, farAway, farAway, false, false));
	}

	/** L5: session scope resets with the profile; all-time persists. */
	@Test
	public void sessionScopeIsPerActivation()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 77L);
		state.incrementKillCount("Zulrah");
		state.ingestLoot("Zulrah", Map.of(12934, 100));
		assertEquals(100, (int) state.sessionLootFor("Zulrah").get(12934));
		assertEquals(1, state.sessionKillCount("Zulrah"));

		AccountState fresh = StateFixture.state(temp.getRoot());
		StateFixture.profile(fresh, 77L);
		assertEquals(100, (int) fresh.lootFor("Zulrah").get(12934));
		assertTrue(fresh.sessionLootFor("Zulrah").isEmpty());
		assertEquals(0, fresh.sessionKillCount("Zulrah"));
	}

	/** L3: confirmed pickups persist and never leak into the drop totals. */
	@Test
	public void pickedLootPersistsSeparately()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 77L);
		state.ingestLoot("Zulrah", Map.of(12934, 100));
		state.recordPickedLoot("Zulrah", Map.of(12934, 60));
		assertEquals(100, (int) state.lootFor("Zulrah").get(12934));
		assertEquals(60, (int) state.lootPickedFor("Zulrah").get(12934));

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 77L);
		assertEquals(60, (int) after.lootPickedFor("Zulrah").get(12934));
	}

	@Test
	public void lootAggregatesAndSurvivesRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 77L);
		before.incrementKillCount("Zulrah");
		before.incrementKillCount("Zulrah");
		before.ingestLoot("Zulrah", Map.of(12934, 100)); // scales
		before.ingestLoot("Zulrah", Map.of(12934, 150, 2402, 1));

		assertEquals(250, (int) before.lootFor("Zulrah").get(12934));
		assertTrue(before.lootSources().contains("Zulrah"));

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 77L);
		assertEquals(250, (int) after.lootFor("Zulrah").get(12934));
		assertEquals(2, after.getKillCount("Zulrah"));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.incrementKillCount("Zulrah");
		StateFixture.inventory(state, Map.of(385, 5)); // sharks
		StateFixture.checkpointSupplies(state);
		StateFixture.inventory(state, Map.of(385, 3)); // ate two
		state.ingestLoot("Zulrah", Map.of(12934, 100, 2402, 1));
		StateFixture.itemNames(state, Map.of(12934, "Zulrah's scales", 2402, "Magic fang", 385, "Shark"));

		LootModule module = new LootModule(state, null, new IronHubConfig()
		{
		}, null, new net.runelite.client.eventbus.EventBus(),
			new com.ironhub.data.DataPack(new com.google.gson.Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/loot-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
