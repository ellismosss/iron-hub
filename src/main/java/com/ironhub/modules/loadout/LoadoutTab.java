package com.ironhub.modules.loadout;

import com.ironhub.data.ScenariosPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Loadout tab: scenario selector + the standard OSRS equipment-slot grid
 * filled with the best owned gear (green = owned by definition; empty
 * slots dim). BETA label until the heuristic earns trust.
 */
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
	private final JComboBox<String> scenario;
	private final JPanel grid = new JPanel(new GridLayout(0, 3, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	LoadoutTab(AccountState state, ItemManager itemManager, ClientThread clientThread, ScenariosPack pack)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.pack = pack;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

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
		ScenariosPack.Scenario selected = pack.getScenarios().get(scenario.getSelectedIndex());
		if (itemManager == null || clientThread == null)
		{
			renderGrid(Map.of()); // unit tests
			return;
		}
		// item stats require the client thread (getItemComposition asserts it)
		clientThread.invoke(() ->
		{
			Map<EquipmentInventorySlot, Integer> best =
				LoadoutSolver.solve(state, selected.getStyle(), itemManager::getItemStats);
			SwingUtilities.invokeLater(() -> renderGrid(best));
		});
	}

	private void renderGrid(Map<EquipmentInventorySlot, Integer> best)
	{
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
