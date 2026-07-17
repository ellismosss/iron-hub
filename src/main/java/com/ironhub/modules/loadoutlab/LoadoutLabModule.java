package com.ironhub.modules.loadoutlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.loadoutlab.LoadoutLabPlugin;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.runelite.api.Varbits;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;

/**
 * Loadout Lab (github.com/ajkatz/runelite-loadout-lab, BSD-2-Clause,
 * imported whole per user direction) as a task-aware Iron Hub module.
 * The upstream engine lives untouched under {@code com.loadoutlab};
 * this wrapper adds the one-stop-shop layer: auto-follows the slayer
 * task / most recently fought or killed NPC (panel.selectExternal),
 * pulls wiki tips for the activity on demand, and remembers full
 * setups — worn gear in the OSRS slot layout, inventory (4x7) and
 * rune pouch, Inventory Setups style — per task/boss.
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

	private final LoadoutLabPlugin lab;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private final AccountState state;
	private final ClientThread clientThread;   // null in unit tests
	private final net.runelite.api.Client client; // null in unit tests
	private final ItemManager itemManager;     // null in unit tests
	private final com.google.gson.Gson gson;
	private final okhttp3.OkHttpClient httpClient; // null in unit tests
	private net.runelite.client.game.SpriteManager spriteManager; // null in unit tests

	private final Runnable listener = () -> SwingUtilities.invokeLater(this::onStateChanged);
	private com.ironhub.modules.loadout.StrategyClient strategyClient;
	private com.ironhub.ui.osrs.OsrsTheme theme;
	private JPanel holder;
	private JPanel strip;
	private final JPanel activityHolder = new JPanel();
	private final JPanel tipsPanel = new JPanel();
	private final JPanel setupView = new JPanel();
	private String activityLine = "";
	private String lastAutoSelected = "";
	private String viewedSetup; // explicit Load-setup pick, wins over the activity
	private boolean dpsCalcCollapsed;
	private boolean started;

	@Inject
	public LoadoutLabModule(LoadoutLabPlugin lab, EventBus eventBus, IronHubConfig config,
		AccountState state, ClientThread clientThread, net.runelite.api.Client client,
		ItemManager itemManager, com.google.gson.Gson gson, okhttp3.OkHttpClient httpClient,
		net.runelite.client.game.SpriteManager spriteManager)
	{
		this.spriteManager = spriteManager;
		this.lab = lab;
		this.eventBus = eventBus;
		this.config = config;
		this.state = state;
		this.clientThread = clientThread;
		this.client = client;
		this.itemManager = itemManager;
		this.gson = gson;
		this.httpClient = httpClient;
	}

	@Override
	public String name()
	{
		return "Loadout Lab";
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
		// the upstream panel styles itself at construction from this seam
		com.loadoutlab.ui.LoadoutLabPanel.setIronHubTheme(config.osrsTheme());
		lab.setPanelReadyCallback(() -> SwingUtilities.invokeLater(() ->
		{
			wireHooks();
			mountPanel();
		}));
		eventBus.register(lab);
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
			eventBus.unregister(lab);
			lab.shutDown();
			started = false;
		}
		holder = null;
		strip = null;
	}

	@Override
	public JComponent buildTab()
	{
		if (holder == null)
		{
			theme = config.osrsTheme();
			holder = new JPanel(new BorderLayout());
			// frameless on the theme backing: the hub provides the frame and
			// the header plate; the upstream lab panel keeps its own look
			holder.setOpaque(true);
			holder.setBackground(theme.background);
			holder.add(buildStrip(), BorderLayout.NORTH);
			setupView.setLayout(new BoxLayout(setupView, BoxLayout.Y_AXIS));
			setupView.setOpaque(false);
			setupView.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));
			holder.add(setupView, BorderLayout.SOUTH); // saved setup sits under the lab
			mountPanel();
			refreshStrip();
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
		});
	}

	/** The activity card: what the module is auto-following, plus wiki tips.
	 * (Save/Load setup live once, at the bottom with the saved-setup view -
	 * the old second Save button up here duplicated them.) */
	private JPanel buildStrip()
	{
		strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setOpaque(false);
		strip.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));

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

	/** Slayer task wins for planning; the last fight/kill breaks ties. */
	private String activity()
	{
		return !state.getCombatNpcName().isEmpty()
			? state.getCombatNpcName() : state.getSlayerTask();
	}

	private void onStateChanged()
	{
		refreshStrip();
		// auto-follow: select the activity's monster in the lab exactly once
		String activity = activity();
		if (config.labFollowActivity() && !activity.isEmpty()
			&& !activity.equals(lastAutoSelected) && lab.getPanel() != null)
		{
			lastAutoSelected = activity;
			boolean isNpc = activity.equals(state.getCombatNpcName());
			lab.getPanel().selectExternal(activity, isNpc ? state.getCombatNpcId() : null);
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
		renderSavedSetup();
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

	// ── remembered setups (gear + inventory + rune pouch) ─────────────

	/** Save-with-name (panel button): prompts, defaults to the activity. */
	private void saveNamedSetup()
	{
		String suggested = activity().isEmpty() ? "My setup" : activity();
		String name = (String) javax.swing.JOptionPane.showInputDialog(holder,
			"Setup name:", "Save setup", javax.swing.JOptionPane.PLAIN_MESSAGE,
			null, null, suggested);
		if (name != null && !name.trim().isEmpty())
		{
			captureSetup(name.trim());
		}
	}

	/** Load-by-name (panel button): renders the pick under the lab. */
	private void loadNamedSetup()
	{
		List<String> names = state.savedSetupNames();
		if (names.isEmpty())
		{
			javax.swing.JOptionPane.showMessageDialog(holder, "No saved setups yet.");
			return;
		}
		String pick = (String) javax.swing.JOptionPane.showInputDialog(holder,
			"Setup:", "Load setup", javax.swing.JOptionPane.PLAIN_MESSAGE,
			null, names.toArray(), names.get(0));
		if (pick != null)
		{
			viewedSetup = pick;
			renderSavedSetup();
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

	/** The remembered setup drawn as the game's own interfaces (Luke,
	 * 2026-07-17): the Worn Equipment layout with slot sprites + chain
	 * links, the rune pouch as slot tiles, and the inventory sitting on the
	 * game's framed side-panel backing. See SavedSetupView. */
	private void renderSavedSetup()
	{
		setupView.removeAll();
		PersistedState.SavedSetup saved = viewedSetup != null
			? state.savedSetup(viewedSetup) : state.savedSetup(activity());
		if (saved != null)
		{
			SavedSetupView view = new SavedSetupView(theme, itemManager, spriteManager,
				state::itemName);
			setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			setupView.add(new com.ironhub.ui.osrs.OsrsLabel("Saved setup",
				com.ironhub.ui.osrs.OsrsSkin.MUTED,
				com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned());
			setupView.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			setupView.add(centered(view.equipment(saved)));

			if (saved.pouchRunes.length > 0 && java.util.Arrays.stream(saved.pouchRunes).anyMatch(r -> r > 0))
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				setupView.add(smallLabel("Rune pouch"));
				setupView.add(Box.createVerticalStrut(2));
				setupView.add(centered(view.runePouch(saved)));
			}

			if (saved.inventory.length > 0)
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				setupView.add(smallLabel("Inventory"));
				setupView.add(Box.createVerticalStrut(2));
				setupView.add(centered(view.inventory(saved)));
			}
		}
		setupView.revalidate();
		setupView.repaint();
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
			// collapsible DPS Calc section wrapping the whole lab panel -
			// the skin's collapsible header (triangle + game label); the lab
			// panel itself keeps upstream's own look
			JPanel section = new JPanel(new BorderLayout());
			section.setOpaque(false);
			JPanel header = new JPanel();
			header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
			header.setOpaque(false);
			header.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.setToolTipText("Show or hide the DPS calculator");
			JLabel triangleLabel = new JLabel(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
			triangleLabel.setForeground(com.ironhub.ui.osrs.OsrsSkin.MUTED);
			header.add(triangleLabel);
			header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			com.ironhub.ui.osrs.OsrsLabel title = new com.ironhub.ui.osrs.OsrsLabel("DPS Calc",
				com.ironhub.ui.osrs.OsrsSkin.MUTED, com.ironhub.ui.osrs.OsrsSkin.font()).leftAligned();
			title.setToolTipText("Show or hide the DPS calculator");
			header.add(title);
			header.add(Box.createHorizontalGlue());
			java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					dpsCalcCollapsed = !dpsCalcCollapsed;
					lab.getPanel().setVisible(!dpsCalcCollapsed);
					triangleLabel.setIcon(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
					holder.revalidate();
				}
			};
			// the title's tooltip swallows row clicks — children carry it too
			header.addMouseListener(toggle);
			title.addMouseListener(toggle);
			triangleLabel.addMouseListener(toggle);
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

	/** Idempotent: attach all wrapper hooks once the panel exists. */
	private void wireHooks()
	{
		if (lab.getPanel() != null)
		{
			lab.getPanel().setSetupHooks(this::saveNamedSetup, this::loadNamedSetup);
			lab.getPanel().setWornLookup(this::wornItemFor);
			lab.getPanel().setDpsCalcHook(this::openDpsCalc);
		}
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
}
