package com.ironhub.modules.bank;

import com.ironhub.IronHubConfig;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
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
	private final net.runelite.client.callback.ClientThread clientThread;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final ConfigManager configManager;
	private BankTab tab;

	@Inject
	public BankTrackerModule(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		IronHubConfig config, DataPack dataPack, ConfigManager configManager)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.config = config;
		this.dataPack = dataPack;
		this.configManager = configManager;
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
			tab = new BankTab(state, itemManager, clientThread,
				dataPack.load("banked-xp", BankedXpPack.class),
				config.bankedXpGridView(), gridView ->
				{
					if (configManager != null)
					{
						configManager.setConfiguration(IronHubConfig.GROUP, "bankedXpGridView", gridView);
					}
				}, config.osrsTheme());
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
