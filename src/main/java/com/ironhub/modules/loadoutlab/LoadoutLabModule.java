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
	private String viewedSetup; // explicit Load-setup pick, wins over the activity
	private boolean dpsCalcCollapsed;
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
			holder = new JPanel(new BorderLayout());
			holder.setBackground(UiTokens.PANEL_BG);
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

	/** The activity card: what the module is auto-following, plus wiki tips.
	 * (Save/Load setup live once, at the bottom with the saved-setup view -
	 * the old second Save button up here duplicated them.) */
	private JPanel buildStrip()
	{
		strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setBackground(UiTokens.PANEL_BG);
		strip.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			new javax.swing.border.LineBorder(UiTokens.BORDER),
			new EmptyBorder(6, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD)));

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ICON_BUTTON_SIZE + 2));
		headerRow.add(new SectionLabel("Activity", UiTokens.FONT_SIZE_LAB_LABEL), BorderLayout.WEST);
		headerRow.add(button("Wiki tips",
			"Fetch tips for this task/boss from its wiki strategy page"
				+ " (user-initiated request); click again to hide them",
			this::fetchTips), BorderLayout.EAST);
		card.add(headerRow);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		activityLabel.setForeground(UiTokens.TEXT_BODY);
		activityLabel.setFont(activityLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LAB_TEXT));
		activityLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.add(activityLabel);

		tipsLabel.setForeground(UiTokens.TEXT_BODY);
		tipsLabel.setFont(tipsLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LAB_SMALL));
		tipsLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		tipsLabel.setVisible(false);
		tipsLabel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		tipsLabel.setToolTipText("Click to dismiss");
		tipsLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tipsLabel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				tipsLabel.setVisible(false);
				strip.revalidate();
				strip.repaint();
			}
		});
		card.add(tipsLabel);

		strip.add(card);
		return strip;
	}

	private JLabel button(String label, String tooltip, Runnable onClick)
	{
		JLabel button = new JLabel(label, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ICON_BUTTON_BG);
		button.setForeground(UiTokens.TEXT_BODY);
		button.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			new javax.swing.border.LineBorder(UiTokens.BORDER_BUTTON),
			new EmptyBorder(1, 6, 1, 6)));
		button.setFont(button.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LAB_SMALL));
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
		activityLabel.setToolTipText(activityLabel.getText());
		renderSavedSetup();
	}

	// ── wiki tips ─────────────────────────────────────────────────────

	private void fetchTips()
	{
		// second press = dismiss (the tips had no way to close)
		if (tipsLabel.isVisible())
		{
			tipsLabel.setVisible(false);
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

	/** The remembered setup, Inventory Setups style: OSRS equipment layout,
	 * 4x7 inventory, rune pouch row. */
	private void renderSavedSetup()
	{
		setupView.removeAll();
		PersistedState.SavedSetup saved = viewedSetup != null
			? state.savedSetup(viewedSetup) : state.savedSetup(activity());
		if (saved != null)
		{
			setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			setupView.add(new SectionLabel("Saved setup", UiTokens.FONT_SIZE_LAB_LABEL));
			setupView.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

			// Inventory Setups look: fixed 46x42 slot boxes, 1px gaps,
			// blank corners as dark boxes
			JPanel equipment = new JPanel(new GridLayout(5, 3, 1, 1));
			equipment.setOpaque(false);
			equipment.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			equipment.setMaximumSize(new Dimension(3 * 46 + 2, 5 * 42 + 4));
			for (EquipmentInventorySlot[] row : GRID)
			{
				for (EquipmentInventorySlot slot : row)
				{
					Integer id = slot == null ? null : saved.equipment.get(slot.name());
					equipment.add(slotBox(id, slot == null ? null
						: slot.name().toLowerCase(java.util.Locale.ROOT), 0));
				}
			}
			JPanel equipmentRow = new JPanel();
			equipmentRow.setLayout(new BoxLayout(equipmentRow, BoxLayout.X_AXIS));
			equipmentRow.setOpaque(false);
			equipmentRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			equipmentRow.add(Box.createHorizontalGlue());
			equipmentRow.add(equipment);
			equipmentRow.add(Box.createHorizontalGlue());
			setupView.add(equipmentRow);

			if (saved.pouchRunes.length > 0 && java.util.Arrays.stream(saved.pouchRunes).anyMatch(r -> r > 0))
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				JLabel pouch = smallLabel("Rune pouch");
				setupView.add(pouch);
				JPanel runes = new JPanel(new GridLayout(1, 4, 1, 1));
				runes.setOpaque(false);
				runes.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				runes.setMaximumSize(new Dimension(4 * 46 + 3, 42));
				for (int i = 0; i < saved.pouchRunes.length; i++)
				{
					runes.add(slotBox(saved.pouchRunes[i] > 0 ? saved.pouchRunes[i] : null,
						"empty", saved.pouchAmounts.length > i ? saved.pouchAmounts[i] : 0));
				}
				JPanel runesRow = new JPanel();
				runesRow.setLayout(new BoxLayout(runesRow, BoxLayout.X_AXIS));
				runesRow.setOpaque(false);
				runesRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				runesRow.add(Box.createHorizontalGlue());
				runesRow.add(runes);
				runesRow.add(Box.createHorizontalGlue());
				setupView.add(runesRow);
			}

			if (saved.inventory.length > 0)
			{
				setupView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				setupView.add(smallLabel("Inventory"));
				JPanel inv = new JPanel(new GridLayout(7, 4, 1, 1));
				inv.setOpaque(false);
				inv.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				inv.setMaximumSize(new Dimension(4 * 46 + 3, 7 * 42 + 6));
				for (int i = 0; i < 28; i++)
				{
					Integer id = i < saved.inventory.length && saved.inventory[i] > 0
						? saved.inventory[i] : null;
					inv.add(slotBox(id, "empty",
						id != null && saved.inventoryQty.length > i ? saved.inventoryQty[i] : 0));
				}
				JPanel invRow = new JPanel();
				invRow.setLayout(new BoxLayout(invRow, BoxLayout.X_AXIS));
				invRow.setOpaque(false);
				invRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				invRow.add(Box.createHorizontalGlue());
				invRow.add(inv);
				invRow.add(Box.createHorizontalGlue());
				setupView.add(invRow);
			}
		}
		setupView.revalidate();
		setupView.repaint();
	}

	private JLabel smallLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(UiTokens.TEXT_MUTED);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LAB_SMALL));
		label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return label;
	}

	/** A 46x42 slot box, exactly the Inventory Setups look: darker-grey
	 * filled slots, dark-grey empties/spacers, sprite centred with the
	 * stack count baked into the icon. */
	private JPanel slotBox(Integer itemId, String emptyTooltip, int quantity)
	{
		JPanel box = new JPanel(new BorderLayout());
		box.setPreferredSize(new Dimension(46, 42));
		box.setMinimumSize(new Dimension(46, 42));
		box.setMaximumSize(new Dimension(46, 42));
		if (emptyTooltip == null && itemId == null)
		{
			box.setBackground(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
			return box; // spacer corner, a dark box like Inventory Setups
		}
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		if (itemId != null)
		{
			box.setBackground(net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR);
			String name = state.itemName(itemId);
			box.setToolTipText(quantity > 1 ? name + " x" + quantity : name);
			if (itemManager != null)
			{
				AsyncBufferedImage sprite = itemManager.getImage(itemId, Math.max(1, quantity), quantity > 1);
				icon.setIcon(new ImageIcon(sprite));
				sprite.onLoaded(() ->
				{
					icon.setIcon(new ImageIcon(sprite));
					icon.repaint();
				});
			}
		}
		else
		{
			box.setBackground(net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR);
			box.setToolTipText(emptyTooltip);
		}
		box.add(icon, BorderLayout.CENTER);
		return box;
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
			// styled like every other Iron Hub section header (triangle glyph)
			JPanel section = new JPanel(new BorderLayout());
			section.setBackground(UiTokens.PANEL_BG);
			SectionLabel header = new SectionLabel("DPS Calc", UiTokens.FONT_SIZE_LAB_LABEL);
			header.setIcon(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
			header.setIconTextGap(UiTokens.ROW_GAP);
			header.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.PAD, UiTokens.PAD_TIGHT, UiTokens.PAD));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.setToolTipText("Show or hide the DPS calculator");
			header.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					dpsCalcCollapsed = !dpsCalcCollapsed;
					lab.getPanel().setVisible(!dpsCalcCollapsed);
					header.setIcon(new com.ironhub.ui.components.PaintedIcon(triangle(), 10));
					holder.revalidate();
				}
			});
			section.add(header, BorderLayout.NORTH);
			section.add(lab.getPanel(), BorderLayout.CENTER);
			lab.getPanel().setVisible(!dpsCalcCollapsed);
			holder.add(section, BorderLayout.CENTER);
			wireHooks();
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
