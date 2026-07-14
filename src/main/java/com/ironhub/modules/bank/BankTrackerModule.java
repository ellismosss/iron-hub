package com.ironhub.modules.bank;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Bank snapshots + search (mockup frame 2f). Banked XP and the
 * best-in-bank loadout solver arrive with their data packs.
 * See DESIGN.md §3.6.
 */
@Slf4j
@Singleton
public class BankTrackerModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private BankTab tab;

	@Inject
	public BankTrackerModule(AccountState state, ItemManager itemManager, IronHubConfig config)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Bank & banked XP";
	}

	@Override
	public boolean enabled()
	{
		return config.bankTracker();
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
			tab = new BankTab(state, itemManager);
		}
		return tab;
	}
}
