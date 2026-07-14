package com.ironhub;

import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.bank.BankTrackerModule;
import com.ironhub.modules.ca.CombatAchievementsModule;
import com.ironhub.modules.clues.ClueStashModule;
import com.ironhub.modules.collectionlog.CollectionLogModule;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.dashboard.DashboardModule;
import com.ironhub.modules.death.DeathRecoveryModule;
import com.ironhub.modules.diaries.DiariesModule;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.modules.gear.GearProgressionModule;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.modules.qol.QolModule;
import com.ironhub.modules.loadout.LoadoutModule;
import com.ironhub.modules.quests.QuestsModule;
import com.ironhub.modules.slayer.SlayerOptimizerModule;
import com.ironhub.modules.suggest.WhatNowModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.modules.sync.ExternalSyncModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M0 walking-skeleton check: every registered module survives a full
 * startUp/shutDown cycle and reports a usable name.
 */
public class ModuleLifecycleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void allModulesStartUpAndShutDownCleanly()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		IronHubConfig config = new IronHubConfig()
		{
		};

		Set<IronHubModule> modules = new IronHubPlugin().provideModules(
			new GearProgressionModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new QuestsModule(state, config),
			new DiariesModule(state, config),
			new CombatAchievementsModule(state, config),
			new QolModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new LoadoutModule(state, null, config, new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new BankTrackerModule(state, null, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new FarmingRunModule(),
			new DailiesModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null),
			new GoalPlannerModule(),
			new WhatNowModule(),
			new ClueStashModule(),
			new SlayerOptimizerModule(),
			new SuppliesRunwayModule(),
			new CollectionLogModule(),
			new ExternalSyncModule(),
			new DashboardModule(),
			new DeathRecoveryModule());

		assertEquals(18, modules.size());

		for (IronHubModule module : modules)
		{
			assertFalse("blank module name: " + module.getClass(), module.name().trim().isEmpty());
			assertTrue("module disabled by default: " + module.getClass(), module.enabled());
			module.startUp();
			module.shutDown();
		}
	}
}
