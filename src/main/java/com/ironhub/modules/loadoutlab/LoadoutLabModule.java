package com.ironhub.modules.loadoutlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.loadoutlab.LoadoutLabPlugin;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemID;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Loadout Lab (github.com/ajkatz/runelite-loadout-lab, BSD-2-Clause,
 * imported whole per user direction) as a task-aware Iron Hub module.
 * The upstream engine lives untouched under {@code com.loadoutlab};
 * this wrapper adds the one-stop-shop layer: a LIVE gear+inventory view
 * (drawn as the game's own interfaces, see SavedSetupView) at the top of
 * the tab, saved setups viewable as a diff against what is currently
 * worn/held (orange = swap in, red = deposit-only), a real-bank collect
 * view of what a viewed setup still needs withdrawn, per-slot item search
 * editing into a draft, plus the activity auto-follow and wiki tips for
 * the lab below.
 */
@Slf4j
@Singleton
public class LoadoutLabModule implements IronHubModule
{
	/** OSRS worn-equipment layout (matches Inventory Setups). */
	private static final EquipmentInventorySlot[][] GRID = {
		{null, EquipmentInventorySlot.HEAD, null},
		{EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET, EquipmentInventorySlot.AMMO},
		{EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD},
		{null, EquipmentInventorySlot.LEGS, null},
		{EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING},
	};
	private static final int[] POUCH_RUNE_VARBITS = {
		Varbits.RUNE_POUCH_RUNE1, Varbits.RUNE_POUCH_RUNE2,
		Varbits.RUNE_POUCH_RUNE3, Varbits.RUNE_POUCH_RUNE4,
	};
	private static final int[] POUCH_AMOUNT_VARBITS = {
		Varbits.RUNE_POUCH_AMOUNT1, Varbits.RUNE_POUCH_AMOUNT2,
		Varbits.RUNE_POUCH_AMOUNT3, Varbits.RUNE_POUCH_AMOUNT4,
	};
	/** Autocast spell varbit (gameval; legacy Varbits has no autocast). */
	private static final int AUTOCAST_SPELL = net.runelite.api.gameval.VarbitID.AUTOCAST_SPELL;
	/** Equipped weapon category — drives which style names apply. */
	private static final int WEAPON_CATEGORY = net.runelite.api.gameval.VarbitID.COMBAT_WEAPON_CATEGORY;
	/**
	 * Autocast index → dummy spell OBJ, whose param 601 (SPELL_NAME) is the
	 * display name — the route the game's own autocast clientscripts take
	 * (enum(int,obj,enum_1986,%varbit276); verified live 2026-07-21, e.g.
	 * value 3 → "Earth Strike"). runelite-api has no EnumID constant for it.
	 */
	private static final int AUTOCAST_SPELLS_ENUM = 1986;

	private final LoadoutLabPlugin lab;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private final AccountState state;
	private final ClientThread clientThread;   // null in unit tests
	private final net.runelite.api.Client client; // null in unit tests
	private final ItemManager itemManager;     // null in unit tests
	private final com.google.gson.Gson gson;
	private final okhttp3.OkHttpClient httpClient; // null in unit tests
	/** Own hidden Bank Tag, never the bank module's — see BankCollectView. */
	private final com.ironhub.ui.components.BankCollectView collectView;

	/** Visibility-gated once the holder exists (RebuildGate): a hidden lab
	 *  strip must not rebuild on every state change. Pre-build, a genuine
	 *  no-op — the old fallback ran the full auto-follow BiS chain per
	 *  combat-target change for a tab that was never opened (2026-07-20
	 *  audit); buildTab seeds itself from live state when it finally runs. */
	private volatile Runnable gatedListener;
	private final Runnable listener = () ->
	{
		// state listeners run on the CLIENT THREAD (the addListener
		// contract) — the cache reads behind the combat line happen here,
		// change-guarded, before the gated EDT rebuild
		refreshCombatNames();
		Runnable gated = gatedListener;
		if (gated != null)
		{
			gated.run();
		}
	};
	private com.ironhub.modules.loadout.StrategyClient strategyClient;
	private com.ironhub.data.ItemNameIndex nameIndex;
	private com.ironhub.ui.osrs.OsrsTheme theme;
	private JPanel holder;
	private JPanel strip;
	private final JPanel activityHolder = new JPanel();
	private final JPanel tipsPanel = new JPanel();
	private final JPanel setupView = new JPanel();
	private final JPanel namesPanel = new JPanel();
	private final JPanel searchPanel = new JPanel();
	private final JPanel searchResults = new JPanel();
	private final JPanel searchTitleHolder = new JPanel();
	private com.ironhub.ui.osrs.StoneTextField searchField;
	private com.ironhub.ui.osrs.StoneButton liveButton;
	private String activityLine = "";
	private String lastAutoSelected = "";
	/** Viewing state: a named setup diffed vs current, an unsaved edited
	 *  draft (wins over the name), or — both null — the live view. */
	private String viewedSetup;
	private PersistedState.SavedSetup draft;

	// ── DPS Calc integration (Luke, 2026-07-21): the wrapper owns the ONE
	// gear viewer + stat tile; the calc publishes its per-style results here
	// and the style buttons/detail follow. EDT-only mutation. ──
	private enum ViewSource
	{
		LIVE, DPS
	}

	private ViewSource viewSource = ViewSource.LIVE;
	private java.util.Map<com.loadoutlab.engine.CombatStyle,
		com.loadoutlab.optimizer.OptimizerService.StyleResult> dpsResults;
	private com.loadoutlab.data.MonsterStats dpsMonster;
	private com.loadoutlab.engine.CombatStyle dpsStyle = com.loadoutlab.engine.CombatStyle.MELEE;
	private boolean namesOpen;
	/** Lazy + volatile: read from the EDT (render) and the client thread
	 *  (cache cross-check); DataPack.load is memoized so double-init is
	 *  the same instance. */
	private volatile com.ironhub.data.WeaponStylesPack stylesPack;
	/** Client-thread cache snapshot for the combat line: whether the pack row
	 *  for this weapon type survived the cache cross-check, and the autocast
	 *  spell's display name. Volatile — the EDT render reads them. */
	private volatile int namedWeaponType = -1;
	private volatile boolean weaponTypeVerified;
	private volatile int namedSpell;
	private volatile String spellName;
	private EquipmentInventorySlot searchSlot;
	private int lastViewFp;
	private boolean dpsCalcCollapsed;
	/** The pouch + inventory viewers fold under one "Inventory" header (Luke). */
	private boolean inventoryCollapsed;
	private JPanel buttonsRow;
	private boolean started;

	@Inject
	public LoadoutLabModule(LoadoutLabPlugin lab, EventBus eventBus, IronHubConfig config,
		AccountState state, ClientThread clientThread, net.runelite.api.Client client,
		ItemManager itemManager, com.google.gson.Gson gson, okhttp3.OkHttpClient httpClient,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager)
	{
		this.lab = lab;
		this.eventBus = eventBus;
		this.config = config;
		this.state = state;
		this.clientThread = clientThread;
		this.client = client;
		this.itemManager = itemManager;
		this.gson = gson;
		this.httpClient = httpClient;
		this.collectView = new com.ironhub.ui.components.BankCollectView(
			"_ironhubloadout_", bankTagsService, tagManager, layoutManager, itemManager);
	}

	@Override
	public String name()
	{
		return "Gear & Combat"; // renamed from "Loadout" (Luke, 2026-07-21)
	}

	@Override
	public boolean enabled()
	{
		return config.loadoutLab();
	}

	@Override
	public void startUp()
	{
		if (httpClient != null)
		{
			strategyClient = new com.ironhub.modules.loadout.StrategyClient(
				httpClient, gson, new com.ironhub.data.ItemNameIndex(gson));
		}
		// combat line above the live view: attack style varp + autocast varbit
		// + the weapon category that names the style buttons
		state.watchVarps(VarPlayer.ATTACK_STYLE);
		state.watchVarbits(AUTOCAST_SPELL, WEAPON_CATEGORY);
		// the upstream panel styles itself at construction from this seam
		com.loadoutlab.ui.LoadoutLabPanel.setIronHubTheme(config.osrsTheme());
		lab.setPanelReadyCallback(() -> SwingUtilities.invokeLater(() ->
		{
			wireHooks();
			mountPanel();
		}));
		eventBus.register(lab);
		eventBus.register(this); // bank collect for the viewed setup
		lab.startUp();
		state.addListener(listener);
		started = true;
	}

	@Override
	public void shutDown()
	{
		if (started)
		{
			state.removeListener(listener);
			eventBus.unregister(this);
			eventBus.unregister(lab);
			lab.shutDown();
			started = false;
		}
		if (clientThread != null)
		{
			clientThread.invoke(collectView::clear);
		}
		holder = null;
		strip = null;
		gatedListener = null;
	}

	@Override
	public JComponent buildTab()
	{
		if (holder == null)
		{
			theme = config.osrsTheme();
			lastViewFp = 0;
			holder = new JPanel(new BorderLayout());
			// frameless on the theme backing: the hub provides the frame and
			// the header plate; the upstream lab panel keeps its own look
			holder.setOpaque(true);
			holder.setBackground(theme.background);
			// setup section FIRST (Luke): live gear view + saved setups at
			// the top, activity strip below, the lab panel underneath
			JPanel top = new JPanel();
			top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
			top.setOpaque(false);
			top.add(buildSetupSection());
			top.add(buildStrip());
			holder.add(top, BorderLayout.NORTH);
			mountPanel();
			// full pass, not just the strip: with the pre-build listener now a
			// no-op, the first build must also auto-follow the current activity
			onStateChanged();
			gatedListener = com.ironhub.ui.components.RebuildGate.install(
				holder, this::onStateChanged);
		}
		return holder;
	}

	/**
	 * A theme flip re-clothes everything: the wrapper chrome is dropped (the
	 * next mount rebuilds it), and because the upstream panel styles itself
	 * at construction, the lab is RESTARTED so a fresh panel arrives in the
	 * new theme via the same panelReadyCallback. Runs on the config-event
	 * thread, exactly like the module's own lifecycle does.
	 */
	@Override
	public void onThemeChanged()
	{
		if (started)
		{
			com.loadoutlab.ui.LoadoutLabPanel.setIronHubTheme(config.osrsTheme());
			eventBus.unregister(lab);
			lab.shutDown();
			eventBus.register(lab);
			lab.startUp();
		}
		SwingUtilities.invokeLater(() ->
		{
			holder = null;
			strip = null;
			gatedListener = null; // the gate watched the dropped holder
		});
	}

	// ── the setup section: live view, saved setups, diff, slot search ─

	private JPanel buildSetupSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));

		// Save/View-all sit BELOW the equipment viewer (Luke, 2026-07-21) —
		// built once here, re-added into the render flow each pass
		buttonsRow = new JPanel();
		buttonsRow.setLayout(new BoxLayout(buttonsRow, BoxLayout.X_AXIS));
		buttonsRow.setOpaque(false);
		buttonsRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		com.ironhub.ui.osrs.StoneButton save = new com.ironhub.ui.osrs.StoneButton(
			theme, theme.boxFill, "Save setup", this::saveNamedSetup);
		save.setToolTipText("Save what the view shows under a name");
		com.ironhub.ui.osrs.StoneButton viewAll = new com.ironhub.ui.osrs.StoneButton(
			theme, theme.boxFill, "View all setups", this::toggleAllSetups);
		viewAll.setToolTipText("List every saved setup; click one to compare it"
			+ " against what you are wearing and carrying");
		buttonsRow.add(save);
		buttonsRow.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		buttonsRow.add(viewAll);
		buttonsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttonsRow.getPreferredSize().height));

		liveButton = new com.ironhub.ui.osrs.StoneButton(
			theme, theme.boxFill, "Back to live view", this::backToLive);
		liveButton.setToolTipText("Stop viewing the setup and show what you"
			+ " currently wear and carry");
		liveButton.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		liveButton.setVisible(false);

		namesPanel.setLayout(new BoxLayout(namesPanel, BoxLayout.Y_AXIS));
		namesPanel.setOpaque(false);
		namesPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		namesPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		namesPanel.setVisible(false);

		setupView.setLayout(new BoxLayout(setupView, BoxLayout.Y_AXIS));
		setupView.setOpaque(false);
		setupView.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		setupView.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		section.add(setupView);

		buildSearchPanel();
		section.add(searchPanel);
		return section;
	}

	private void buildSearchPanel()
	{
		searchPanel.removeAll();
		searchPanel.setLayout(new BoxLayout(searchPanel, BoxLayout.Y_AXIS));
		searchPanel.setOpaque(false);
		searchPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		searchPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		searchPanel.setVisible(false);

		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		searchTitleHolder.setLayout(new BoxLayout(searchTitleHolder, BoxLayout.X_AXIS));
		searchTitleHolder.setOpaque(false);
		head.add(searchTitleHolder);
		head.add(Box.createHorizontalGlue());
		com.ironhub.ui.osrs.StoneButton cancel = new com.ironhub.ui.osrs.StoneButton(
			theme, theme.boxFill, "Cancel", () ->
		{
			searchPanel.setVisible(false);
			holder.revalidate();
			holder.repaint();
		});
		cancel.setMaximumSize(cancel.getPreferredSize());
		head.add(cancel);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		searchPanel.add(head);
		searchPanel.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		searchField = new com.ironhub.ui.osrs.StoneTextField(theme, "Item name…");
		searchField.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				updateSearchResults();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				updateSearchResults();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				updateSearchResults();
			}
		});
		searchPanel.add(searchField);

		searchResults.setLayout(new BoxLayout(searchResults, BoxLayout.Y_AXIS));
		searchResults.setOpaque(false);
		searchResults.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		searchResults.setBorder(new EmptyBorder(UiTokens.ROW_GAP, 0, 0, 0));
		searchPanel.add(searchResults);
	}

	private com.ironhub.data.ItemNameIndex nameIndex()
	{
		if (nameIndex == null)
		{
			nameIndex = new com.ironhub.data.ItemNameIndex(gson);
		}
		return nameIndex;
	}

	private boolean isLive()
	{
		return draft == null && (viewedSetup == null || state.savedSetup(viewedSetup) == null);
	}

	/** The setup currently being viewed (draft wins), or null when live. */
	private PersistedState.SavedSetup viewedOrDraft()
	{
		if (draft != null)
		{
			return draft;
		}
		return viewedSetup != null ? state.savedSetup(viewedSetup) : null;
	}

	private void toggleAllSetups()
	{
		namesOpen = !namesOpen;
		namesPanel.setVisible(namesOpen);
		lastViewFp = 0;
		renderView();
	}

	private void viewSetup(String name)
	{
		viewedSetup = name;
		draft = null;
		viewSource = ViewSource.LIVE; // viewing a setup IS "doing something else"
		searchPanel.setVisible(false);
		lastViewFp = 0;
		renderView();
		applyBankView();
	}

	private void backToLive()
	{
		viewedSetup = null;
		draft = null;
		searchPanel.setVisible(false);
		lastViewFp = 0;
		renderView();
		applyBankView();
	}

	// ── slayer/fight auto-follow (unchanged) ──────────────────────────

	/** The activity card: what the module is auto-following, plus wiki tips. */
	private JPanel buildStrip()
	{
		strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setOpaque(false);
		strip.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));

		com.ironhub.ui.osrs.StonePanel card = new com.ironhub.ui.osrs.StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JPanel headerRow = new JPanel();
		headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.X_AXIS));
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		headerRow.add(new com.ironhub.ui.osrs.OsrsLabel("Activity",
			com.ironhub.ui.osrs.OsrsSkin.MUTED, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
		headerRow.add(Box.createHorizontalGlue());
		com.ironhub.ui.osrs.StoneButton tips = new com.ironhub.ui.osrs.StoneButton(
			theme, theme.boxFill, "Wiki tips", this::fetchTips);
		tips.setToolTipText("Fetch tips for this task/boss from its wiki strategy page"
			+ " (user-initiated request); click again to hide them");
		tips.setMaximumSize(tips.getPreferredSize());
		headerRow.add(tips);
		headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, headerRow.getPreferredSize().height));
		card.add(headerRow);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		activityHolder.setLayout(new BoxLayout(activityHolder, BoxLayout.Y_AXIS));
		activityHolder.setOpaque(false);
		activityHolder.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.add(activityHolder);

		tipsPanel.setLayout(new BoxLayout(tipsPanel, BoxLayout.Y_AXIS));
		tipsPanel.setOpaque(false);
		tipsPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		tipsPanel.setVisible(false);
		tipsPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		tipsPanel.setToolTipText("Click to dismiss");
		tipsPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tipsPanel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				tipsPanel.setVisible(false);
				strip.revalidate();
				strip.repaint();
			}
		});
		card.add(tipsPanel);

		strip.add(card);
		return strip;
	}

	/** The activity the tips/setups serve: whatever changed LAST (a fresh
	 *  task assignment or a new fight both re-aim the calc — Luke). */
	private String activity()
	{
		return !lastAutoSelected.isEmpty() ? lastAutoSelected
			: !state.getCombatNpcName().isEmpty()
			? state.getCombatNpcName() : state.getSlayerTask();
	}

	/** Change-edge snapshots: whichever of task/fight moved most RECENTLY
	 *  wins the auto-follow, so a new assignment re-runs the calc even
	 *  mid-fight and a new fight re-runs it even mid-task (Luke, 2026-07-21). */
	private String seenTask = "";
	private String seenNpc = "";

	private void onStateChanged()
	{
		refreshStrip();
		if (!config.labFollowActivity() || lab.getPanel() == null)
		{
			return;
		}
		String task = state.getSlayerTask();
		String npc = state.getCombatNpcName();
		String follow = null;
		Integer npcId = null;
		if (!task.equals(seenTask))
		{
			seenTask = task;
			if (!task.isEmpty())
			{
				follow = task;
			}
		}
		if (!npc.equals(seenNpc))
		{
			seenNpc = npc;
			if (!npc.isEmpty())
			{
				follow = npc; // the fight is the more immediate context
				npcId = state.getCombatNpcId();
			}
		}
		if (follow != null && !follow.equals(lastAutoSelected))
		{
			lastAutoSelected = follow;
			lab.getPanel().selectExternal(follow, npcId);
		}
	}

	private void refreshStrip()
	{
		if (strip == null)
		{
			return;
		}
		String task = state.getSlayerTask();
		String fighting = state.getCombatNpcName();
		StringBuilder line = new StringBuilder();
		if (!task.isEmpty())
		{
			line.append("Task: ").append(task);
		}
		if (!fighting.isEmpty())
		{
			line.append(line.length() > 0 ? " · " : "").append("Last fought: ").append(fighting);
		}
		activityLine = line.length() > 0 ? line.toString() : "No task or fight detected yet";
		activityHolder.removeAll();
		com.ironhub.ui.osrs.OsrsLabel text = com.ironhub.ui.osrs.OsrsLabel.wrapped(
			activityLine, 180, com.ironhub.ui.osrs.OsrsSkin.LABEL,
			com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
		text.setToolTipText(activityLine);
		activityHolder.add(text);
		activityHolder.revalidate();
		activityHolder.repaint();
		renderView();
	}

	// ── wiki tips ─────────────────────────────────────────────────────

	private void fetchTips()
	{
		// second press = dismiss (the tips had no way to close)
		if (tipsPanel.isVisible())
		{
			tipsPanel.setVisible(false);
			strip.revalidate();
			strip.repaint();
			return;
		}
		String activity = activity();
		if (strategyClient == null || activity.isEmpty())
		{
			return;
		}
		boolean slayerTask = activity.equals(state.getSlayerTask());
		strategyClient.fetch(activity, slayerTask, strategies ->
			SwingUtilities.invokeLater(() -> showTips(activity, strategies)));
	}

	private void showTips(String activity, List<com.ironhub.modules.loadout.WikiStrategy> strategies)
	{
		tipsPanel.removeAll();
		boolean any = false;
		for (com.ironhub.modules.loadout.WikiStrategy strategy : strategies)
		{
			if (!strategy.notes.isEmpty())
			{
				if (any)
				{
					tipsPanel.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				}
				any = true;
				tipsPanel.add(new com.ironhub.ui.osrs.OsrsLabel(strategy.name(),
					com.ironhub.ui.osrs.OsrsSkin.MUTED,
					com.ironhub.ui.osrs.OsrsSkin.boldFont()).leftAligned());
				tipsPanel.add(com.ironhub.ui.osrs.OsrsLabel.wrapped(strategy.notes, 180,
					com.ironhub.ui.osrs.OsrsSkin.MUTED,
					com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
			}
		}
		if (!any)
		{
			tipsPanel.add(com.ironhub.ui.osrs.OsrsLabel.wrapped(
				"No wiki tips found for " + activity, 180,
				com.ironhub.ui.osrs.OsrsSkin.FAINT,
				com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
		}
		tipsPanel.setVisible(true);
		strip.revalidate();
	}

	// ── saving setups ─────────────────────────────────────────────────

	/** Save-with-name: saves what the view SHOWS — the draft or viewed
	 *  setup when one is up, else a live capture (gear+inv+pouch). */
	private void saveNamedSetup()
	{
		// the shared button serves the DPS Calc too (Luke): in DPS view it
		// saves the SUGGESTED loadout under a monster-flavoured name
		boolean dps = viewSource == ViewSource.DPS && isLive() && suggestionSetup(dpsStyle) != null;
		String suggested = dps
			? (dpsMonster == null ? "DPS setup" : dpsMonster.getName() + " · " + dpsStyle)
			: viewedSetup != null ? viewedSetup
			: activity().isEmpty() ? "My setup" : activity();
		String name = (String) javax.swing.JOptionPane.showInputDialog(holder,
			"Setup name:", "Save setup", javax.swing.JOptionPane.PLAIN_MESSAGE,
			null, null, suggested);
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		name = name.trim();
		PersistedState.SavedSetup source = dps ? suggestionSetup(dpsStyle) : viewedOrDraft();
		if (source != null)
		{
			state.saveSetup(name, copy(source)); // persists + notifies
			draft = null;
			viewedSetup = name;
			lastViewFp = 0;
			renderView();
			applyBankView();
		}
		else
		{
			captureSetup(name);
		}
	}

	private void captureSetup(String key)
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		int[] worn = state.getEquipmentSlots();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (slot.getSlotIdx() < worn.length && worn[slot.getSlotIdx()] > 0)
			{
				setup.equipment.put(slot.name(), worn[slot.getSlotIdx()]);
			}
		}
		setup.inventory = state.getInventorySlots();
		setup.inventoryQty = new int[setup.inventory.length];
		Map<Integer, Integer> quantities = state.getInventorySnapshot();
		for (int i = 0; i < setup.inventory.length; i++)
		{
			setup.inventoryQty[i] = setup.inventory[i] > 0
				? quantities.getOrDefault(setup.inventory[i], 1) : 0;
		}
		if (clientThread != null && client != null)
		{
			clientThread.invoke(() ->
			{
				int[] runes = new int[POUCH_RUNE_VARBITS.length];
				int[] amounts = new int[POUCH_RUNE_VARBITS.length];
				net.runelite.api.EnumComposition runeEnum =
					client.getEnum(net.runelite.api.EnumID.RUNEPOUCH_RUNE);
				for (int i = 0; i < POUCH_RUNE_VARBITS.length; i++)
				{
					int runeIndex = client.getVarbitValue(POUCH_RUNE_VARBITS[i]);
					runes[i] = runeIndex > 0 ? runeEnum.getIntValue(runeIndex) : -1;
					amounts[i] = runeIndex > 0 ? client.getVarbitValue(POUCH_AMOUNT_VARBITS[i]) : 0;
				}
				setup.pouchRunes = runes;
				setup.pouchAmounts = amounts;
				state.saveSetup(key, setup); // persists + notifies (re-renders)
			});
		}
		else
		{
			state.saveSetup(key, setup);
		}
	}

	private static PersistedState.SavedSetup copy(PersistedState.SavedSetup s)
	{
		PersistedState.SavedSetup c = new PersistedState.SavedSetup();
		c.equipment = new HashMap<>(s.equipment);
		c.inventory = s.inventory.clone();
		c.inventoryQty = s.inventoryQty.clone();
		c.pouchRunes = s.pouchRunes.clone();
		c.pouchAmounts = s.pouchAmounts.clone();
		return c;
	}

	// ── the live/diff view ────────────────────────────────────────────

	/**
	 * Rune pouch slot count for the view: 3 for the regular pouch, 4 for
	 * the divine (incl. trouver-locked variants); an unrecognised pouch
	 * whose 4th rune varbit is populated must be divine-sized too. 0 = no
	 * pouch carried = no pouch panel (honest).
	 */
	static int pouchSlots(Set<Integer> carriedIds, boolean fourthRuneVarbitSet)
	{
		if (carriedIds.contains(ItemID.DIVINE_RUNE_POUCH)
			|| carriedIds.contains(ItemID.DIVINE_RUNE_POUCH_L))
		{
			return 4;
		}
		if (carriedIds.contains(ItemID.RUNE_POUCH) || carriedIds.contains(ItemID.RUNE_POUCH_L))
		{
			return fourthRuneVarbitSet ? 4 : 3;
		}
		return 0;
	}

	private Set<Integer> carriedIds()
	{
		Set<Integer> ids = new HashSet<>();
		for (int id : state.getInventorySlots())
		{
			if (id > 0)
			{
				ids.add(id);
			}
		}
		for (int id : state.getEquipmentSlots())
		{
			if (id > 0)
			{
				ids.add(id);
			}
		}
		return ids;
	}

	/** Snapshot of what is worn/carried right now, pouch included. */
	private PersistedState.SavedSetup liveSetup()
	{
		PersistedState.SavedSetup live = state.captureSetup();
		int slots = pouchSlots(carriedIds(),
			state.getVarbit(Varbits.RUNE_POUCH_RUNE4) > 0);
		if (slots > 0)
		{
			List<Map.Entry<Integer, Integer>> runes =
				new ArrayList<>(state.getRunePouch().entrySet());
			runes.sort(Map.Entry.comparingByKey());
			live.pouchRunes = new int[slots];
			live.pouchAmounts = new int[slots];
			Arrays.fill(live.pouchRunes, -1);
			for (int i = 0; i < runes.size() && i < slots; i++)
			{
				live.pouchRunes[i] = runes.get(i).getKey();
				live.pouchAmounts[i] = runes.get(i).getValue();
			}
		}
		return live;
	}

	/** EDT. Rebuilds the setup view (live or diffed) + the names list. */
	private void renderView()
	{
		if (holder == null)
		{
			return;
		}
		int fp = Objects.hash(
			Arrays.hashCode(state.getEquipmentSlots()),
			Arrays.hashCode(state.getInventorySlots()),
			state.getRunePouch(),
			state.getVarp(VarPlayer.ATTACK_STYLE),
			state.getVarbit(AUTOCAST_SPELL),
			state.getVarbit(WEAPON_CATEGORY),
			weaponTypeVerified,
			spellName,
			state.savedSetupNames(),
			viewedSetup,
			draft != null ? draft.equipment : null,
			namesOpen,
			inventoryCollapsed,
			viewSource,
			dpsStyle,
			System.identityHashCode(dpsResults),
			state.getCombatNpcId());
		if (fp == lastViewFp)
		{
			return;
		}
		lastViewFp = fp;

		renderNames();
		// the DPS view shows the calc's suggestion; an explicitly viewed
		// setup/draft still wins (that IS "doing something else")
		boolean dps = viewSource == ViewSource.DPS && isLive()
			&& suggestionSetup(dpsStyle) != null;
		boolean live = isLive() && !dps;
		liveButton.setVisible(!isLive());
		PersistedState.SavedSetup shown = dps ? suggestionSetup(dpsStyle)
			: live ? liveSetup() : viewedOrDraft();

		setupView.removeAll();
		String title = dps
			? "DPS Calc · " + (dpsMonster == null ? "" : dpsMonster.getName())
			: live ? "Live gear & inventory"
			: (draft != null ? "Draft (unsaved) · vs current" : viewedSetup + " · vs current");
		com.ironhub.ui.osrs.OsrsLabel titleLabel = new com.ironhub.ui.osrs.OsrsLabel(title,
			com.ironhub.ui.osrs.OsrsSkin.MUTED, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
		setupView.add(titleLabel);
		if (!live)
		{
			com.ironhub.ui.osrs.OsrsLabel legend = new com.ironhub.ui.osrs.OsrsLabel(
				"Orange = swap · Red = deposit",
				com.ironhub.ui.osrs.OsrsSkin.FAINT, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
			legend.setToolTipText("Orange border: the setup wants this item here"
				+ " — withdraw or equip it. Red border: you carry this but the"
				+ " setup has no place for it — deposit it.");
			setupView.add(legend);
		}
		// the LIVE style line has no place over a suggested loadout
		String combat = dps ? null : combatLine();
		if (combat != null)
		{
			JPanel combatRow = new JPanel();
			combatRow.setLayout(new BoxLayout(combatRow, BoxLayout.X_AXIS));
			combatRow.setOpaque(false);
			combatRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			javax.swing.Icon typeIcon = combatIcon();
			if (typeIcon != null)
			{
				// icons ride in their own holder beside the OsrsLabel (skin rule)
				combatRow.add(new JLabel(typeIcon));
				combatRow.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
			com.ironhub.ui.osrs.OsrsLabel combatLabel = new com.ironhub.ui.osrs.OsrsLabel(combat,
				com.ironhub.ui.osrs.OsrsSkin.LABEL, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
			combatLabel.setToolTipText(combatTooltip());
			combatRow.add(combatLabel);
			combatRow.add(Box.createHorizontalGlue());
			combatRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, combatRow.getPreferredSize().height));
			setupView.add(combatRow);
		}
		setupView.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		SavedSetupView view = new SavedSetupView(theme, itemManager, state::itemName);
		// merged display: setup items, plus deposit-only current items in
		// the slots the setup leaves empty; tints from the pure diff. The
		// DPS suggestion diffs its EQUIPMENT vs current (orange = swap in)
		// but never the inventory — it suggests gear, not carried items.
		PersistedState.SavedSetup display = shown;
		Map<String, Color> equipTints = null;
		Color[] invTints = null;
		if (!live)
		{
			display = copy(shown);
			equipTints = new HashMap<>();
			Map<String, SetupDiff.Slot> eq = SetupDiff.equipment(shown, state.getEquipmentSlots());
			for (Map.Entry<String, SetupDiff.Slot> entry : eq.entrySet())
			{
				SetupDiff.Slot slot = entry.getValue();
				if (slot.itemId > 0)
				{
					display.equipment.put(entry.getKey(), slot.itemId);
				}
				else
				{
					display.equipment.remove(entry.getKey());
				}
				Color tint = tintColor(slot.tint);
				if (tint != null)
				{
					equipTints.put(entry.getKey(), tint);
				}
			}
			if (!dps)
			{
				SetupDiff.Slot[] inv = SetupDiff.inventory(shown, state.getInventorySlots());
				display.inventory = new int[28];
				display.inventoryQty = new int[28];
				invTints = new Color[28];
				Map<Integer, Integer> carriedQty = state.getInventorySnapshot();
				for (int i = 0; i < 28; i++)
				{
					display.inventory[i] = inv[i].itemId;
					if (inv[i].itemId > 0)
					{
						// deposit-only slots show the CURRENT item; its stack
						// count comes from what is actually carried
						display.inventoryQty[i] = inv[i].tint == SetupDiff.Tint.DEPOSIT
							? carriedQty.getOrDefault(inv[i].itemId, 1)
							: (i < shown.inventoryQty.length ? Math.max(1, shown.inventoryQty[i]) : 1);
					}
					invTints[i] = tintColor(inv[i].tint);
				}
			}
		}

		// the source switch sits directly ABOVE the gear viewer (Luke): which
		// gear the viewer shows — your current equipment or the calc's pick
		if (dpsResults != null && isLive())
		{
			com.ironhub.ui.osrs.StoneChipRow sourceChips =
				new com.ironhub.ui.osrs.StoneChipRow(theme, true, "Current gear", "DPS Calc");
			sourceChips.setSelected(dps ? 1 : 0);
			sourceChips.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			sourceChips.onChange(i ->
			{
				viewSource = i == 1 ? ViewSource.DPS : ViewSource.LIVE;
				lastViewFp = 0;
				renderView();
			});
			setupView.add(sourceChips);
			setupView.add(Box.createVerticalStrut(2));
		}
		if (dps)
		{
			setupView.add(styleButtonsRow());
			setupView.add(Box.createVerticalStrut(2));
		}

		setupView.add(centered(view.equipment(display, equipTints, dps ? null : this::openSlotSearch)));

		// the setup controls live directly under the worn-equipment view
		// (Luke, 2026-07-21), then the shared stat tile, then the setups list
		setupView.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		setupView.add(buttonsRow);
		if (lab.getPanel() != null)
		{
			com.loadoutlab.ui.LoadoutLabPanel.TileStats tileStats = dps
				? lab.getPanel().suggestionStats(dpsStyle) : liveTileStats();
			if (tileStats != null)
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				setupView.add(lab.getPanel().statsTile(tileStats));
			}
		}
		setupView.add(Box.createVerticalStrut(2));
		setupView.add(liveButton);
		setupView.add(namesPanel);

		// rune pouch + inventory fold under ONE "Inventory" section (Luke)
		boolean hasPouch = display.pouchRunes.length > 0
			&& Arrays.stream(display.pouchRunes).anyMatch(r -> r > 0);
		boolean hasInventory = display.inventory.length > 0;
		if (hasPouch || hasInventory)
		{
			setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			setupView.add(sectionToggle("Inventory", inventoryCollapsed, () ->
			{
				inventoryCollapsed = !inventoryCollapsed;
				lastViewFp = 0;
				renderView();
			}));
			if (!inventoryCollapsed)
			{
				if (hasPouch)
				{
					setupView.add(Box.createVerticalStrut(2));
					setupView.add(smallLabel("Rune pouch"));
					setupView.add(Box.createVerticalStrut(2));
					setupView.add(centered(view.runePouch(display)));
				}
				if (hasInventory)
				{
					setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
					setupView.add(centered(view.inventory(display, invTints)));
				}
			}
		}

		setupView.revalidate();
		setupView.repaint();
		holder.revalidate();
		holder.repaint();
	}

	private static Color tintColor(SetupDiff.Tint tint)
	{
		switch (tint)
		{
			case SWAP:
				return com.ironhub.ui.osrs.OsrsSkin.TITLE;       // orange: withdraw/equip
			case DEPOSIT:
				return UiTokens.STATUS_WARNING;                  // red: no place in the setup
			default:
				return null;
		}
	}

	/**
	 * The selected spell or attack style, or null pre-login. Named honestly
	 * (Luke, 2026-07-21): "Stab: Controlled (Lunge)" from the weapon-styles
	 * pack once the LIVE cache cross-check vouches for the weapon type's row
	 * (see WeaponStylesPack.matchesKinds); the autocast spell's own cache
	 * name + " (Spell)". Anything unverified falls back to the game's raw
	 * numbers — never an invented name.
	 */
	private String combatLine()
	{
		if (state.getEquipmentSlots().length == 0 && state.getInventorySlots().length == 0)
		{
			return null; // nothing ingested yet — unknown, so silent
		}
		int spell = state.getVarbit(AUTOCAST_SPELL);
		if (spell > 0)
		{
			String name = spellName;
			return name != null && !name.isEmpty()
				? name + " (Spell)" : "Autocast spell " + spell;
		}
		com.ironhub.data.WeaponStylesPack.Option option = styleOption();
		int idx = state.getVarp(VarPlayer.ATTACK_STYLE);
		if (option == null)
		{
			return "Attack style " + (idx + 1);
		}
		if (option.type == null)
		{
			return option.button; // bulwark's no-attack Block
		}
		if (option.button.equals(option.style) || option.button.equals(option.type))
		{
			return option.type + ": " + (option.style == null ? option.button : option.style);
		}
		return option.type + ": " + option.style + " (" + option.button + ")";
	}

	/** The pack option for the current weapon type + style index — live
	 *  clients require the cache cross-check to have vouched for the type
	 *  (headless trusts the pack; deterministic for tests). */
	private com.ironhub.data.WeaponStylesPack stylesPack()
	{
		com.ironhub.data.WeaponStylesPack pack = stylesPack;
		if (pack == null)
		{
			// lazy — DataPack.load is memoized centrally, and the headless
			// tab (no startUp) still names styles deterministically
			pack = new com.ironhub.data.DataPack(gson)
				.load("weapon-styles", com.ironhub.data.WeaponStylesPack.class);
			stylesPack = pack;
		}
		return pack;
	}

	private com.ironhub.data.WeaponStylesPack.Option styleOption()
	{
		com.ironhub.data.WeaponStylesPack stylesPack = stylesPack();
		int weaponType = state.getVarbit(WEAPON_CATEGORY);
		if (client != null && !(weaponType == namedWeaponType && weaponTypeVerified))
		{
			return null;
		}
		return stylesPack.option(weaponType, state.getVarp(VarPlayer.ATTACK_STYLE));
	}

	/** The attack-type icon beside the combat line (wiki's own set), or null. */
	private javax.swing.Icon combatIcon()
	{
		String type = null;
		if (state.getVarbit(AUTOCAST_SPELL) > 0)
		{
			type = "magic";
		}
		else
		{
			com.ironhub.data.WeaponStylesPack.Option option = styleOption();
			if (option != null && option.type != null)
			{
				type = option.type.toLowerCase(Locale.ROOT);
			}
		}
		if (type == null)
		{
			return null;
		}
		java.awt.image.BufferedImage img =
			com.ironhub.ui.osrs.OsrsIcons.image(theme, "styles/" + type);
		return img == null ? null
			: new javax.swing.ImageIcon(img.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH));
	}

	private String combatTooltip()
	{
		int spell = state.getVarbit(AUTOCAST_SPELL);
		if (spell > 0)
		{
			return spellName != null && !spellName.isEmpty()
				? "The spell set to autocast"
				: "The game's autocast spell number — its name is not in the cache yet";
		}
		return styleOption() != null
			? "The selected combat style: attack type, style and the button's own name"
			: "The selected combat style slot; this weapon type's style names"
				+ " could not be verified against the game cache";
	}

	/**
	 * Client-thread, change-guarded: verify the weapon type's pack row
	 * against the cache's style-kind signature (the AttackStylesPlugin
	 * enum walk) and resolve the autocast spell's display name (enum 1986 →
	 * spell obj → SPELL_NAME param — the game's own autocast script route).
	 */
	private void refreshCombatNames()
	{
		if (client == null || !client.isClientThread())
		{
			return;
		}
		int weaponType = state.getVarbit(WEAPON_CATEGORY);
		if (weaponType != namedWeaponType)
		{
			weaponTypeVerified = stylesPack().matchesKinds(weaponType, cacheStyleKinds(weaponType));
			namedWeaponType = weaponType;
		}
		int spell = state.getVarbit(AUTOCAST_SPELL);
		if (spell != namedSpell)
		{
			spellName = spell > 0 ? cacheSpellName(spell) : null;
			namedSpell = spell;
		}
	}

	/** Param-1407 style kinds by option index for a weapon type, or null
	 *  when the cache doesn't map it (e.g. blue moon spear). Client thread. */
	private String[] cacheStyleKinds(int weaponType)
	{
		try
		{
			int sub = client.getEnum(net.runelite.api.EnumID.WEAPON_STYLES).getIntValue(weaponType);
			if (sub == -1)
			{
				return null;
			}
			int[] structs = client.getEnum(sub).getIntVals();
			String[] kinds = new String[structs.length];
			for (int i = 0; i < structs.length; i++)
			{
				kinds[i] = client.getStructComposition(structs[i])
					.getStringValue(net.runelite.api.ParamID.ATTACK_STYLE_NAME);
			}
			return kinds;
		}
		catch (RuntimeException e)
		{
			return null; // unreadable cache = unverified, never a guess
		}
	}

	private String cacheSpellName(int spell)
	{
		try
		{
			int itemId = client.getEnum(AUTOCAST_SPELLS_ENUM).getIntValue(spell);
			return itemId > 0 ? client.getItemDefinition(itemId)
				.getStringValue(net.runelite.api.ParamID.SPELL_NAME) : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** The saved-setups list: the Design lab's checklist grammar without the
	 *  checkboxes — rows in one notched frame, whole-row hover/hit — inside a
	 *  stone-scrolled viewport so a long list stays short (Luke, 2026-07-21). */
	private void renderNames()
	{
		namesPanel.removeAll();
		if (!namesOpen)
		{
			return;
		}
		List<String> names = state.savedSetupNames();
		if (names.isEmpty())
		{
			namesPanel.add(new com.ironhub.ui.osrs.OsrsLabel("No saved setups yet",
				com.ironhub.ui.osrs.OsrsSkin.FAINT,
				com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
			return;
		}
		JPanel list = new JPanel()
		{
			@Override
			public Dimension getPreferredSize()
			{
				Dimension d = super.getPreferredSize();
				// track the viewport width so rows fill the frame
				java.awt.Container parent = getParent();
				return parent instanceof javax.swing.JViewport
					? new Dimension(parent.getWidth(), d.height) : d;
			}
		};
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		for (String name : names)
		{
			boolean active = draft == null && name.equals(viewedSetup);
			list.add(setupRow(name, active));
		}

		com.ironhub.ui.osrs.StonePanel frame = new com.ironhub.ui.osrs.StonePanel(theme);
		frame.setLayout(new BorderLayout());
		frame.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(list,
			javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
		{
			@Override
			public Dimension getPreferredSize()
			{
				Dimension d = super.getPreferredSize();
				return new Dimension(d.width, Math.min(d.height, 132)); // ~6 rows
			}
		};
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		com.ironhub.ui.osrs.StoneScrollBarUI.skin(scroll.getVerticalScrollBar(), theme);
		scroll.getVerticalScrollBar().setUnitIncrement(22);
		frame.add(scroll, BorderLayout.CENTER);
		frame.setMaximumSize(new Dimension(Integer.MAX_VALUE, frame.getPreferredSize().height));
		namesPanel.add(frame);
	}

	/** One setup row: name in the checklist-row look (hover fill, whole-row
	 *  hit target), TITLE-orange when it is the one being viewed. */
	private JComponent setupRow(String name, boolean active)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(true);
		row.setBackground(theme.boxFill);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(3, 6, 3, 6));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		com.ironhub.ui.osrs.OsrsLabel label = new com.ironhub.ui.osrs.OsrsLabel(name,
			active ? com.ironhub.ui.osrs.OsrsSkin.TITLE : com.ironhub.ui.osrs.OsrsSkin.LABEL,
			com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned().squeezable();
		String tip = active ? "Viewing — click again for the live view"
			: "Compare this setup against what you wear and carry";
		row.setToolTipText(tip);
		label.setToolTipText(tip);
		row.add(label);
		row.add(Box.createHorizontalGlue());
		java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				row.setBackground(theme.hoverFill);
				row.repaint();
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				row.setBackground(theme.boxFill);
				row.repaint();
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				if (active)
				{
					backToLive();
				}
				else
				{
					viewSetup(name);
				}
			}
		};
		row.addMouseListener(click);
		label.addMouseListener(click);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ── per-slot item search (edits a draft) ──────────────────────────

	private void openSlotSearch(EquipmentInventorySlot slot)
	{
		searchSlot = slot;
		searchTitleHolder.removeAll();
		searchTitleHolder.add(new com.ironhub.ui.osrs.OsrsLabel(
			"Replace " + slot.name().toLowerCase(Locale.ROOT).replace('_', ' '),
			com.ironhub.ui.osrs.OsrsSkin.MUTED,
			com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
		searchField.setText("");
		searchResults.removeAll();
		searchPanel.setVisible(true);
		holder.revalidate();
		holder.repaint();
		searchField.requestFocusInWindow();
	}

	private void updateSearchResults()
	{
		searchResults.removeAll();
		String query = searchField.getText().trim();
		if (query.length() >= 2)
		{
			for (String name : nameIndex().search(query, 8))
			{
				com.ironhub.ui.osrs.OsrsLabel row = new com.ironhub.ui.osrs.OsrsLabel(name,
					com.ironhub.ui.osrs.OsrsSkin.MUTED,
					com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
				row.setToolTipText("Put this item in the slot (edits the viewed setup)");
				row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				row.addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mousePressed(java.awt.event.MouseEvent e)
					{
						pickSearchResult(name);
					}
				});
				searchResults.add(row);
			}
		}
		searchResults.revalidate();
		searchResults.repaint();
		holder.revalidate();
	}

	private void pickSearchResult(String name)
	{
		Integer id = nameIndex().idOf(name);
		if (id == null || searchSlot == null)
		{
			return; // unresolvable pick changes nothing
		}
		if (draft == null)
		{
			// viewing live or a saved setup: edits fork an unsaved draft
			PersistedState.SavedSetup base = viewedOrDraft();
			draft = copy(base != null ? base : liveSetup());
		}
		draft.equipment.put(searchSlot.name(), id);
		searchPanel.setVisible(false);
		lastViewFp = 0;
		renderView();
		applyBankView();
	}

	// ── bank collect for the viewed setup ─────────────────────────────

	/** Setup items in display order (gear layout, inventory, pouch). */
	private static List<Integer> setupOrder(PersistedState.SavedSetup setup)
	{
		List<Integer> order = new ArrayList<>(layoutOrder(setup).values());
		for (int id : setup.inventory)
		{
			if (id > 0)
			{
				order.add(id);
			}
		}
		for (int id : setup.pouchRunes)
		{
			if (id > 0)
			{
				order.add(id);
			}
		}
		return order;
	}

	/** What the viewed setup still needs withdrawn, in setup order. */
	private List<Integer> withdrawList(PersistedState.SavedSetup setup)
	{
		Set<Integer> need = state.setupItemsToWithdraw(setup);
		List<Integer> out = new ArrayList<>();
		for (int id : setupOrder(setup))
		{
			if (need.contains(id) && !out.contains(id))
			{
				out.add(id);
			}
		}
		return out;
	}

	/** Apply/clear the collected bank view for the current viewing state
	 *  (live = clear). Safe with the bank closed — the tag opens on the
	 *  bank's next build via onScriptPreFired. */
	private void applyBankView()
	{
		if (clientThread == null)
		{
			return;
		}
		PersistedState.SavedSetup shown = viewedOrDraft();
		List<Integer> list = shown == null ? List.of() : withdrawList(shown);
		clientThread.invoke(() ->
		{
			if (list.isEmpty())
			{
				collectView.clear();
			}
			else
			{
				collectView.apply(list);
			}
		});
	}

	/** Bank building while a setup is viewed: collect what it still needs
	 *  (recomputed — carried items change as the player withdraws). */
	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_INIT
			|| viewedOrDraft() == null || clientThread == null)
		{
			return;
		}
		// AFTER the init script finishes — opening a tag mid-init forces an
		// extra relayout; the apply no-ops when our tag is already active
		clientThread.invokeLater(() ->
		{
			PersistedState.SavedSetup shown = viewedOrDraft();
			if (shown == null)
			{
				return;
			}
			List<Integer> list = withdrawList(shown);
			if (list.isEmpty())
			{
				collectView.clear();
			}
			else
			{
				collectView.apply(list);
			}
		});
	}

	/** Readable bank title while collected (Bank Tags shows the raw hidden
	 *  tag name otherwise — the farm-run lesson). */
	@Subscribe
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
			String name = draft != null ? "Draft setup" : viewedSetup;
			title.setText("<col=ff981f>" + name + "</col> — Iron Hub");
		}
	}

	/** Centre a fixed-size canvas in the tab's width. */
	private static JPanel centered(java.awt.Component inner)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.add(Box.createHorizontalGlue());
		row.add(inner);
		row.add(Box.createHorizontalGlue());
		return row;
	}

	private JComponent smallLabel(String text)
	{
		return new com.ironhub.ui.osrs.OsrsLabel(text, com.ironhub.ui.osrs.OsrsSkin.FAINT,
			com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
	}

	/** A small collapsible sub-section header: triangle + label, whole row
	 *  the hit target (the Inventory fold). */
	private JComponent sectionToggle(String text, boolean collapsed, Runnable onToggle)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new com.ironhub.ui.components.PaintedIcon(collapsed
			? com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_RIGHT
			: com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		triangle.setForeground(com.ironhub.ui.osrs.OsrsSkin.MUTED);
		row.add(triangle);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		com.ironhub.ui.osrs.OsrsLabel label = new com.ironhub.ui.osrs.OsrsLabel(text,
			com.ironhub.ui.osrs.OsrsSkin.MUTED, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
		row.add(label);
		row.add(Box.createHorizontalGlue());
		java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				onToggle.run();
			}
		};
		row.addMouseListener(toggle);
		label.addMouseListener(toggle);
		triangle.addMouseListener(toggle);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** The lab panel arrives async (its ~3MB dataset parses off-thread). */
	private void mountPanel()
	{
		if (holder == null)
		{
			return;
		}
		// keep the NORTH strip; swap only the CENTER content
		BorderLayout layout = (BorderLayout) holder.getLayout();
		java.awt.Component center = layout.getLayoutComponent(BorderLayout.CENTER);
		if (center != null)
		{
			holder.remove(center);
		}
		if (lab.getPanel() != null)
		{
			// collapsible DPS Calc section wrapping the whole lab panel — the
			// header wears the MODULE-PLATE grammar (notched stone, bold TITLE
			// text centred, triangle left) so it reads like the hub's other
			// section headers (Luke, 2026-07-21)
			JPanel section = new JPanel(new BorderLayout());
			section.setOpaque(false);
			com.ironhub.ui.osrs.StonePanel plate = new com.ironhub.ui.osrs.StonePanel(theme);
			plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
			plate.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			plate.setToolTipText("Show or hide the DPS calculator");
			JLabel triangleLabel = new JLabel(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
			triangleLabel.setForeground(com.ironhub.ui.osrs.OsrsSkin.MUTED);
			plate.add(triangleLabel);
			plate.add(Box.createHorizontalGlue());
			com.ironhub.ui.osrs.OsrsLabel title = new com.ironhub.ui.osrs.OsrsLabel("DPS Calc",
				com.ironhub.ui.osrs.OsrsSkin.TITLE, com.ironhub.ui.osrs.OsrsSkin.boldFont());
			title.setToolTipText("Show or hide the DPS calculator");
			plate.add(title);
			plate.add(Box.createHorizontalGlue());
			// mirror the triangle's width so the title stays optically centred
			plate.add(Box.createHorizontalStrut(10));
			java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					dpsCalcCollapsed = !dpsCalcCollapsed;
					lab.getPanel().setVisible(!dpsCalcCollapsed);
					triangleLabel.setIcon(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
					// closing the calc reverts the shared viewer to live gear;
					// reopening returns to the suggestion when one exists (Luke)
					viewSource = dpsCalcCollapsed ? ViewSource.LIVE
						: dpsResults != null ? ViewSource.DPS : viewSource;
					lastViewFp = 0;
					renderView();
					holder.revalidate();
				}
			};
			// the title's tooltip swallows row clicks — children carry it too
			plate.addMouseListener(toggle);
			title.addMouseListener(toggle);
			triangleLabel.addMouseListener(toggle);
			JPanel header = new JPanel(new BorderLayout());
			header.setOpaque(false);
			header.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 4, 3, 4));
			header.add(plate, BorderLayout.CENTER);
			section.add(header, BorderLayout.NORTH);
			section.add(lab.getPanel(), BorderLayout.CENTER);
			lab.getPanel().setVisible(!dpsCalcCollapsed);
			holder.add(section, BorderLayout.CENTER);
			wireHooks();
			onStateChanged(); // panel just arrived: apply auto-follow now
		}
		else
		{
			JPanel loading = new JPanel();
			loading.setLayout(new BoxLayout(loading, BoxLayout.X_AXIS));
			loading.setOpaque(false);
			loading.add(Box.createHorizontalGlue());
			loading.add(new com.ironhub.ui.osrs.OsrsLabel("Loading gear dataset…",
				com.ironhub.ui.osrs.OsrsSkin.FAINT, com.ironhub.ui.osrs.OsrsSkin.font()));
			loading.add(Box.createHorizontalGlue());
			holder.add(loading, BorderLayout.CENTER);
		}
		holder.revalidate();
		holder.repaint();
	}

	/** The DPS Calc header triangle for the current collapse state. */
	private com.ironhub.ui.components.PaintedIcon.Shape triangle()
	{
		return dpsCalcCollapsed
			? com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_RIGHT
			: com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_DOWN;
	}

	/** Idempotent: attach all wrapper hooks once the panel exists. The
	 *  panel's Load button now toggles the all-setups list at the top
	 *  ("View all setups" replaced the old Load-setup dialog). */
	private void wireHooks()
	{
		if (lab.getPanel() != null)
		{
			lab.getPanel().setSetupHooks(this::saveNamedSetup, this::toggleAllSetups);
			lab.getPanel().setWornLookup(this::wornItemFor);
			lab.getPanel().setDpsCalcHook(this::openDpsCalc);
			lab.getPanel().setResultsListener(new com.loadoutlab.ui.LoadoutLabPanel.ResultsListener()
			{
				@Override
				public void onResults(com.loadoutlab.data.MonsterStats monster,
					java.util.Map<com.loadoutlab.engine.CombatStyle,
						com.loadoutlab.optimizer.OptimizerService.StyleResult> results)
				{
					dpsResults = results;
					dpsMonster = monster;
					if (suggestionSetup(dpsStyle) == null)
					{
						// keep a live selection where possible; else strongest
						dpsStyle = bestDpsStyle();
					}
					// fresh numbers = the calc is what the player is doing
					viewSource = ViewSource.DPS;
					lastViewFp = 0;
					renderView();
				}

				@Override
				public void onCleared()
				{
					dpsResults = null;
					dpsMonster = null;
					viewSource = ViewSource.LIVE;
					lastViewFp = 0;
					renderView();
				}
			});
		}
	}

	/**
	 * One button per combat style, each listing its suggested dps — the
	 * highest in green — switching the shared viewer, tile and the calc's
	 * detail card (Luke: buttons replaced the three expandable panels).
	 */
	private JPanel styleButtonsRow()
	{
		JPanel row = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		Double best = null;
		for (com.loadoutlab.engine.CombatStyle style : com.loadoutlab.engine.CombatStyle.concreteValues())
		{
			Double dps = suggestedDps(style);
			if (dps != null && (best == null || dps > best))
			{
				best = dps;
			}
		}
		for (com.loadoutlab.engine.CombatStyle style : com.loadoutlab.engine.CombatStyle.concreteValues())
		{
			Double dps = suggestedDps(style);
			// one decimal: three buttons share 225px and "Melee · 4.71" clips
			String label = style.toString() + " " + (dps == null ? "—" : String.format(Locale.ROOT, "%.1f", dps));
			boolean selected = style == dpsStyle;
			com.ironhub.ui.osrs.StoneButton button = new com.ironhub.ui.osrs.StoneButton(theme,
				selected ? theme.selectFill : theme.boxFill, label, dps == null ? null : () ->
			{
				dpsStyle = style;
				if (lab.getPanel() != null)
				{
					lab.getPanel().setDetailStyle(style);
				}
				lastViewFp = 0;
				renderView();
			});
			if (dps != null && best != null && dps.equals(best))
			{
				button.labelColor(com.ironhub.ui.osrs.OsrsSkin.VALUE); // best dps = green
			}
			else if (dps == null)
			{
				button.labelColor(com.ironhub.ui.osrs.OsrsSkin.FAINT);
			}
			button.setToolTipText(dps == null ? "No usable owned set for " + style
				: "Show the best owned " + style.toString().toLowerCase(Locale.ROOT) + " set");
			row.add(button);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/**
	 * The live tile: your worn gear's bonuses, with the monster-dependent
	 * numbers evaluated against the LAST monster you attacked (honest "?"
	 * before any fight) at your real levels, unboosted.
	 */
	private com.loadoutlab.ui.LoadoutLabPanel.TileStats liveTileStats()
	{
		if (lab.getPanel() == null || lab.getPanel().data() == null)
		{
			return null;
		}
		com.loadoutlab.data.LoadoutData data = lab.getPanel().data();
		java.util.Map<com.loadoutlab.data.GearSlot, com.loadoutlab.data.GearItem> gear =
			new java.util.EnumMap<>(com.loadoutlab.data.GearSlot.class);
		int[] worn = state.getEquipmentSlots();
		for (java.util.Map.Entry<String, EquipmentInventorySlot> slot : LAB_SLOTS.entrySet())
		{
			int idx = slot.getValue().getSlotIdx();
			if (idx < worn.length && worn[idx] > 0)
			{
				com.loadoutlab.data.GearItem item = data.getGear(worn[idx]);
				if (item != null)
				{
					gear.put(com.loadoutlab.data.GearSlot.valueOf(slot.getKey()), item);
				}
			}
		}
		com.loadoutlab.engine.Loadout loadout = new com.loadoutlab.engine.Loadout(gear);
		com.loadoutlab.ui.LoadoutLabPanel.TileStats stats = new com.loadoutlab.ui.LoadoutLabPanel.TileStats();
		stats.loadout = loadout;
		int npcId = state.getCombatNpcId();
		if (npcId > 0)
		{
			for (com.loadoutlab.data.MonsterStats m : data.getMonsters())
			{
				if (m.getId() == npcId)
				{
					stats.monster = m;
					break;
				}
			}
		}
		if (stats.monster != null)
		{
			try
			{
				java.util.Map<net.runelite.api.Skill, Integer> levels = new java.util.HashMap<>();
				for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
				{
					levels.put(skill, state.getRealLevel(skill));
				}
				com.loadoutlab.engine.OptimizationRequest request =
					new com.loadoutlab.engine.OptimizationRequest(stats.monster,
						liveWeaponStyle(loadout), com.loadoutlab.engine.PlayerLevels.from(levels),
						null, null, 0, null, true, false, null, 1);
				stats.result = new com.loadoutlab.engine.DpsCalculator().calculate(request, loadout);
			}
			catch (RuntimeException e)
			{
				stats.result = null; // unknowable stays "?" — never invented
			}
		}
		return stats;
	}

	/** The style the worn weapon fights in (unarmed/unknown = melee). */
	private static com.loadoutlab.engine.CombatStyle liveWeaponStyle(com.loadoutlab.engine.Loadout loadout)
	{
		com.loadoutlab.data.GearItem weapon = loadout.getWeapon();
		if (weapon != null)
		{
			for (com.loadoutlab.engine.CombatStyle style : com.loadoutlab.engine.CombatStyle.concreteValues())
			{
				if (weapon.isWeaponFor(style))
				{
					return style;
				}
			}
		}
		return com.loadoutlab.engine.CombatStyle.MELEE;
	}

	/** The strongest style by suggested dps (MELEE when nothing usable). */
	private com.loadoutlab.engine.CombatStyle bestDpsStyle()
	{
		com.loadoutlab.engine.CombatStyle best = com.loadoutlab.engine.CombatStyle.MELEE;
		double bestDps = -1;
		for (com.loadoutlab.engine.CombatStyle style : com.loadoutlab.engine.CombatStyle.concreteValues())
		{
			Double dps = suggestedDps(style);
			if (dps != null && dps > bestDps)
			{
				bestDps = dps;
				best = style;
			}
		}
		return best;
	}

	/** The best owned set's dps for a style, or null when no usable set. */
	private Double suggestedDps(com.loadoutlab.engine.CombatStyle style)
	{
		if (dpsResults == null)
		{
			return null;
		}
		com.loadoutlab.optimizer.OptimizerService.StyleResult result = dpsResults.get(style);
		return result == null || result.owned == null || result.owned.isEmpty()
			? null : result.owned.get(0).getDps();
	}

	/** The calc's suggested loadout for a style as a displayable setup. */
	private PersistedState.SavedSetup suggestionSetup(com.loadoutlab.engine.CombatStyle style)
	{
		if (dpsResults == null)
		{
			return null;
		}
		com.loadoutlab.optimizer.OptimizerService.StyleResult result = dpsResults.get(style);
		if (result == null || result.owned == null || result.owned.isEmpty())
		{
			return null;
		}
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		for (java.util.Map.Entry<com.loadoutlab.data.GearSlot, com.loadoutlab.data.GearItem> slot
			: result.owned.get(0).getLoadout().getGear().entrySet())
		{
			EquipmentInventorySlot mapped = slot.getValue() == null
				? null : LAB_SLOTS.get(slot.getKey().name());
			if (mapped != null)
			{
				setup.equipment.put(mapped.name(), slot.getValue().getId());
			}
		}
		return setup;
	}

	/** GearSlot → currently worn item id (or null). */
	private Integer wornItemFor(com.loadoutlab.data.GearSlot slot)
	{
		EquipmentInventorySlot mapped = LAB_SLOTS.get(slot.name());
		if (mapped == null)
		{
			return null;
		}
		int[] worn = state.getEquipmentSlots();
		int idx = mapped.getSlotIdx();
		return idx < worn.length && worn[idx] > 0 ? worn[idx] : null;
	}

	private static final Map<String, EquipmentInventorySlot> LAB_SLOTS = Map.ofEntries(
		Map.entry("HEAD", EquipmentInventorySlot.HEAD),
		Map.entry("CAPE", EquipmentInventorySlot.CAPE),
		Map.entry("NECK", EquipmentInventorySlot.AMULET),
		Map.entry("AMMO", EquipmentInventorySlot.AMMO),
		Map.entry("WEAPON", EquipmentInventorySlot.WEAPON),
		Map.entry("SHIELD", EquipmentInventorySlot.SHIELD),
		Map.entry("BODY", EquipmentInventorySlot.BODY),
		Map.entry("LEGS", EquipmentInventorySlot.LEGS),
		Map.entry("HANDS", EquipmentInventorySlot.GLOVES),
		Map.entry("FEET", EquipmentInventorySlot.BOOTS),
		Map.entry("RING", EquipmentInventorySlot.RING));

	/** Open the wiki DPS calc with the lab's monster + shown setup. */
	private void openDpsCalc(int monsterId, String monsterName,
		Map<com.loadoutlab.data.GearSlot, Integer> loadout, boolean onSlayerTask)
	{
		if (httpClient == null)
		{
			return;
		}
		Map<EquipmentInventorySlot, Integer> equipment =
			new java.util.EnumMap<>(EquipmentInventorySlot.class);
		loadout.forEach((slot, id) ->
		{
			EquipmentInventorySlot mapped = LAB_SLOTS.get(slot.name());
			if (mapped != null)
			{
				equipment.put(mapped, id);
			}
		});
		com.google.gson.JsonObject payload = com.ironhub.modules.loadout.DpsExport.buildPayload(
			gson, state, "Iron Hub - " + monsterName, equipment, monsterId, monsterName, onSlayerTask);
		okhttp3.Request request = new okhttp3.Request.Builder()
			.url(com.ironhub.modules.loadout.DpsExport.ENDPOINT)
			.post(okhttp3.RequestBody.create(
				okhttp3.MediaType.parse("application/json"), gson.toJson(payload)))
			.build();
		httpClient.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(okhttp3.Call call, java.io.IOException e)
			{
				log.warn("dps calc export failed", e);
			}

			@Override
			public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException
			{
				try (okhttp3.ResponseBody body = response.body())
				{
					String id = gson.fromJson(body.string(), com.google.gson.JsonObject.class)
						.get("data").getAsString();
					net.runelite.client.util.LinkBrowser.browse(
						com.ironhub.modules.loadout.DpsExport.SHARE_URL + id);
				}
			}
		});
	}

	/** Slot names → saved item ids, ordered by the OSRS layout (tests). */
	static Map<String, Integer> layoutOrder(PersistedState.SavedSetup setup)
	{
		Map<String, Integer> ordered = new LinkedHashMap<>();
		for (EquipmentInventorySlot[] row : GRID)
		{
			for (EquipmentInventorySlot slot : row)
			{
				if (slot != null && setup.equipment.containsKey(slot.name()))
				{
					ordered.put(slot.name(), setup.equipment.get(slot.name()));
				}
			}
		}
		return ordered;
	}

	// ── test seams ────────────────────────────────────────────────────

	/** Test seam: view a saved setup diffed against current. */
	void toggleAllSetupsForTest()
	{
		toggleAllSetups();
	}

	/** Test seam: force the shared viewer's source (true = DPS view). */
	public void setViewSourceForTest(boolean dps)
	{
		viewSource = dps ? ViewSource.DPS : ViewSource.LIVE;
		lastViewFp = 0;
		renderView();
	}

	void viewSetupForTest(String name)
	{
		viewSetup(name);
	}
}
