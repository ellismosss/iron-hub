package com.ironhub.modules.death;

import com.ironhub.IronHubConfig;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Death recovery (DESIGN.md §3.21): calm, factual history of recent
 * deaths — when, where (with a Path button to the spot), and what was
 * carried. Reclaim costs and grave timers arrive later.
 */
@Slf4j
@Singleton
public class DeathRecoveryModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private final ShortestPathBridge pathBridge;
	private DeathTab tab;

	@Inject
	public DeathRecoveryModule(AccountState state, ItemManager itemManager,
		IronHubConfig config, ShortestPathBridge pathBridge)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
		this.pathBridge = pathBridge;
	}

	@Override
	public String name()
	{
		return "Death recovery";
	}

	@Override
	public boolean enabled()
	{
		return config.deathRecovery();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new DeathTab(state, itemManager, pathBridge, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: drop it, the next mount rebuilds. */
	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}
}
