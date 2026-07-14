package com.ironhub.modules.loadout;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.ScenariosPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Best-in-bank loadout solver (DESIGN.md §3.6) as a top-level module.
 * BETA: heuristic ranking; wiki DPS calc export is the authority.
 */
@Slf4j
@Singleton
public class LoadoutModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private LoadoutTab tab;

	private final com.google.gson.Gson gson;
	private final okhttp3.OkHttpClient httpClient;

	@Inject
	public LoadoutModule(AccountState state, ItemManager itemManager,
		ClientThread clientThread, IronHubConfig config, DataPack dataPack,
		com.google.gson.Gson gson, okhttp3.OkHttpClient httpClient)
	{
		this.gson = gson;
		this.httpClient = httpClient;
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.config = config;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "Loadout";
	}

	@Override
	public boolean enabled()
	{
		return config.bankTracker(); // solver reads the bank snapshot
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
			tab = new LoadoutTab(state, itemManager, clientThread,
				dataPack.load("scenarios", ScenariosPack.class), config, gson, httpClient);
		}
		return tab;
	}
}
