package com.ironhub;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
import com.ironhub.modules.quests.QuestsModule;
import com.ironhub.modules.slayer.SlayerOptimizerModule;
import com.ironhub.modules.suggest.WhatNowModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.modules.sync.ExternalSyncModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.IronHubPanel;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Iron Hub",
	description = "Central companion hub for Ironman accounts: gear, quests, milestones, bank, farming, dailies and more",
	tags = {"ironman", "iron", "progression", "tracker", "hub"}
)
public class IronHubPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private AccountState accountState;

	@Inject
	private IronHubPanel panel;

	@Inject
	private Set<IronHubModule> modules;

	private final Set<IronHubModule> started = new HashSet<>();
	private NavigationButton navButton;

	// RuneLite ships Guice 4.1 no_aop without the multibindings extension,
	// so the module set is assembled here rather than via Multibinder.
	@Provides
	@Singleton
	Set<IronHubModule> provideModules(
		GearProgressionModule gearProgression,
		QuestsModule quests,
		DiariesModule diaries,
		CombatAchievementsModule combatAchievements,
		QolModule qol,
		BankTrackerModule bankTracker,
		FarmingRunModule farmingRun,
		DailiesModule dailies,
		GoalPlannerModule goalPlanner,
		WhatNowModule whatNow,
		ClueStashModule clueStash,
		SlayerOptimizerModule slayerOptimizer,
		SuppliesRunwayModule suppliesRunway,
		CollectionLogModule collectionLog,
		ExternalSyncModule externalSync,
		DashboardModule dashboard,
		DeathRecoveryModule deathRecovery)
	{
		// TODO: skills, loot/supplies, boat — see DESIGN.md §3
		return ImmutableSet.of(
			gearProgression, quests, diaries, combatAchievements, qol, bankTracker,
			farmingRun, dailies, goalPlanner, whatNow, clueStash,
			slayerOptimizer, suppliesRunway, collectionLog, externalSync,
			dashboard, deathRecovery);
	}

	@Override
	protected void startUp()
	{
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Iron Hub")
			.icon(icon)
			.priority(4)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		syncModuleLifecycles();
		log.info("Iron Hub started with {}/{} modules enabled", started.size(), modules.size());
	}

	@Override
	protected void shutDown()
	{
		started.forEach(IronHubModule::shutDown);
		started.clear();
		clientToolbar.removeNavigation(navButton);
		accountState.persist();
	}

	/** Start newly enabled modules; stop newly disabled ones. */
	private void syncModuleLifecycles()
	{
		for (IronHubModule module : modules)
		{
			boolean shouldRun = module.enabled();
			if (shouldRun && !started.contains(module))
			{
				module.startUp();
				started.add(module);
			}
			else if (!shouldRun && started.contains(module))
			{
				module.shutDown();
				started.remove(module);
				panel.invalidateModule(module.name());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		accountState.onGameStateChanged(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		accountState.onStatChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		accountState.onVarbitChanged(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		accountState.onItemContainerChanged(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		accountState.onGameTick();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (IronHubConfig.GROUP.equals(event.getGroup()))
		{
			syncModuleLifecycles();
		}
	}

	@Provides
	IronHubConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronHubConfig.class);
	}
}
