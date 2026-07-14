package com.ironhub.modules.loadout;

import com.ironhub.data.ScenariosPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.EquipmentInventorySlot;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Loadout tab: scenario selector + the standard OSRS equipment-slot grid
 * filled with the best owned gear (green = owned by definition; empty
 * slots dim). BETA label until the heuristic earns trust.
 */
@Slf4j
class LoadoutTab extends JPanel
{
	// mockup 2f grid: rows of 3, null = spacer
	private static final EquipmentInventorySlot[][] GRID = {
		{null, EquipmentInventorySlot.HEAD, null},
		{EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET, EquipmentInventorySlot.AMMO},
		{EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD},
		{null, EquipmentInventorySlot.LEGS, null},
		{EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING},
	};

	private final AccountState state;
	private final ItemManager itemManager;   // null in unit tests
	private final ClientThread clientThread; // null in unit tests
	private final ScenariosPack pack;
	private final com.ironhub.IronHubConfig config;
	private final com.google.gson.Gson gson;
	private final okhttp3.OkHttpClient httpClient; // null in unit tests
	private final JComboBox<String> scenario;
	private final JPanel grid = new JPanel(new GridLayout(0, 3, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
	private final JLabel activityLine = new JLabel();
	private final JPanel wornGrid = new JPanel(new GridLayout(0, 3, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
	private final JPanel invGrid = new JPanel(new GridLayout(7, 4, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final StrategyClient strategyClient; // null in unit tests
	private final JPanel strategyBar = new JPanel();
	private java.util.List<WikiStrategy> strategies = java.util.List.of();
	private int selectedStrategy;
	private Map<EquipmentInventorySlot, Integer> lastBest = Map.of();

	LoadoutTab(AccountState state, ItemManager itemManager, ClientThread clientThread,
		ScenariosPack pack, com.ironhub.IronHubConfig config, com.google.gson.Gson gson,
		okhttp3.OkHttpClient httpClient)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.pack = pack;
		this.config = config;
		this.gson = gson;
		this.httpClient = httpClient;
		this.strategyClient = httpClient == null ? null
			: new StrategyClient(httpClient, gson, new com.ironhub.data.ItemNameIndex(gson));
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		// current activity (slayer task + last fought NPC drive strategies)
		activityLine.setForeground(UiTokens.TEXT_MUTED);
		activityLine.setFont(activityLine.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		activityLine.setAlignmentX(LEFT_ALIGNMENT);
		add(activityLine);
		add(Box.createVerticalStrut(UiTokens.PAD));

		// live worn equipment + inventory
		add(new SectionLabel("Equipped"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		wornGrid.setOpaque(false);
		wornGrid.setAlignmentX(LEFT_ALIGNMENT);
		add(wornGrid);
		add(Box.createVerticalStrut(UiTokens.PAD));
		add(new SectionLabel("Inventory"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		invGrid.setOpaque(false);
		invGrid.setAlignmentX(LEFT_ALIGNMENT);
		add(invGrid);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(new SectionLabel("Best in bank"));
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel beta = new JLabel("BETA");
		beta.setForeground(UiTokens.TEXT_FAINT);
		beta.setFont(beta.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_LABEL));
		header.add(beta);
		header.add(Box.createHorizontalGlue());
		add(header);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		// wiki strategy tabs appear here after a fetch
		strategyBar.setLayout(new BoxLayout(strategyBar, BoxLayout.Y_AXIS));
		strategyBar.setOpaque(false);
		strategyBar.setAlignmentX(LEFT_ALIGNMENT);
		add(strategyBar);

		scenario = new JComboBox<>(pack.getScenarios().stream()
			.map(s -> s.getName() + " · " + s.getStyle())
			.toArray(String[]::new));
		scenario.setAlignmentX(LEFT_ALIGNMENT);
		scenario.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		scenario.addActionListener(e -> rebuild());
		add(scenario);
		add(Box.createVerticalStrut(UiTokens.PAD));

		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		add(grid);
		add(Box.createVerticalStrut(UiTokens.PAD));

		// compact buttons (2f): full labels in tooltips
		JPanel buttons = new JPanel(new GridLayout(1, 3, UiTokens.GRID_GAP, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		buttons.add(secondaryButton("Wiki gear",
			"Fetch the wiki's recommended gear for your task / target (user-initiated request)",
			this::fetchStrategies));
		buttons.add(secondaryButton("DPS calc", "Export to the wiki DPS calculator", this::exportToDpsCalc));
		buttons.add(secondaryButton("Bank tag", "Copy this loadout as a Bank Tags import string", this::copyBankTag));
		add(buttons);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		renderActivity();
		renderCurrent();
		if (itemManager == null || clientThread == null)
		{
			renderGrid(Map.of()); // unit tests
			return;
		}
		// item stats require the client thread (getItemComposition asserts it)
		if (!strategies.isEmpty())
		{
			WikiStrategy strategy = strategies.get(Math.min(selectedStrategy, strategies.size() - 1));
			clientThread.invoke(() ->
			{
				Map<EquipmentInventorySlot, Integer> best =
					StrategySolver.solve(state, strategy, itemManager::getItemStats);
				SwingUtilities.invokeLater(() -> renderGrid(best));
			});
			return;
		}
		ScenariosPack.Scenario selected = pack.getScenarios().get(scenario.getSelectedIndex());
		clientThread.invoke(() ->
		{
			Map<EquipmentInventorySlot, Integer> best =
				LoadoutSolver.solve(state, selected.getStyle(), itemManager::getItemStats);
			SwingUtilities.invokeLater(() -> renderGrid(best));
		});
	}

	/** The activity strategies are planned for: current fight beats task. */
	private String plannedActivity()
	{
		return !state.getCombatNpcName().isEmpty()
			? state.getCombatNpcName() : state.getSlayerTask();
	}

	/** User-initiated wiki lookup for the detected activity. */
	private void fetchStrategies()
	{
		String activity = plannedActivity();
		if (strategyClient == null || activity.isEmpty())
		{
			return;
		}
		boolean slayerTask = activity.equals(state.getSlayerTask());
		strategyClient.fetch(activity, slayerTask, result ->
			SwingUtilities.invokeLater(() ->
			{
				strategies = result;
				selectedStrategy = 0;
				renderStrategyBar(activity);
				rebuild();
			}));
	}

	private void renderStrategyBar(String activity)
	{
		strategyBar.removeAll();
		if (!strategies.isEmpty())
		{
			JLabel source = new JLabel("Wiki setups · " + activity);
			source.setForeground(UiTokens.TEXT_FAINT);
			source.setFont(source.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			source.setAlignmentX(LEFT_ALIGNMENT);
			strategyBar.add(source);
			strategyBar.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			com.ironhub.ui.components.SegmentedControl tabs =
				new com.ironhub.ui.components.SegmentedControl(true,
					strategies.stream().limit(4).map(WikiStrategy::name).toArray(String[]::new));
			tabs.onChange(i ->
			{
				selectedStrategy = i;
				rebuild();
			});
			strategyBar.add(tabs);
			strategyBar.add(Box.createVerticalStrut(UiTokens.PAD));
		}
		else
		{
			JLabel none = new JLabel("No wiki setup found for " + activity);
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			none.setAlignmentX(LEFT_ALIGNMENT);
			strategyBar.add(none);
			strategyBar.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		strategyBar.revalidate();
		strategyBar.repaint();
	}

	private void renderActivity()
	{
		String task = state.getSlayerTask();
		String fighting = state.getCombatNpcName();
		StringBuilder line = new StringBuilder();
		if (!task.isEmpty())
		{
			line.append("Task: ").append(task);
		}
		if (!fighting.isEmpty())
		{
			line.append(line.length() > 0 ? " · " : "").append("Fighting: ").append(fighting);
		}
		activityLine.setText(line.length() > 0 ? line.toString() : "No activity detected yet");
	}

	/** Live worn gear (slot layout) + inventory (4x7, container order). */
	private void renderCurrent()
	{
		wornGrid.removeAll();
		int[] worn = state.getEquipmentSlots();
		for (EquipmentInventorySlot[] row : GRID)
		{
			for (EquipmentInventorySlot slot : row)
			{
				Integer id = null;
				if (slot != null && slot.getSlotIdx() < worn.length && worn[slot.getSlotIdx()] > 0)
				{
					id = worn[slot.getSlotIdx()];
				}
				wornGrid.add(cell(slot, id));
			}
		}
		wornGrid.revalidate();
		wornGrid.repaint();

		invGrid.removeAll();
		int[] inv = state.getInventorySlots();
		for (int i = 0; i < 28; i++)
		{
			int id = i < inv.length ? inv[i] : -1;
			JPanel wrap = new JPanel();
			wrap.setOpaque(false);
			GridTile tile;
			if (id > 0)
			{
				tile = new GridTile("", state.itemName(id), GridTile.State.OWNED, false);
				if (itemManager != null)
				{
					AsyncBufferedImage sprite = itemManager.getImage(id);
					tile.setIcon(new ImageIcon(sprite));
					sprite.onLoaded(tile::repaint);
				}
			}
			else
			{
				tile = new GridTile("", "empty", GridTile.State.LOCKED, false);
			}
			wrap.add(tile);
			invGrid.add(wrap);
		}
		invGrid.revalidate();
		invGrid.repaint();
	}

	private void renderGrid(Map<EquipmentInventorySlot, Integer> best)
	{
		lastBest = best;
		grid.removeAll();
		for (EquipmentInventorySlot[] row : GRID)
		{
			for (EquipmentInventorySlot slot : row)
			{
				grid.add(cell(slot, slot == null ? null : best.get(slot)));
			}
		}
		grid.revalidate();
		grid.repaint();
	}

	private JLabel secondaryButton(String label, String tooltip, Runnable onClick)
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

	/** POST the loadout to the wiki shortlink API, open the share URL. */
	private void exportToDpsCalc()
	{
		if (lastBest.isEmpty() || httpClient == null || !config.dpsCalcExport())
		{
			return;
		}
		ScenariosPack.Scenario selected = pack.getScenarios().get(scenario.getSelectedIndex());
		com.google.gson.JsonObject payload = DpsExport.buildPayload(
			gson, state, "Iron Hub — " + selected.getName(), lastBest);

		okhttp3.Request request = new okhttp3.Request.Builder()
			.url(DpsExport.ENDPOINT)
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
					net.runelite.client.util.LinkBrowser.browse(DpsExport.SHARE_URL + id);
				}
			}
		});
	}

	/** Bank Tags import form: banktag,<name>,<icon item>,<item ids…> */
	private void copyBankTag()
	{
		if (lastBest.isEmpty())
		{
			return;
		}
		ScenariosPack.Scenario selected = pack.getScenarios().get(scenario.getSelectedIndex());
		StringBuilder tag = new StringBuilder("banktag,iron-hub-").append(selected.getId())
			.append(",").append(lastBest.values().iterator().next());
		lastBest.values().forEach(id -> tag.append(",").append(id));
		Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(tag.toString()), null);
	}

	private JPanel cell(EquipmentInventorySlot slot, Integer itemId)
	{
		JPanel wrap = new JPanel();
		wrap.setOpaque(false);
		if (slot == null)
		{
			return wrap; // spacer
		}
		GridTile tile;
		if (itemId != null)
		{
			tile = new GridTile("", state.itemName(itemId), GridTile.State.OWNED, false);
			if (itemManager != null)
			{
				AsyncBufferedImage sprite = itemManager.getImage(itemId);
				tile.setIcon(new ImageIcon(sprite));
				sprite.onLoaded(tile::repaint);
			}
		}
		else
		{
			tile = new GridTile("", slot.name().toLowerCase() + " — nothing owned",
				GridTile.State.LOCKED, false);
		}
		wrap.add(tile);
		return wrap;
	}
}
