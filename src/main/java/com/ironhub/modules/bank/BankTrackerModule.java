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
	private final net.runelite.client.eventbus.EventBus eventBus;
	private final net.runelite.api.Client client;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final ConfigManager configManager;
	private BankTab tab;

	/** Items the player picked in the sidebar list — these get the green
	 *  glow inside the collected bank view. Session-scoped; the tab mutates. */
	private final Set<Integer> selection = ConcurrentHashMap.newKeySet();
	private com.ironhub.ui.components.BankRestockOverlay selectionOverlay;

	/** What the real bank collects together (Inventory Setups-style): the
	 *  sidebar's current result list in SKILL view, else the selection. */
	private volatile java.util.List<Integer> bankDisplay = java.util.List.of();
	private volatile String bankTitle = "";
	private com.ironhub.ui.components.BankCollectView collectView;

	@Inject
	public BankTrackerModule(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.game.SkillIconManager skillIconManager,
		OverlayManager overlayManager,
		net.runelite.client.eventbus.EventBus eventBus,
		net.runelite.api.Client client,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager,
		IronHubConfig config, DataPack dataPack, ConfigManager configManager)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.skillIconManager = skillIconManager;
		this.overlayManager = overlayManager;
		this.eventBus = eventBus;
		this.client = client;
		this.collectView = new com.ironhub.ui.components.BankCollectView(
			"_ironhubbank_", bankTagsService, tagManager, layoutManager, itemManager);
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
		if (eventBus != null)
		{
			eventBus.register(this);
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (selectionOverlay != null)
		{
			overlayManager.remove(selectionOverlay);
			selectionOverlay = null;
		}
		selection.clear();
		bankDisplay = java.util.List.of();
		if (clientThread != null)
		{
			clientThread.invoke(collectView::clear);
		}
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

	/**
	 * The sidebar's current bank-display set (ordered) + a readable title
	 * for the bank window. Applies/clears the collected view live — the
	 * bank rearranges immediately when it is open, or on its next build.
	 */
	void setBankDisplay(java.util.List<Integer> itemIds, String title)
	{
		if (itemIds.equals(bankDisplay))
		{
			return;
		}
		bankDisplay = java.util.List.copyOf(itemIds);
		bankTitle = title;
		if (clientThread != null)
		{
			clientThread.invoke(() ->
			{
				if (bankDisplay.isEmpty())
				{
					collectView.clear();
				}
				else
				{
					collectView.apply(bankDisplay);
				}
			});
		}
	}

	/** Re-assert the collected view whenever the bank rebuilds itself. */
	@net.runelite.client.eventbus.Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_INIT
			&& !bankDisplay.isEmpty())
		{
			collectView.apply(bankDisplay);
		}
	}

	/** Readable bank title while collected (Bank Tags shows the raw hidden
	 *  tag name otherwise — the farm-run lesson). */
	@net.runelite.client.eventbus.Subscribe
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_FINISHBUILDING
			|| client == null || !collectView.isApplied())
		{
			return;
		}
		net.runelite.api.widgets.Widget title =
			client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.TITLE);
		if (title != null)
		{
			title.setText("<col=ff981f>" + bankTitle + "</col> — Iron Hub");
		}
	}

	@Override
	public javax.swing.JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new BankTab(state, itemManager, clientThread, skillIconManager, selection,
				this::setBankDisplay,
				dataPack.load("banked-xp", BankedXpPack.class),
				dataPack.load("xp-actions", com.ironhub.data.XpActionsPack.class),
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
