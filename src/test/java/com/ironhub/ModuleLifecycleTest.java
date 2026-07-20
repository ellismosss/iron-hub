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
import com.ironhub.modules.loot.LootModule;
import com.ironhub.modules.quests.QuestsModule;
import com.ironhub.modules.slayer.SlayerOptimizerModule;
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
			new GearProgressionModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null),
			new QuestsModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null),
			new DiariesModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new CombatAchievementsModule(state, config, null,
				new net.runelite.client.eventbus.EventBus(),
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null),
			new QolModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson())),
			new LootModule(state, null, config),
			new BankTrackerModule(state, null, null, null, null, null, null, null, null, null, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null),
			new FarmingRunModule(state, null, new net.runelite.client.eventbus.EventBus(), null, null, null, config, null, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null, null, null, null, null, null),
			new DailiesModule(state, null, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null, null, null, null, null, null, null, null, null, null),
			new GoalPlannerModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null),
			new ClueStashModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				new net.runelite.client.eventbus.EventBus(), null),
			new SlayerOptimizerModule(state, null, null, config, null, null,
				new net.runelite.client.eventbus.EventBus(), null, null, null,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null, null, null, null, null),
			new SuppliesRunwayModule(state, null, config),
			new CollectionLogModule(state, null, null, new net.runelite.client.eventbus.EventBus(), config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null),
			new ExternalSyncModule(state, null, new net.runelite.client.eventbus.EventBus(), config, null, new com.google.gson.Gson()),
			new DashboardModule(),
			new DeathRecoveryModule(state, null, config, null),
			new com.ironhub.modules.loadoutlab.LoadoutLabModule(
				new com.loadoutlab.LoadoutLabPlugin(), new net.runelite.client.eventbus.EventBus(), config,
				state, null, null, null, new com.google.gson.Gson(), null, null, null, null),
			new com.ironhub.modules.designlab.DesignLabModule(config, new net.runelite.client.eventbus.EventBus()),
			new com.ironhub.modules.dailies.DailiesNewModule(
				new DailiesModule(state, null, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()),
					null, null, null, null, null, null, null, null, null, null, null),
				config),
			new com.ironhub.modules.poh.PohModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				new net.runelite.client.eventbus.EventBus(), null),
			new com.ironhub.modules.hunter.HunterRumoursModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				null, null, new net.runelite.client.eventbus.EventBus(), null, null,
				null, null, null, null, null, null),
			new com.ironhub.modules.moneymaking.MoneyMakingModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null),
			new com.ironhub.modules.sailing.SailingUpgradesModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				new net.runelite.client.eventbus.EventBus(), null, null),
			new com.ironhub.modules.porttasks.PortTasksModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				new net.runelite.client.eventbus.EventBus(), null, null, null, null),
			new com.ironhub.modules.bankspace.BankSpaceModule(state, config,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()),
				new net.runelite.client.eventbus.EventBus(), null, null, null));

		assertEquals(26, modules.size());

		// the nav blocks route by exact module name — a mismatch is a hub
		// slot forever showing "Enable the <name> module" for a module that
		// does not exist (the classic nav's row-name guard, re-homed)
		java.util.Set<String> names = new java.util.HashSet<>();
		modules.forEach(module -> names.add(module.name()));
		for (java.util.List<String> content : com.ironhub.ui.IronHubPanel.blockContents().values())
		{
			for (String routed : content)
			{
				assertTrue("nav block routes to unknown module: " + routed,
					names.contains(routed));
			}
		}

		for (IronHubModule module : modules)
		{
			assertFalse("blank module name: " + module.getClass(), module.name().trim().isEmpty());
			assertTrue("module disabled by default: " + module.getClass(), module.enabled());
			if (module instanceof com.ironhub.modules.loadoutlab.LoadoutLabModule)
			{
				continue; // imported plugin needs real client services; it has its own suite
			}
			module.startUp();
			module.shutDown();
		}
	}
}
