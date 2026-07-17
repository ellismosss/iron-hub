package com.ironhub.modules.slayer;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlayerModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private SlayerOptimizerModule module(AccountState state)
	{
		return new SlayerOptimizerModule(state, null, null, config, null, null,
			new EventBus(), null, null, null, new DataPack(new Gson()), null);
	}

	@Test
	public void readsTaskStateFromVarps()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		SlayerOptimizerModule module = module(state);
		module.startUp();

		assertEquals(0, module.remaining()); // no task
		StateFixture.varp(state, VarPlayerID.SLAYER_COUNT, 87);
		StateFixture.varbit(state, VarbitID.SLAYER_POINTS, 1240);
		StateFixture.varbit(state, VarbitID.SLAYER_TASKS_COMPLETED, 43);

		assertEquals(87, module.remaining());
		assertEquals(1240, module.points());
		assertEquals(43, module.streak());
		module.shutDown();
	}

	@Test
	public void recordLifecycleAssignKillComplete()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SlayerOptimizerModule module = module(state);
		module.startUp();

		StateFixture.stat(state, Skill.SLAYER, 60, 300_000);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_TARGET, 41); // headless: name arrives via the seam
		StateFixture.varbitChanged(state, VarbitID.SLAYER_MASTER, 5); // Duradel
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT_ORIGINAL, 150);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 150);
		module.applyResolvedTask("Dust devils", "");

		PersistedState.SlayerTaskRecord active = module.activeRecord();
		assertNotNull(active);
		assertEquals("Dust devils", active.name);
		assertEquals("Duradel", active.master);
		assertEquals(150, active.assigned);
		assertEquals(300_000, active.xpStart);

		// kills decrement the count; xp accrues
		StateFixture.stat(state, Skill.SLAYER, 60, 300_450);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 147);
		assertEquals(3, module.activeRecord().killed);
		assertEquals(450, module.activeRecord().xpGained);

		// streak increment = completion
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 0);
		StateFixture.varbitChanged(state, VarbitID.SLAYER_TASKS_COMPLETED, 1);
		assertNull(module.activeRecord());
		List<PersistedState.SlayerTaskRecord> records = module.records();
		assertEquals(1, records.size());
		assertTrue(records.get(0).completed);
		assertTrue(records.get(0).end > 0);

		// records survive the persistence round-trip
		assertEquals(1, state.getSlayerRecords().size());
		assertTrue(state.getSlayerRecords().get(0).completed);
		module.shutDown();
	}

	@Test
	public void taskChangeWithoutCompletionClosesHonestly()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SlayerOptimizerModule module = module(state);
		module.startUp();

		StateFixture.varpChanged(state, VarPlayerID.SLAYER_TARGET, 8);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT_ORIGINAL, 60);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 60);
		module.applyResolvedTask("Nechryael", "");
		assertNotNull(module.activeRecord());

		// a points skip zeroes the count with kills left (no streak change),
		// then the replacement task arrives
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 0);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT_ORIGINAL, 40);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 40);
		module.applyResolvedTask("Gargoyles", "");

		List<PersistedState.SlayerTaskRecord> records = module.records();
		assertEquals(2, records.size());
		assertEquals(0, records.get(0).killed); // the skip-to-zero never counts as kills
		assertFalse(records.get(0).completed);
		assertTrue(records.get(0).end > 0);
		assertEquals("Gargoyles", records.get(1).name);
		assertEquals(0, records.get(1).end);
		module.shutDown();
	}

	@Test
	public void targetMatchingIsCoreParity()
	{
		List<Pattern> patterns = SlayerOptimizerModule.targetPatterns(
			"Abyssal demons", List.of("Abyssal Sire"));
		String[] attack = {null, "Attack", null};
		String[] pick = {"Pick"};
		String[] none = {"Talk-to"};

		assertTrue(SlayerOptimizerModule.matchesTarget(patterns, "Abyssal demon", attack));
		assertTrue(SlayerOptimizerModule.matchesTarget(patterns, "Greater abyssal demon", attack));
		assertTrue(SlayerOptimizerModule.matchesTarget(patterns, "Abyssal Sire", attack));
		assertFalse(SlayerOptimizerModule.matchesTarget(patterns, "Abyssal demon", none));
		assertFalse(SlayerOptimizerModule.matchesTarget(patterns, "Demonic gorilla", attack));
		// NBSP normalization (RuneLite composition names)
		assertTrue(SlayerOptimizerModule.matchesTarget(patterns, "Abyssal\u00A0demon", attack));
		// zygomite Pick action counts as attackable
		List<Pattern> zygo = SlayerOptimizerModule.targetPatterns(
			"Mutated zygomites", List.of("Zygomite", "Fungi"));
		assertTrue(SlayerOptimizerModule.matchesTarget(zygo, "Fungi", pick));
	}

	@Test
	public void blockedListsDecodePerMaster()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		SlayerOptimizerModule module = module(state);
		module.startUp();

		StateFixture.varbitChanged(state, VarbitID.SLAYER_BLOCKED_KONAR_1, 41);
		StateFixture.varbitChanged(state, VarbitID.SLAYER_BLOCKED_KONAR_3, 17);
		StateFixture.varbitChanged(state, VarbitID.SLAYER_BLOCKED_DURADEL_1, 5);

		assertEquals(List.of(41, 17), module.blockedTaskIds(8)); // Konar
		assertEquals(List.of(5), module.blockedTaskIds(5));      // Duradel
		assertEquals(List.of(), module.blockedTaskIds(2));       // Mazchna clean
		// Spria reads Turael's slots
		StateFixture.varbitChanged(state, VarbitID.SLAYER_BLOCKED_TURAEL_2, 9);
		assertEquals(module.blockedTaskIds(1), module.blockedTaskIds(9));

		module.seedTaskName(41, "Dust devils");
		assertEquals("Dust devils", module.taskNameById(41));
		assertNull(module.taskNameById(17)); // unresolved stays honest
		module.shutDown();
	}

	@Test
	public void unlockVarbitsMirrorIntoGraphFlags()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		SlayerOptimizerModule module = module(state);
		module.startUp();

		assertFalse(state.isUnlocked("slayerreward_malevolentmasquerade"));
		StateFixture.varbitChanged(state, VarbitID.SLAYER_HELM_UNLOCKED, 1);
		assertTrue(state.isUnlocked("slayerreward_malevolentmasquerade"));
		module.shutDown();
	}

	@Test
	public void tabRendersAllViewsHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.SLAYER, 72, 900_000);
		StateFixture.varbit(state, VarbitID.SLAYER_POINTS, 1240);
		StateFixture.varbit(state, VarbitID.SLAYER_TASKS_COMPLETED, 43);
		StateFixture.varbit(state, VarbitID.SLAYER_MASTER, 5); // Duradel
		StateFixture.varbit(state, VarbitID.SLAYER_BLOCKED_DURADEL_1, 41);
		StateFixture.varbit(state, VarbitID.SLAYER_HELM_UNLOCKED, 1);
		StateFixture.bank(state, java.util.Map.of(4164, 1)); // facemask owned

		SlayerOptimizerModule module = module(state);
		module.startUp();
		module.seedTaskName(41, "Smoke devils");
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_TARGET, 41);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT_ORIGINAL, 150);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 150);
		module.applyResolvedTask("Dust devils", "");
		StateFixture.stat(state, Skill.SLAYER, 72, 940_000);
		StateFixture.varpChanged(state, VarPlayerID.SLAYER_COUNT, 63);
		state.setSlayerNote("Dust devils", "Barrage in the Catacombs");
		state.setSlayerBlockPref("Duradel", java.util.List.of("Smoke devils", "Cave kraken"));
		state.setSlayerSkipPref("Duradel", java.util.List.of("Steel dragons"));

		JComponent tab = module.buildTab();
		assertNotNull(tab);
		String[] names = {"task", "history", "unlocks", "blocks"};
		for (int view = 0; view < names.length; view++)
		{
			((SlayerTab) tab).selectView(view);
			java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
			assertTrue(names[view] + " render too small", image.getHeight() > 80);
			java.io.File out = new java.io.File("build/reports/slayer-" + names[view] + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		module.shutDown();
	}
}
