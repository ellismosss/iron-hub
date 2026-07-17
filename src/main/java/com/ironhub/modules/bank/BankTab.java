package com.ironhub.modules.bank;

import com.ironhub.data.BankedXpPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Bank tab content (frame 2f, search section) in the OSRS stonework skin:
 * search the last bank snapshot from anywhere — icon · name · ×count rows,
 * with a faint "snapshot from last bank visit" timestamp line. Nothing lists
 * until the player types or picks a stat filter (Luke, 2026-07-17): a stat
 * filter ranks the bank by that equipment stat, highest first, with the stat
 * value on the row. Stats resolve on the client thread (compositions are
 * client-thread) and cache per bank snapshot. Frameless — the host's header
 * plate names the module.
 */
class BankTab extends JPanel
{
	private static final int MAX_RESULTS = 50;
	/** Free-standing wrapped hints: the panel minus the tab's side padding. */
	private static final int HINT_WIDTH = UiTokens.PANEL_WIDTH - 20;

	/** Index 0 = no filter; the rest map through {@link #statOf}. */
	private static final String[] STAT_NAMES = {
		"Filter by stat…",
		"Stab attack", "Slash attack", "Crush attack", "Magic attack", "Ranged attack",
		"Melee strength", "Ranged strength", "Magic damage", "Prayer",
		"Stab defence", "Slash defence", "Crush defence", "Magic defence", "Ranged defence",
	};

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests — icons skipped
	private final net.runelite.client.callback.ClientThread clientThread; // null headless
	private final BankedXpPack bankedXpPack;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StoneTextField search;
	private final javax.swing.JComboBox<String> statFilter;
	private final JPanel snapshotHolder = new JPanel();
	private final JPanel list = new JPanel();
	private final StoneChipRow xpView;
	private final JPanel xpSection = new JPanel();

	/** id → stat value for {@link #statKey}'s (filter, bank snapshot) pair. */
	private Map<Integer, Integer> statValues = Map.of();
	private String statKey = "";
	private boolean statsComputing;

	BankTab(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread, BankedXpPack bankedXpPack,
		boolean gridView, java.util.function.Consumer<Boolean> onViewChange, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.bankedXpPack = bankedXpPack;
		this.theme = theme;
		this.search = new StoneTextField(theme, "Search bank…");
		this.statFilter = com.ironhub.ui.osrs.StoneComboBoxUI.skin(
			new javax.swing.JComboBox<>(STAT_NAMES), theme);
		this.xpView = new StoneChipRow(theme, false, "Grid", "List");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(search);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		statFilter.setAlignmentX(LEFT_ALIGNMENT);
		statFilter.addActionListener(e -> rebuild());
		add(statFilter);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		snapshotHolder.setLayout(new BoxLayout(snapshotHolder, BoxLayout.X_AXIS));
		snapshotHolder.setOpaque(false);
		snapshotHolder.setAlignmentX(LEFT_ALIGNMENT);
		add(snapshotHolder);
		add(Box.createVerticalStrut(UiTokens.PAD));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// section header with the view toggle on its right
		JPanel xpHeader = new JPanel();
		xpHeader.setLayout(new BoxLayout(xpHeader, BoxLayout.X_AXIS));
		xpHeader.setOpaque(false);
		xpHeader.setAlignmentX(LEFT_ALIGNMENT);
		xpHeader.setBorder(new EmptyBorder(8, 4, 3, 4));
		OsrsLabel xpTitle = new OsrsLabel("Banked XP", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		xpTitle.setAlignmentY(CENTER_ALIGNMENT);
		xpHeader.add(xpTitle);
		xpHeader.add(Box.createHorizontalGlue());
		xpView.setAlignmentY(CENTER_ALIGNMENT);
		xpHeader.add(xpView);
		cap(xpHeader);
		add(xpHeader);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		xpSection.setLayout(new BoxLayout(xpSection, BoxLayout.Y_AXIS));
		xpSection.setOpaque(false);
		xpSection.setAlignmentX(LEFT_ALIGNMENT);
		add(xpSection);
		add(Box.createVerticalGlue());

		xpView.setSelected(gridView ? 0 : 1);
		xpView.onChange(i ->
		{
			onViewChange.accept(i == 0); // persists per profile via ConfigManager
			rebuild();
		});

		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		snapshotHolder.removeAll();
		list.removeAll();
		Map<Integer, Integer> bank = state.getBankSnapshot();

		if (bank.isEmpty())
		{
			setSnapshotLine("no snapshot yet");
			list.add(faintLine("Open your bank once to take a snapshot."));
		}
		else
		{
			setSnapshotLine("snapshot from last bank visit · "
				+ relativeTime(System.currentTimeMillis() - state.getBankTimestamp()));

			String query = search.getText().trim();
			int stat = statFilter.getSelectedIndex();
			if (query.isEmpty() && stat <= 0)
			{
				// list nothing until asked — 1,000 idle rows helped freeze
				// the client, and an unfiltered dump answers no question
				list.add(faintLine("Type to search, or filter by a stat."));
			}
			else if (stat <= 0)
			{
				List<Integer> matches = bank.keySet().stream()
					.filter(id -> matches(state.itemName(id), query))
					.sorted(Comparator.comparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
					.collect(Collectors.toList());
				addRows(matches, bank, null);
			}
			else if (clientThread == null || itemManager == null)
			{
				list.add(faintLine("Equipment stats need the game client."));
			}
			else if (!currentStatKey(stat).equals(statKey))
			{
				ensureStats(stat);
				list.add(faintLine("Reading equipment stats…"));
			}
			else
			{
				Map<Integer, Integer> values = statValues;
				List<Integer> matches = values.keySet().stream()
					.filter(id -> matches(state.itemName(id), query))
					.sorted(Comparator.<Integer>comparingInt(id -> -values.get(id))
						.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
					.collect(Collectors.toList());
				addRows(matches, bank, values);
			}
		}
		rebuildBankedXp();
		snapshotHolder.revalidate();
		snapshotHolder.repaint();
		list.revalidate();
		list.repaint();
	}

	/** The capped row group: with stat values, the row's right side ranks
	 *  ("+83"); without, it counts ("×1,200"). */
	private void addRows(List<Integer> matches, Map<Integer, Integer> bank,
		Map<Integer, Integer> statValues)
	{
		if (!matches.isEmpty())
		{
			// the item rows sit inside one notched frame, checklist-style
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (Integer id : matches.subList(0, Math.min(matches.size(), MAX_RESULTS)))
			{
				group.add(itemRow(id, bank.getOrDefault(id, 0),
					statValues == null ? null : statValues.get(id)));
			}
			cap(group);
			list.add(group);
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		if (matches.size() > MAX_RESULTS)
		{
			list.add(faintLine("+ " + (matches.size() - MAX_RESULTS) + " more — refine your search"));
		}
		else if (matches.isEmpty())
		{
			list.add(faintLine("No banked items match."));
		}
	}

	// ── equipment-stat ranking ────────────────────────────────────────

	private String currentStatKey(int stat)
	{
		return stat + "@" + state.getBankTimestamp();
	}

	/** Resolve the chosen stat for every banked item on the CLIENT THREAD
	 *  (compositions and stats are client-thread reads), then re-render.
	 *  Cached per (filter, bank snapshot) pair. */
	private void ensureStats(int stat)
	{
		if (statsComputing)
		{
			return;
		}
		statsComputing = true;
		String key = currentStatKey(stat);
		Map<Integer, Integer> bank = state.getBankSnapshot();
		clientThread.invokeLater(() ->
		{
			Map<Integer, Integer> values = new java.util.HashMap<>();
			for (int id : bank.keySet())
			{
				net.runelite.client.game.ItemStats stats = itemManager.getItemStats(id);
				net.runelite.client.game.ItemEquipmentStats equipment =
					stats == null ? null : stats.getEquipment();
				int value = equipment == null ? 0 : statOf(equipment, stat);
				if (value > 0)
				{
					values.put(id, value);
				}
			}
			SwingUtilities.invokeLater(() ->
			{
				statValues = values;
				statKey = key;
				statsComputing = false;
				rebuild();
			});
		});
	}

	/** The dropdown's stat index → its value on an equipable item. */
	static int statOf(net.runelite.client.game.ItemEquipmentStats e, int stat)
	{
		switch (stat)
		{
			case 1: return e.getAstab();
			case 2: return e.getAslash();
			case 3: return e.getAcrush();
			case 4: return e.getAmagic();
			case 5: return e.getArange();
			case 6: return e.getStr();
			case 7: return e.getRstr();
			case 8: return Math.round(e.getMdmg());
			case 9: return e.getPrayer();
			case 10: return e.getDstab();
			case 11: return e.getDslash();
			case 12: return e.getDcrush();
			case 13: return e.getDmagic();
			case 14: return e.getDrange();
			default: return 0;
		}
	}

	/** Provenance copy — always faint (honesty is a feature); wrapped so a
	 *  long timestamp flows to a second line instead of clipping mid-word. */
	private void setSnapshotLine(String text)
	{
		snapshotHolder.add(OsrsLabel.wrapped(text, HINT_WIDTH, OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned());
		snapshotHolder.add(Box.createHorizontalGlue());
		cap(snapshotHolder);
	}

	private void rebuildBankedXp()
	{
		xpSection.removeAll();
		Map<net.runelite.api.Skill, BankedXp.Result> totals = BankedXp.compute(state, bankedXpPack);
		if (totals.isEmpty())
		{
			xpSection.add(faintLine("No bankable XP found."));
		}
		else if (xpView.getSelected() == 0) // grid — 3-col stat boxes
		{
			JPanel grid = new JPanel(new java.awt.GridLayout(0, 3, 4, 4));
			grid.setOpaque(false);
			grid.setAlignmentX(LEFT_ALIGNMENT);
			totals.forEach((skill, result) ->
			{
				StatBox box = new StatBox(theme, skill.getName(), null, formatXp(result.xp));
				box.setToolTipText(tooltip(skill, result));
				grid.add(box);
			});
			int pad = 3 - (totals.size() % 3);
			for (int i = 0; pad < 3 && i < pad; i++)
			{
				grid.add(emptyCell());
			}
			cap(grid);
			xpSection.add(grid);
		}
		else // list — name + green value rows
		{
			totals.forEach((skill, result) ->
			{
				xpSection.add(xpRow(skill, result));
				xpSection.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			});
		}
		xpSection.revalidate();
		xpSection.repaint();
	}

	/** A label · value line in a stone box (the stat-row grammar). */
	private JComponent xpRow(net.runelite.api.Skill skill, BankedXp.Result result)
	{
		StonePanel row = new StonePanel(theme);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setToolTipText(tooltip(skill, result));

		OsrsLabel name = new OsrsLabel(skill.getName(), OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(OsrsLabel.value(formatXp(result.xp)));
		cap(row);
		return row;
	}

	private JPanel emptyCell()
	{
		JPanel cell = new JPanel();
		cell.setOpaque(false);
		return cell;
	}

	private static String tooltip(net.runelite.api.Skill skill, BankedXp.Result result)
	{
		return skill.getName() + " — " + formatXp(result.xp) + " XP banked · "
			+ String.join(", ", result.methods);
	}

	private static String formatXp(double xp)
	{
		return QuantityFormatter.quantityToRSDecimalStack((int) Math.round(xp), true);
	}

	/** statValue null = count row ("×1,200"); set = ranked row ("+83"),
	 *  with the banked count moved to the tooltip. */
	private JPanel itemRow(int itemId, int quantity, Integer statValue)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);

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
		OsrsLabel nameLabel = new OsrsLabel(name, OsrsSkin.LABEL, OsrsSkin.font())
			.leftAligned().squeezable();
		nameLabel.setToolTipText(statValue == null ? name
			: name + " — ×" + QuantityFormatter.quantityToStackSize(quantity) + " banked");
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());

		row.add(OsrsLabel.value(statValue != null ? "+" + statValue
			: "×" + QuantityFormatter.quantityToStackSize(quantity)));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JComponent faintLine(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(OsrsLabel.wrapped(text, HINT_WIDTH, OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Filter predicate — static for direct unit testing. */
	static boolean matches(String itemName, String query)
	{
		return query.trim().isEmpty()
			|| itemName.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
	}

	/** Delegates to the shared formatter — kept for the unit tests. */
	static String relativeTime(long millisAgo)
	{
		return com.ironhub.ui.Format.relativeTime(millisAgo);
	}

	private static void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
