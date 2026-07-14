package com.ironhub.modules.loadoutlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.SectionLabel;
import com.loadoutlab.LoadoutLabPlugin;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
import net.runelite.client.util.AsyncBufferedImage;

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

	private final Runnable listener = () -> SwingUtilities.invokeLater(this::onStateChanged);
	private com.ironhub.modules.loadout.StrategyClient strategyClient;
	private JPanel holder;
	private JPanel strip;
	private final JLabel activityLabel = new JLabel();
	private final JLabel tipsLabel = new JLabel();
	private final JPanel setupView = new JPanel();
	private String lastAutoSelected = "";
	private boolean started;

	@Inject
	public LoadoutLabModule(LoadoutLabPlugin lab, EventBus eventBus, IronHubConfig config,
		AccountState state, ClientThread clientThread, net.runelite.api.Client client,
		ItemManager itemManager, com.google.gson.Gson gson, okhttp3.OkHttpClient httpClient)
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
		lab.setPanelReadyCallback(() -> SwingUtilities.invokeLater(this::mountPanel));
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
			holder = new JPanel(new BorderLayout());
			holder.setBackground(UiTokens.PANEL_BG);
			holder.add(buildStrip(), BorderLayout.NORTH);
			mountPanel();
			refreshStrip();
		}
		return holder;
	}

	/** The activity strip: auto-followed target, tips, remembered setup. */
	private JPanel buildStrip()
	{
		strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setBackground(UiTokens.PANEL_BG);
		strip.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));

		activityLabel.setForeground(UiTokens.TEXT_MUTED);
		activityLabel.setFont(activityLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		activityLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		strip.add(activityLabel);
		strip.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		JPanel buttons = new JPanel(new GridLayout(1, 2, UiTokens.GRID_GAP, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		buttons.add(button("Wiki tips",
			"Fetch tips for this task/boss from its wiki strategy page (user-initiated request)",
			this::fetchTips));
		buttons.add(button("Save setup",
			"Remember your current worn gear, inventory and rune pouch for this task/boss",
			this::saveCurrentSetup));
		strip.add(buttons);
		strip.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		tipsLabel.setForeground(UiTokens.TEXT_FAINT);
		tipsLabel.setFont(tipsLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		tipsLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		tipsLabel.setVisible(false);
		strip.add(tipsLabel);

		setupView.setLayout(new BoxLayout(setupView, BoxLayout.Y_AXIS));
		setupView.setOpaque(false);
		setupView.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		strip.add(setupView);
		return strip;
	}

	private JLabel button(String label, String tooltip, Runnable onClick)
	{
		JLabel button = new JLabel(label, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ICON_BUTTON_BG);
		button.setForeground(UiTokens.TEXT_BODY);
		button.setBorder(new javax.swing.border.LineBorder(UiTokens.BORDER_BUTTON));
		button.setFont(button.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		button.setToolTipText(tooltip);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				onClick.run();
			}
		});
		return button;
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
		activityLabel.setText(line.length() > 0 ? line.toString() : "No task or fight detected yet");
		renderSavedSetup();
	}

	// ── wiki tips ─────────────────────────────────────────────────────

	private void fetchTips()
	{
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
		StringBuilder tips = new StringBuilder();
		for (com.ironhub.modules.loadout.WikiStrategy strategy : strategies)
		{
			if (!strategy.notes.isEmpty())
			{
				if (tips.length() > 0)
				{
					tips.append("<br><br>");
				}
				tips.append("<b>").append(strategy.name()).append("</b><br>")
					.append(strategy.notes.replace("&", "&amp;").replace("<", "&lt;"));
			}
		}
		tipsLabel.setText(tips.length() == 0
			? "<html><body style='width:190px'>No wiki tips found for " + activity + "</body></html>"
			: "<html><body style='width:190px'>" + tips + "</body></html>");
		tipsLabel.setVisible(true);
		strip.revalidate();
	}

	// ── remembered setups (gear + inventory + rune pouch) ─────────────

	/** Capture worn gear + inventory now; the rune pouch needs the client thread. */
	private void saveCurrentSetup()
	{
		String activity = activity();
		if (activity.isEmpty())
		{
			return;
		}
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
				state.saveSetup(activity, setup); // persists + notifies (re-renders)
			});
		}
		else
		{
			state.saveSetup(activity, setup);
		}
	}

	/** The remembered setup, Inventory Setups style: OSRS equipment layout,
	 * 4x7 inventory, rune pouch row. */
	private void renderSavedSetup()
	{
		setupView.removeAll();
		PersistedState.SavedSetup saved = state.savedSetup(activity());
		if (saved != null)
		{
			setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			setupView.add(new SectionLabel("Saved setup"));
			setupView.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

			JPanel equipment = new JPanel(new GridLayout(0, 3, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
			equipment.setOpaque(false);
			equipment.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			for (EquipmentInventorySlot[] row : GRID)
			{
				for (EquipmentInventorySlot slot : row)
				{
					Integer id = slot == null ? null : saved.equipment.get(slot.name());
					equipment.add(tileCell(id, slot == null ? null
						: slot.name().toLowerCase(java.util.Locale.ROOT), 0));
				}
			}
			setupView.add(equipment);

			if (saved.pouchRunes.length > 0 && java.util.Arrays.stream(saved.pouchRunes).anyMatch(r -> r > 0))
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				JLabel pouch = smallLabel("Rune pouch");
				setupView.add(pouch);
				JPanel runes = new JPanel(new GridLayout(1, 4, UiTokens.GRID_GAP, 0));
				runes.setOpaque(false);
				runes.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				runes.setMaximumSize(new Dimension(4 * 40, 40));
				for (int i = 0; i < saved.pouchRunes.length; i++)
				{
					runes.add(tileCell(saved.pouchRunes[i] > 0 ? saved.pouchRunes[i] : null,
						"empty", saved.pouchAmounts.length > i ? saved.pouchAmounts[i] : 0));
				}
				setupView.add(runes);
			}

			if (saved.inventory.length > 0)
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				setupView.add(smallLabel("Inventory"));
				JPanel inv = new JPanel(new GridLayout(7, 4, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
				inv.setOpaque(false);
				inv.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				for (int i = 0; i < 28; i++)
				{
					Integer id = i < saved.inventory.length && saved.inventory[i] > 0
						? saved.inventory[i] : null;
					inv.add(tileCell(id, "empty",
						id != null && saved.inventoryQty.length > i ? saved.inventoryQty[i] : 0));
				}
				setupView.add(inv);
			}
		}
		setupView.revalidate();
		setupView.repaint();
	}

	private JLabel smallLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(UiTokens.TEXT_FAINT);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel tileCell(Integer itemId, String emptyTooltip, int quantity)
	{
		JPanel wrap = new JPanel();
		wrap.setOpaque(false);
		if (emptyTooltip == null && itemId == null)
		{
			return wrap; // spacer in the equipment layout
		}
		GridTile tile;
		if (itemId != null)
		{
			String name = state.itemName(itemId);
			tile = new GridTile("", quantity > 1 ? name + " x" + quantity : name,
				GridTile.State.OWNED, false);
			if (itemManager != null)
			{
				AsyncBufferedImage sprite = itemManager.getImage(itemId, Math.max(1, quantity), quantity > 1);
				tile.setIcon(new ImageIcon(sprite));
				sprite.onLoaded(tile::repaint);
			}
		}
		else
		{
			tile = new GridTile("", emptyTooltip, GridTile.State.LOCKED, false);
		}
		wrap.add(tile);
		return wrap;
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
			holder.add(lab.getPanel(), BorderLayout.CENTER);
			onStateChanged(); // panel just arrived: apply auto-follow now
		}
		else
		{
			JLabel loading = new JLabel("Loading gear dataset...", JLabel.CENTER);
			loading.setForeground(UiTokens.TEXT_MUTED);
			holder.add(loading, BorderLayout.CENTER);
		}
		holder.revalidate();
		holder.repaint();
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
