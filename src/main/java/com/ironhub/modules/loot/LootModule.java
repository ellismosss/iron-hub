package com.ironhub.modules.loot;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Loot & supplies tracker (DESIGN.md §3.9, mockup frame 2g): per-source
 * lifetime loot with total and per-kill views, item-first framing.
 * Supplies consumption, per-hour/session windows, uniques highlighting
 * and the NET view build on this as their data arrives.
 */
@Slf4j
@Singleton
public class LootModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private LootTab tab;

	@Inject
	public LootModule(AccountState state, ItemManager itemManager, IronHubConfig config)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Loot & supplies";
	}

	@Override
	public boolean enabled()
	{
		return config.lootSupplies();
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
			tab = new LootTab(state, itemManager);
		}
		return tab;
	}
}
