package com.ironhub.modules.loot;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Loot tab content (frame 2g) in the OSRS stonework skin: source selector
 * with kill count, Total / Per kill toggle, and sprite rows sorted by
 * quantity. GP value deliberately de-emphasized — irons care about items.
 * Frameless — the host's header plate names the module.
 */
class LootTab extends JPanel
{
	private static final int MAX_ROWS = 50;

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests — icons skipped
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::sourcesChanged);
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final JComboBox<String> source = new JComboBox<>();
	private final StoneChipRow view;
	private final JPanel killsLine = new JPanel();
	private final JPanel list = new JPanel();
	private final JPanel supplies = new JPanel();
	private List<String> sources = new ArrayList<>();

	LootTab(AccountState state, ItemManager itemManager, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.theme = theme;
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, listener);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(Box.createVerticalStrut(4));

		StoneComboBoxUI.skin(source, theme);
		source.setAlignmentX(LEFT_ALIGNMENT);
		source.setMaximumSize(new Dimension(Integer.MAX_VALUE, source.getPreferredSize().height));
		source.addActionListener(e ->
		{
			if (!repopulating)
			{
				rebuild();
			}
		});
		add(source);
		add(Box.createVerticalStrut(3));

		killsLine.setLayout(new BoxLayout(killsLine, BoxLayout.X_AXIS));
		killsLine.setOpaque(false);
		killsLine.setAlignmentX(LEFT_ALIGNMENT);
		add(killsLine);
		add(Box.createVerticalStrut(4));

		view = new StoneChipRow(theme, true, "Total", "Per kill");
		view.onChange(i -> rebuild());
		add(view);
		add(Box.createVerticalStrut(4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);

		supplies.setLayout(new BoxLayout(supplies, BoxLayout.Y_AXIS));
		supplies.setOpaque(false);
		supplies.setAlignmentX(LEFT_ALIGNMENT);
		add(supplies);
		add(Box.createVerticalGlue());

		state.addListener(listener, AccountState.Topic.LOOT);
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
			// removeAllItems/addItem fire the combo's action listener per
			// mutation — up to four full list rebuilds in one pass
			// (2026-07-20 audit); the trailing rebuild() covers the result
			repopulating = true;
			try
			{
				sources = fresh;
				source.removeAllItems();
				sources.forEach(source::addItem);
				if (selected != null && sources.contains(selected))
				{
					source.setSelectedItem(selected);
				}
			}
			finally
			{
				repopulating = false;
			}
		}
		rebuild();
	}

	private boolean repopulating;

	private void rebuild()
	{
		list.removeAll();
		killsLine.removeAll();
		String selected = (String) source.getSelectedItem();

		if (selected == null)
		{
			killsLine.add(new OsrsLabel("no loot recorded yet", OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			list.add(faintLine("Kill something — drops are tracked automatically."));
		}
		else
		{
			int kills = state.getKillCount(selected);
			killsLine.add(new OsrsLabel(kills + (kills == 1 ? " kill" : " kills") + " recorded",
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			boolean perKill = view.getSelected() == 1;

			Map<Integer, Integer> loot = state.lootFor(selected);
			List<Integer> ids = new ArrayList<>(loot.keySet());
			ids.sort(Comparator.comparingInt((Integer id) -> -loot.get(id))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)));

			// flat rows in the Route-list grammar (Luke, 2026-07-17) — no
			// frame around the list, light text, small icons
			for (Integer id : ids.subList(0, Math.min(ids.size(), MAX_ROWS)))
			{
				list.add(itemRow(id, loot.get(id), kills, perKill));
			}
			if (ids.size() > MAX_ROWS)
			{
				list.add(Box.createVerticalStrut(3));
				list.add(faintLine("+ " + (ids.size() - MAX_ROWS) + " more items"));
			}
		}
		rebuildSupplies(selected);
		cap(killsLine);
		killsLine.revalidate();
		killsLine.repaint();
		list.revalidate();
		list.repaint();
	}

	/** SUPPLIES USED (frame 2g): consumption per source, avg per kill. */
	private void rebuildSupplies(String selected)
	{
		supplies.removeAll();
		Map<Integer, Integer> used = selected == null ? Map.of() : state.suppliesFor(selected);
		if (!used.isEmpty())
		{
			supplies.add(section("Supplies used"));

			int kills = state.getKillCount(selected);
			boolean perKill = view.getSelected() == 1;
			List<Integer> ids = new ArrayList<>(used.keySet());
			// same flat Route-list grammar as the loot rows above
			ids.sort(Comparator.comparingInt((Integer id) -> -used.get(id))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)));
			for (Integer id : ids.subList(0, Math.min(ids.size(), MAX_ROWS)))
			{
				supplies.add(itemRow(id, used.get(id), kills, perKill));
			}
		}
		supplies.revalidate();
		supplies.repaint();
	}

	/**
	 * One item as a flat Route-style row (Luke, 2026-07-17): 16px sprite in
	 * its own holder, name and count in the light body colour — no green,
	 * no frame, no stack number baked into the sprite (unreadable at 16px;
	 * the count is the row's own text).
	 */
	private JComponent itemRow(int itemId, int quantity, int kills, boolean perKill)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));

		JLabel icon = new JLabel();
		Dimension iconSize = new Dimension(16, 16);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		java.awt.Image sprite = sprites.get(itemId, -1, 16);
		if (sprite != null)
		{
			icon.setIcon(new ImageIcon(sprite));
		}
		row.add(icon);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));

		String name = state.itemName(itemId);
		OsrsLabel nameLabel = new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		nameLabel.setToolTipText(name);
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());

		OsrsLabel count = new OsrsLabel(perKill
			? perKillText(quantity, kills)
			: "×" + QuantityFormatter.quantityToStackSize(quantity),
			OsrsSkin.MUTED, OsrsSkin.font());
		count.setToolTipText(quantity + " over " + kills + (kills == 1 ? " kill" : " kills"));
		row.add(count);
		cap(row);
		return row;
	}

	private JComponent faintLine(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Section header in the skin grammar (the FarmingTab pattern). */
	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
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
