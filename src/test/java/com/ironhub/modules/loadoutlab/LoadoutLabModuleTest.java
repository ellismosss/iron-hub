package com.ironhub.modules.loadoutlab;

import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoadoutLabModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void savedSetupsPersistWithInventoryAndPouch()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 42L);
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("WEAPON", 12926);
		setup.equipment.put("HEAD", 13197);
		setup.inventory = new int[]{385, -1, 2434};
		setup.inventoryQty = new int[]{5, 0, 1};
		setup.pouchRunes = new int[]{554, 555, -1, -1};
		setup.pouchAmounts = new int[]{1000, 500, 0, 0};
		before.saveSetup("Kalphites", setup);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 42L);
		PersistedState.SavedSetup loaded = after.savedSetup("Kalphites");
		assertTrue(loaded != null);
		assertEquals((Integer) 12926, loaded.equipment.get("WEAPON"));
		assertEquals(385, loaded.inventory[0]);
		assertEquals(5, loaded.inventoryQty[0]);
		assertEquals(554, loaded.pouchRunes[0]);
		assertEquals(1000, loaded.pouchAmounts[0]);
		assertTrue(after.savedSetup("Zulrah") == null);
	}

	/** The wrapper chrome (activity card, saved setup, loading note) in the
	 *  stone skin — the upstream lab panel is absent headless, so the tab
	 *  shows the honest loading note where it would mount. */
	@Test
	public void wrapperChromeRendersInTheStoneSkin() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);
		state.setSlayerTask("Kalphites");
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("WEAPON", 12926);
		setup.equipment.put("HEAD", 13197);
		setup.inventory = new int[]{385, -1, 2434};
		setup.inventoryQty = new int[]{5, 0, 1};
		setup.pouchRunes = new int[]{554, 555, -1, -1};
		setup.pouchAmounts = new int[]{1000, 500, 0, 0};
		state.saveSetup("Kalphites", setup);

		LoadoutLabModule module = newModule(state);
		javax.swing.JComponent tab = module.buildTab();
		java.awt.image.BufferedImage image =
			com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/loadoutlab-wrapper.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	private static LoadoutLabModule newModule(AccountState state)
	{
		return new LoadoutLabModule(new com.loadoutlab.LoadoutLabPlugin(),
			new net.runelite.client.eventbus.EventBus(), new com.ironhub.IronHubConfig()
			{
			},
			state, null, null, null, new com.google.gson.Gson(), null, null, null, null);
	}

	// ── the diff rules (SetupDiff truth table) ────────────────────────

	/**
	 * Per slot with S = setup item, C = current item:
	 * S==C -> shown S, no tint; S present C different -> shown S, SWAP;
	 * S empty + C elsewhere in the setup -> hidden; S empty + C nowhere
	 * in the setup -> shown C, DEPOSIT. Variation-aware (charged glory
	 * settles the glory slot).
	 */
	@Test
	public void diffTintsFollowTheTruthTable()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("WEAPON", 12926); // blowpipe, currently worn too
		setup.equipment.put("HEAD", 13197);   // wanted, currently different
		setup.inventory = new int[]{385, -1, -1};
		setup.inventoryQty = new int[]{5, 0, 0};

		// worn: weapon matches, head is something else, ring worn but the
		// setup has no ring anywhere -> deposit-only
		int[] worn = new int[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx() + 1];
		java.util.Arrays.fill(worn, -1);
		worn[net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx()] = 12926;
		worn[net.runelite.api.EquipmentInventorySlot.HEAD.getSlotIdx()] = 1163;
		worn[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx()] = 2550;

		java.util.Map<String, SetupDiff.Slot> eq = SetupDiff.equipment(setup, worn);
		assertEquals(SetupDiff.Tint.NONE, eq.get("WEAPON").tint);
		assertEquals(12926, eq.get("WEAPON").itemId);
		assertEquals(SetupDiff.Tint.SWAP, eq.get("HEAD").tint);
		assertEquals(13197, eq.get("HEAD").itemId); // the SETUP item shows
		assertEquals(SetupDiff.Tint.DEPOSIT, eq.get("RING").tint);
		assertEquals(2550, eq.get("RING").itemId);  // the CURRENT item shows
		assertEquals(SetupDiff.Tint.NONE, eq.get("CAPE").tint); // both empty
		assertTrue(eq.get("CAPE").itemId <= 0);

		// inventory: slot 0 matches, slot 1 currently holds a setup item
		// (the head piece we still need) -> hidden, slot 2 currently holds
		// a stranger -> deposit
		int[] carried = new int[]{385, 13197, 995};
		SetupDiff.Slot[] inv = SetupDiff.inventory(setup, carried);
		assertEquals(SetupDiff.Tint.NONE, inv[0].tint);
		assertEquals(385, inv[0].itemId);
		assertEquals(SetupDiff.Tint.NONE, inv[1].tint); // accounted for at HEAD
		assertTrue(inv[1].itemId <= 0);
		assertEquals(SetupDiff.Tint.DEPOSIT, inv[2].tint);
		assertEquals(995, inv[2].itemId);
	}

	@Test
	public void diffIsVariationAware()
	{
		// derive a variant pair from the real mapping rather than trusting
		// hardcoded ids: glory(4) and plain glory share a canonical id
		int glory = 1704;
		int gloryCharged = 1712;
		assertEquals(net.runelite.client.game.ItemVariationMapping.map(glory),
			net.runelite.client.game.ItemVariationMapping.map(gloryCharged));

		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("AMULET", glory);
		int[] worn = new int[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx() + 1];
		java.util.Arrays.fill(worn, -1);
		worn[net.runelite.api.EquipmentInventorySlot.AMULET.getSlotIdx()] = gloryCharged;
		assertEquals(SetupDiff.Tint.NONE,
			SetupDiff.equipment(setup, worn).get("AMULET").tint);
	}

	// ── rune pouch size detection ─────────────────────────────────────

	@Test
	public void pouchSlotsFollowTheCarriedPouch()
	{
		java.util.Set<Integer> divine = java.util.Set.of(net.runelite.api.ItemID.DIVINE_RUNE_POUCH);
		java.util.Set<Integer> divineLocked = java.util.Set.of(net.runelite.api.ItemID.DIVINE_RUNE_POUCH_L);
		java.util.Set<Integer> regular = java.util.Set.of(net.runelite.api.ItemID.RUNE_POUCH);
		java.util.Set<Integer> none = java.util.Set.of(4151);

		assertEquals(4, LoadoutLabModule.pouchSlots(divine, false));
		assertEquals(4, LoadoutLabModule.pouchSlots(divineLocked, false));
		assertEquals(3, LoadoutLabModule.pouchSlots(regular, false));
		// unrecognised divine variant: the 4th rune varbit is the tell
		assertEquals(4, LoadoutLabModule.pouchSlots(regular, true));
		// no pouch carried = no pouch panel, even with stale varbits
		assertEquals(0, LoadoutLabModule.pouchSlots(none, true));
	}

	// ── renders: live view + diffed setup view ────────────────────────

	private AccountState liveState() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		int[] worn = new int[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx() + 1];
		java.util.Arrays.fill(worn, -1);
		worn[net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx()] = 12926;
		worn[net.runelite.api.EquipmentInventorySlot.HEAD.getSlotIdx()] = 1163;
		worn[net.runelite.api.EquipmentInventorySlot.RING.getSlotIdx()] = 2550;
		StateFixture.equipmentSlots(state, worn);
		int[] inv = new int[28];
		java.util.Arrays.fill(inv, -1);
		inv[0] = 385;
		inv[1] = 13197;
		inv[2] = 995;
		inv[3] = net.runelite.api.ItemID.RUNE_POUCH;
		StateFixture.inventorySlots(state, inv);
		StateFixture.inventory(state, java.util.Map.of(385, 5, 13197, 1, 995, 10000,
			net.runelite.api.ItemID.RUNE_POUCH, 1));
		StateFixture.runePouch(state, java.util.Map.of(554, 1000, 555, 600));
		StateFixture.varp(state, net.runelite.api.VarPlayer.ATTACK_STYLE, 1);
		return state;
	}

	@Test
	public void liveViewRenders() throws Exception
	{
		AccountState state = liveState();
		LoadoutLabModule module = newModule(state);
		javax.swing.JComponent tab = module.buildTab();
		java.awt.image.BufferedImage image =
			com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/loadout-live.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	@Test
	public void diffedSetupViewRenders() throws Exception
	{
		AccountState state = liveState();
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("WEAPON", 12926); // matches -> no tint
		setup.equipment.put("HEAD", 13197);   // differs -> orange
		setup.inventory = new int[]{385, -1, -1};
		setup.inventoryQty = new int[]{5, 0, 0};
		setup.pouchRunes = new int[]{554, -1, -1, -1};
		setup.pouchAmounts = new int[]{1000, 0, 0, 0};
		state.saveSetup("Zulrah", setup);

		LoadoutLabModule module = newModule(state);
		javax.swing.JComponent tab = module.buildTab();
		javax.swing.SwingUtilities.invokeAndWait(() -> module.viewSetupForTest("Zulrah"));
		java.awt.image.BufferedImage image =
			com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/loadout-setup-diff.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	/** The setups list: checkbox-less checklist rows inside a stone-scrolled
	 *  frame, capped so a long list never dominates the tab (Luke). */
	@Test
	public void setupsListRendersFramedAndScrollCapped() throws Exception
	{
		AccountState state = liveState();
		for (int i = 1; i <= 12; i++)
		{
			PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
			setup.equipment.put("WEAPON", 12926);
			state.saveSetup("Setup " + i, setup);
		}
		LoadoutLabModule module = newModule(state);
		javax.swing.JComponent tab = module.buildTab();
		javax.swing.SwingUtilities.invokeAndWait(module::toggleAllSetupsForTest);
		java.awt.image.BufferedImage image =
			com.ironhub.ui.SwingRender.render((javax.swing.JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/loadout-setups-list.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	@Test
	public void equipmentRendersInOsrsLayoutOrder()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("RING", 28307);
		setup.equipment.put("HEAD", 13197);
		setup.equipment.put("WEAPON", 12926);
		Map<String, Integer> ordered = LoadoutLabModule.layoutOrder(setup);
		// head row first, weapon row before the gloves/boots/ring row
		assertEquals(java.util.List.of("HEAD", "WEAPON", "RING"),
			new java.util.ArrayList<>(ordered.keySet()));
	}
}
