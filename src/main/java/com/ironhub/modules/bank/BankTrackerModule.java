package com.ironhub.modules.bank;

import com.ironhub.IronHubConfig;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Bank snapshots + search (mockup frame 2f), grown 2026-07-17 into a bank
 * workbench: search-gated list, equipment-stat ranking, Highest-alchs view
 * with persisted exclusions (Alchemiser-style), per-skill banked-XP views
 * (Banked Experience port), and a multi-select whose items glow in the real
 * bank via the shared restock overlay. See DESIGN.md §3.6.
 */
@Slf4j
@Singleton
public class BankTrackerModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final net.runelite.client.game.SkillIconManager skillIconManager;
	private final OverlayManager overlayManager;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final ConfigManager configManager;
	private BankTab tab;

	/** Items the player picked in the sidebar list — glowed in the open
	 *  bank so they can be found. Session-scoped; the tab mutates it. */
	private final Set<Integer> selection = ConcurrentHashMap.newKeySet();
	private com.ironhub.ui.components.BankRestockOverlay selectionOverlay;

	@Inject
	public BankTrackerModule(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.game.SkillIconManager skillIconManager,
		OverlayManager overlayManager,
		IronHubConfig config, DataPack dataPack, ConfigManager configManager)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.skillIconManager = skillIconManager;
		this.overlayManager = overlayManager;
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
		if (overlayManager != null)
		{
			selectionOverlay = new com.ironhub.ui.components.BankRestockOverlay(() -> selection);
			overlayManager.add(selectionOverlay);
		}
	}

	@Override
	public void shutDown()
	{
		if (selectionOverlay != null)
		{
			overlayManager.remove(selectionOverlay);
			selectionOverlay = null;
		}
		selection.clear();
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	Set<Integer> selection()
	{
		return selection;
	}

	@Override
	public javax.swing.JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new BankTab(state, itemManager, clientThread, skillIconManager, selection,
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
