package com.ironhub.modules.loot;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SegmentedControl;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Loot tab content (frame 2g): source selector with kill count,
 * Total / Per kill toggle, and sprite rows sorted by quantity.
 * GP value deliberately de-emphasized — irons care about items.
 */
class LootTab extends JPanel
{
	private static final int MAX_ROWS = 50;

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests — icons skipped
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::sourcesChanged);

	private final JComboBox<String> source = new JComboBox<>();
	private final SegmentedControl view = new SegmentedControl(true, "Total", "Per kill");
	private final JLabel killsLine = new JLabel();
	private final JPanel list = new JPanel();
	private List<String> sources = new ArrayList<>();

	LootTab(AccountState state, ItemManager itemManager)
	{
		this.state = state;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("Loot"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		source.setAlignmentX(LEFT_ALIGNMENT);
		source.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		source.addActionListener(e -> rebuild());
		add(source);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		killsLine.setForeground(UiTokens.TEXT_FAINT);
		killsLine.setFont(killsLine.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		killsLine.setAlignmentX(LEFT_ALIGNMENT);
		add(killsLine);
		add(Box.createVerticalStrut(UiTokens.PAD));

		view.onChange(i -> rebuild());
		add(view);
		add(Box.createVerticalStrut(UiTokens.PAD));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		sourcesChanged();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Refresh the selector when new sources appear, keeping the selection. */
	private void sourcesChanged()
	{
		List<String> fresh = new ArrayList<>(state.lootSources());
		fresh.sort(Comparator.comparingInt((String s) -> -state.getKillCount(s))
			.thenComparing(s -> s));
		if (!fresh.equals(sources))
		{
			String selected = (String) source.getSelectedItem();
			sources = fresh;
			source.removeAllItems();
			sources.forEach(source::addItem);
			if (selected != null && sources.contains(selected))
			{
				source.setSelectedItem(selected); // fires rebuild via listener
			}
		}
		rebuild();
	}

	private void rebuild()
	{
		list.removeAll();
		String selected = (String) source.getSelectedItem();

		if (selected == null)
		{
			killsLine.setText("no loot recorded yet");
			list.add(faintLine("Kill something — drops are tracked automatically."));
		}
		else
		{
			int kills = state.getKillCount(selected);
			killsLine.setText(kills + (kills == 1 ? " kill" : " kills") + " recorded");
			boolean perKill = view.getSelected() == 1;

			Map<Integer, Integer> loot = state.lootFor(selected);
			List<Integer> ids = new ArrayList<>(loot.keySet());
			ids.sort(Comparator.comparingInt((Integer id) -> -loot.get(id))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)));

			for (Integer id : ids.subList(0, Math.min(ids.size(), MAX_ROWS)))
			{
				list.add(itemRow(id, loot.get(id), kills, perKill));
				list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			if (ids.size() > MAX_ROWS)
			{
				list.add(faintLine("+ " + (ids.size() - MAX_ROWS) + " more items"));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel itemRow(int itemId, int quantity, int kills, boolean perKill)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));

		JLabel icon = new JLabel();
		Dimension iconSize = new Dimension(UiTokens.TILE_ICON_SIZE, UiTokens.TILE_ICON_SIZE);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		if (itemManager != null)
		{
			AsyncBufferedImage sprite = itemManager.getImage(itemId, quantity, quantity > 1);
			icon.setIcon(new ImageIcon(sprite));
			sprite.onLoaded(icon::repaint);
		}
		row.add(icon);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		String name = state.itemName(itemId);
		JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(UiTokens.TEXT_BODY);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		nameLabel.setToolTipText(name);
		nameLabel.setMinimumSize(new Dimension(0, 0));
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());

		JLabel count = new JLabel(perKill
			? perKillText(quantity, kills)
			: "×" + QuantityFormatter.quantityToStackSize(quantity));
		count.setForeground(UiTokens.TEXT_PRIMARY);
		count.setFont(count.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		count.setToolTipText(quantity + " over " + kills + (kills == 1 ? " kill" : " kills"));
		row.add(count);
		return row;
	}

	private JLabel faintLine(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(UiTokens.TEXT_FAINT);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	/** "0.8/kill" — static for unit testing. */
	static String perKillText(int quantity, int kills)
	{
		if (kills <= 0)
		{
			return "—";
		}
		double avg = (double) quantity / kills;
		return (avg >= 100 ? String.valueOf(Math.round(avg))
			: String.format(Locale.ROOT, "%.1f", avg)) + "/kill";
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
