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
	// ponytail: unused since the Grid/List chips went (2026-07-17); kept so
	// the injected ctor arity stays pinned by the lifecycle tests
	private final ConfigManager configManager;
	private final javax.inject.Provider<com.ironhub.modules.goals.GoalPlannerModule> planner; // null in tests
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

	/** Debounces the live bank re-collect: each apply is a full tag rewrite
	 *  (~50 config writes) + bank relayout on the client thread, and a
	 *  sidebar search changes the display list per keystroke (2026-07-20
	 *  audit) — apply once the sidebar settles. */
	private final javax.swing.Timer bankDisplayTimer =
		new javax.swing.Timer(300, e -> applyBankDisplay());

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
		IronHubConfig config, DataPack dataPack, ConfigManager configManager,
		javax.inject.Provider<com.ironhub.modules.goals.GoalPlannerModule> planner)
	{
		this.planner = planner;
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
		bankDisplayTimer.setRepeats(false);
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
		bankDisplayTimer.stop(); // a pending debounced apply must not fire post-shutdown
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

	/** The goal plan's TRAIN target for a skill display name, 0 = none —
	 *  the plan seam (2026-07-20 intelligence arc). */
	private int plannedTargetFor(String skillName)
	{
		if (planner == null)
		{
			return 0;
		}
		try
		{
			for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
			{
				if (skill.getName().equalsIgnoreCase(skillName))
				{
					return planner.get().plannedTargetLevel(skill);
				}
			}
		}
		catch (RuntimeException e)
		{
			// provider unbound (planner module absent) — no plan, no target
		}
		return 0;
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
			bankDisplayTimer.restart();
		}
	}

	private void applyBankDisplay()
	{
		if (clientThread == null)
		{
			return;
		}
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

	/** Re-assert the collected view when the bank opens — AFTER the init
	 *  script finishes (opening a tag mid-init forces an extra relayout;
	 *  the apply is a no-op when our tag is already active). */
	@net.runelite.client.eventbus.Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_INIT
			&& !bankDisplay.isEmpty() && clientThread != null)
		{
			clientThread.invokeLater(() ->
			{
				if (!bankDisplay.isEmpty())
				{
					collectView.apply(bankDisplay);
				}
			});
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
				config.osrsTheme(), this::plannedTargetFor,
				dataPack.load("item-sources", com.ironhub.data.ItemSourcesPack.class));
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
