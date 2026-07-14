package com.ironhub;

import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.bank.BankTrackerModule;
import com.ironhub.modules.clues.ClueStashModule;
import com.ironhub.modules.collectionlog.CollectionLogModule;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.dashboard.DashboardModule;
import com.ironhub.modules.death.DeathRecoveryModule;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.modules.gear.GearProgressionModule;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.modules.slayer.SlayerOptimizerModule;
import com.ironhub.modules.suggest.WhatNowModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.modules.sync.ExternalSyncModule;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * M0 walking-skeleton check: every registered module survives a full
 * startUp/shutDown cycle and reports a usable name.
 */
public class ModuleLifecycleTest
{
	@Test
	public void allModulesStartUpAndShutDownCleanly()
	{
		Set<IronHubModule> modules = new IronHubPlugin().provideModules(
			new GearProgressionModule(),
			new BankTrackerModule(),
			new FarmingRunModule(),
			new DailiesModule(),
			new GoalPlannerModule(),
			new WhatNowModule(),
			new ClueStashModule(),
			new SlayerOptimizerModule(),
			new SuppliesRunwayModule(),
			new CollectionLogModule(),
			new ExternalSyncModule(),
			new DashboardModule(),
			new DeathRecoveryModule());

		assertEquals(13, modules.size());

		for (IronHubModule module : modules)
		{
			assertFalse("blank module name: " + module.getClass(), module.name().trim().isEmpty());
			module.startUp();
			module.shutDown();
		}
	}
}
