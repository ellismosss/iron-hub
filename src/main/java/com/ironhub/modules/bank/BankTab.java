package com.ironhub.modules.bank;

import com.ironhub.data.BankedXpPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneCheckbox;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Bank tab content (frame 2f grown into a workbench, Luke 2026-07-17): the
 * search-gated list (nothing renders until a search, filter or view is
 * chosen), an equipment-stat ranking dropdown, a Highest-alchs view
 * (Alchemiser semantics: HA price x quantity, descending, with persisted
 * per-item exclusions and a reset), and per-skill banked-XP views over the
 * Banked Experience data port. Rows multi-select — selected items glow in
 * the real bank via the shared restock overlay — and hovering any row shows
 * the item's equipment stats (Item Stats-style tooltip). All item values
 * resolve on the CLIENT THREAD once per bank snapshot and cache. Frameless —
 * the host's header plate names the module.
 */
class BankTab extends JPanel
{
	/** Top-20 (Luke): the list ranks, it does not enumerate. */
	private static final int MAX_RESULTS = 20;
	/** Free-standing wrapped hints: the panel minus the tab's side padding. */
	private static final int HINT_WIDTH = UiTokens.PANEL_WIDTH - 20;

	/** Index 0 = no filter; the rest map through {@link #statOf}. */
	private static final String[] STAT_NAMES = {
		"Filter by stat…",
		"Stab attack", "Slash attack", "Crush attack", "Magic attack", "Ranged attack",
		"Melee strength", "Ranged strength", "Magic damage", "Prayer",
		"Stab defence", "Slash defence", "Crush defence", "Magic defence", "Ranged defence",
	};

	private enum Mode
	{
		SEARCH, ALCH, SKILL
	}

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests — icons skipped
	private final net.runelite.client.callback.ClientThread clientThread; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless
	/** Module-owned selection — the bank glow overlay reads it live. */
	private final Set<Integer> selection;
	/** Pushes (ordered items, readable title) for the collected bank view. */
	private final java.util.function.BiConsumer<List<Integer>, String> onBankDisplay;
	private final BankedXpPack bankedXpPack;
	/** The ids actually rendered by the last addRows (capped) — the SKILL
	 *  view sends exactly these to the bank. */
	private List<Integer> lastShownIds = List.of();
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StoneTextField search;
	private final javax.swing.JComboBox<String> statFilter;
	private final JPanel actionsHolder = new JPanel();
	private final JPanel skillStrip = new JPanel();
	private final JPanel snapshotHolder = new JPanel();
	private final JPanel list = new JPanel();
	private final StoneChipRow xpView;
	private final JPanel xpSection = new JPanel();

	private Mode mode = Mode.SEARCH;
	private Skill skillMode;

	/** Per-item equipment stats + high-alch prices for {@link #itemDataKey}'s
	 *  bank snapshot — one client-thread sweep serves the stat ranking, the
	 *  alch view and the hover tooltips. */
	private Map<Integer, ItemEquipmentStats> equipStats = Map.of();
	private Map<Integer, Long> haPrices = Map.of();
	private String itemDataKey = "";
	private boolean itemDataComputing;

	BankTab(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.game.SkillIconManager skillIcons, Set<Integer> selection,
		java.util.function.BiConsumer<List<Integer>, String> onBankDisplay,
		BankedXpPack bankedXpPack,
		boolean gridView, java.util.function.Consumer<Boolean> onViewChange, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.skillIcons = skillIcons;
		this.selection = selection;
		this.onBankDisplay = onBankDisplay;
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

		actionsHolder.setLayout(new BoxLayout(actionsHolder, BoxLayout.Y_AXIS));
		actionsHolder.setOpaque(false);
		actionsHolder.setAlignmentX(LEFT_ALIGNMENT);
		add(actionsHolder);

		skillStrip.setLayout(new java.awt.GridLayout(0, 8, 2, 2));
		skillStrip.setOpaque(false);
		skillStrip.setAlignmentX(LEFT_ALIGNMENT);
		JPanel stripHolder = new JPanel();
		stripHolder.setLayout(new BoxLayout(stripHolder, BoxLayout.X_AXIS));
		stripHolder.setOpaque(false);
		stripHolder.setAlignmentX(LEFT_ALIGNMENT);
		stripHolder.add(skillStrip);
		stripHolder.add(Box.createHorizontalGlue());
		add(stripHolder);
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

		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
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
		lastShownIds = List.of(); // set by addRows; stale ids must not linger
		rebuildActions();
		rebuildSkillStrip();
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
			boolean needsItemData = mode == Mode.ALCH || (mode == Mode.SEARCH && stat > 0);
			if (needsItemData)
			{
				ensureItemData();
			}

			if (mode == Mode.ALCH)
			{
				rebuildAlch(bank, query);
			}
			else if (mode == Mode.SKILL)
			{
				rebuildSkillView(bank, query);
			}
			else if (query.isEmpty() && stat <= 0)
			{
				// list nothing until asked — an unfiltered dump answers no
				// question, and idle rows were freeze fuel
				list.add(faintLine("Type to search, filter by a stat, or pick a view."));
			}
			else if (stat <= 0)
			{
				List<Row> rows = bank.keySet().stream()
					.filter(id -> matches(state.itemName(id), query))
					.sorted(Comparator.comparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
					.map(id -> new Row(id,
						"×" + QuantityFormatter.quantityToStackSize(bank.get(id)), false))
					.collect(Collectors.toList());
				addRows(rows, bank);
			}
			else if (clientThread == null || itemManager == null)
			{
				list.add(faintLine("Equipment stats need the game client."));
			}
			else if (!dataReady())
			{
				list.add(faintLine("Reading equipment stats…"));
			}
			else
			{
				Map<Integer, ItemEquipmentStats> equipment = equipStats;
				List<Row> rows = equipment.keySet().stream()
					.filter(bank::containsKey)
					.filter(id -> statOf(equipment.get(id), stat) > 0)
					.filter(id -> matches(state.itemName(id), query))
					.sorted(Comparator.<Integer>comparingInt(id -> -statOf(equipment.get(id), stat))
						.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
					.map(id -> new Row(id, "+" + statOf(equipment.get(id), stat), false))
					.collect(Collectors.toList());
				addRows(rows, bank);
			}
		}
		pushBankDisplay();
		rebuildBankedXp();
		snapshotHolder.revalidate();
		snapshotHolder.repaint();
		actionsHolder.revalidate();
		actionsHolder.repaint();
		skillStrip.revalidate();
		skillStrip.repaint();
		list.revalidate();
		list.repaint();
	}

	// ── views ─────────────────────────────────────────────────────────

	/** Alchemiser semantics: HA price × quantity, descending; excluded
	 *  items hidden (persisted), each row's checkbox excludes it. */
	private void rebuildAlch(Map<Integer, Integer> bank, String query)
	{
		if (clientThread == null || itemManager == null)
		{
			list.add(faintLine("Item values need the game client."));
			return;
		}
		if (!dataReady())
		{
			list.add(faintLine("Reading item values…"));
			return;
		}
		Map<Integer, Long> prices = haPrices;
		List<Row> rows = prices.keySet().stream()
			.filter(bank::containsKey)
			.filter(id -> !state.isAlchExcluded(id))
			.filter(id -> matches(state.itemName(id), query))
			.sorted(Comparator.<Integer>comparingLong(id -> -stackValue(prices, bank, id))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.map(id -> new Row(id,
				QuantityFormatter.quantityToStackSize(stackValue(prices, bank, id))
					+ "/" + prices.get(id) + " gp/ea", true))
			.collect(Collectors.toList());
		if (rows.isEmpty())
		{
			list.add(faintLine(state.getAlchExcluded().isEmpty()
				? "Nothing alchable in the bank snapshot."
				: "Nothing left — everything alchable is excluded."));
			return;
		}
		addRows(rows, bank);
	}

	private static long stackValue(Map<Integer, Long> prices, Map<Integer, Integer> bank, int id)
	{
		return prices.get(id) * bank.getOrDefault(id, 0);
	}

	/** Banked Experience port: bank items with entries for the skill,
	 *  ranked by total banked XP (quantity × best method's xp). */
	private void rebuildSkillView(Map<Integer, Integer> bank, String query)
	{
		Map<Integer, BankedXpPack.Entry> best = bestEntries(skillMode);
		List<Row> rows = best.keySet().stream()
			.filter(bank::containsKey)
			.filter(id -> matches(state.itemName(id), query))
			.sorted(Comparator.<Integer>comparingDouble(
					id -> -bank.get(id) * best.get(id).getXpEach())
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.map(id -> new Row(id,
				formatXp(bank.get(id) * best.get(id).getXpEach()) + " xp", false))
			.collect(Collectors.toList());
		if (rows.isEmpty())
		{
			list.add(faintLine("No banked " + skillMode.getName() + " XP found."));
			return;
		}
		addRows(rows, bank);
	}

	/** Best (highest-xp) pack entry per item for one skill. */
	private Map<Integer, BankedXpPack.Entry> bestEntries(Skill skill)
	{
		Map<Integer, BankedXpPack.Entry> best = new HashMap<>();
		for (BankedXpPack.Entry entry : bankedXpPack.getEntries())
		{
			if (skill.getName().equalsIgnoreCase(entry.getSkill()))
			{
				best.merge(entry.getItemId(), entry,
					(a, b) -> a.getXpEach() >= b.getXpEach() ? a : b);
			}
		}
		return best;
	}

	// ── controls ──────────────────────────────────────────────────────

	private void rebuildActions()
	{
		actionsHolder.removeAll();
		JPanel row = new JPanel(new java.awt.GridLayout(1, 0, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new StoneButton(theme, mode == Mode.ALCH ? "Back to search" : "Highest alchs", () ->
		{
			mode = mode == Mode.ALCH ? Mode.SEARCH : Mode.ALCH;
			skillMode = null;
			rebuild();
		}));
		if (!selection.isEmpty())
		{
			row.add(new StoneButton(theme, "Clear selection (" + selection.size() + ")", () ->
			{
				selection.clear();
				rebuild();
			}));
		}
		int excluded = state.getAlchExcluded().size();
		if (mode == Mode.ALCH && excluded > 0)
		{
			row.add(new StoneButton(theme, "Reset excluded (" + excluded + ")",
				state::clearAlchExclusions));
		}
		cap(row);
		actionsHolder.add(row);
		actionsHolder.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
	}

	/** One icon per skill the banked-XP pack covers; the active view's icon
	 *  sits on the select fill. Needs the skill icons — absent headless. */
	private void rebuildSkillStrip()
	{
		skillStrip.removeAll();
		if (skillIcons == null)
		{
			return;
		}
		for (Skill skill : packSkills())
		{
			JPanel cell = new JPanel(new java.awt.BorderLayout());
			boolean active = mode == Mode.SKILL && skill == skillMode;
			cell.setOpaque(active);
			if (active)
			{
				cell.setBackground(theme.selectFill);
			}
			cell.setBorder(new EmptyBorder(2, 2, 2, 2));
			JLabel icon = new JLabel(new ImageIcon(skillIcons.getSkillImage(skill, true)));
			cell.add(icon, java.awt.BorderLayout.CENTER);
			cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			cell.setToolTipText(skill.getName() + " — banked XP view");
			cell.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (mode == Mode.SKILL && skill == skillMode)
					{
						mode = Mode.SEARCH;
						skillMode = null;
					}
					else
					{
						mode = Mode.SKILL;
						skillMode = skill;
					}
					rebuild();
				}
			});
			skillStrip.add(cell);
		}
	}

	/** The skills the pack has entries for, in Skill enum order. */
	private List<Skill> packSkills()
	{
		java.util.EnumSet<Skill> skills = java.util.EnumSet.noneOf(Skill.class);
		for (BankedXpPack.Entry entry : bankedXpPack.getEntries())
		{
			try
			{
				skills.add(Skill.valueOf(entry.getSkill().toUpperCase(Locale.ROOT)));
			}
			catch (IllegalArgumentException e)
			{
				// unknown skill string: the pack test guards this; skip here
			}
		}
		return new ArrayList<>(skills);
	}

	// ── rows ──────────────────────────────────────────────────────────

	private static final class Row
	{
		final int itemId;
		final String rightText;
		final boolean excludable;

		Row(int itemId, String rightText, boolean excludable)
		{
			this.itemId = itemId;
			this.rightText = rightText;
			this.excludable = excludable;
		}
	}

	/** What the real bank should collect together right now: the SKILL
	 *  view's whole result list (selected ones keep the green glow), else
	 *  just the selected items. Empty = release the bank. */
	private void pushBankDisplay()
	{
		if (onBankDisplay == null)
		{
			return;
		}
		if (mode == Mode.SKILL && skillMode != null)
		{
			onBankDisplay.accept(lastShownIds, skillMode.getName() + " banked XP");
		}
		else
		{
			List<Integer> selected = selection.stream()
				.sorted(Comparator.comparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
				.collect(Collectors.toList());
			onBankDisplay.accept(selected, "Selected items");
		}
	}

	private void addRows(List<Row> rows, Map<Integer, Integer> bank)
	{
		lastShownIds = rows.subList(0, Math.min(rows.size(), MAX_RESULTS)).stream()
			.map(row -> row.itemId).collect(Collectors.toList());
		if (!rows.isEmpty())
		{
			// the item rows sit inside one notched frame, checklist-style
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (Row row : rows.subList(0, Math.min(rows.size(), MAX_RESULTS)))
			{
				group.add(itemRow(row, bank.getOrDefault(row.itemId, 0)));
			}
			cap(group);
			list.add(group);
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		if (rows.size() > MAX_RESULTS)
		{
			list.add(faintLine("+ " + (rows.size() - MAX_RESULTS) + " more — refine your search"));
		}
		else if (rows.isEmpty())
		{
			list.add(faintLine("No banked items match."));
		}
	}

	/** icon · name · value (+ exclude box in the alch view). Click selects —
	 *  selected items glow in the real bank; hover shows the item's stats. */
	private JPanel itemRow(Row spec, int quantity)
	{
		int itemId = spec.itemId;
		boolean selected = selection.contains(itemId);
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(selected);
		if (selected)
		{
			row.setBackground(theme.selectFill);
		}
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		// 16px scaled sprite, count never baked in (the Route-row lesson —
		// the raw 36x32 sprite cropped in a smaller box)
		JLabel icon = new JLabel();
		Dimension iconSize = new Dimension(16, 16);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		if (itemManager != null)
		{
			AsyncBufferedImage sprite = itemManager.getImage(itemId);
			Runnable apply = () -> icon.setIcon(new ImageIcon(
				sprite.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH)));
			apply.run();
			sprite.onLoaded(apply);
		}
		row.add(icon);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		// the alch list reads in the small font (Luke, 2026-07-17)
		java.awt.Font rowFont = spec.excludable ? OsrsSkin.smallFont() : OsrsSkin.font();
		String tooltip = statsTooltip(itemId, quantity);
		OsrsLabel nameLabel = new OsrsLabel(state.itemName(itemId), OsrsSkin.LABEL, rowFont)
			.leftAligned().squeezable();
		nameLabel.setToolTipText(tooltip);
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(spec.rightText, OsrsSkin.VALUE, rowFont));

		MouseAdapter select = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!selection.remove(itemId))
				{
					selection.add(itemId);
				}
				rebuild(); // row band + Clear-selection count follow
			}
		};
		row.addMouseListener(select);
		// tooltip-bearing children swallow the row's listener — carry it
		nameLabel.addMouseListener(select);
		icon.addMouseListener(select);
		row.setToolTipText(tooltip);

		if (spec.excludable)
		{
			row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			StoneCheckbox exclude = new StoneCheckbox(theme, false);
			exclude.setToolTipText("Exclude from Highest alchs");
			exclude.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			exclude.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.setAlchExcluded(itemId, true); // notify re-renders the list
				}
			});
			row.add(exclude);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ── item data (client thread, cached per bank snapshot) ───────────

	private boolean dataReady()
	{
		return ("@" + state.getBankTimestamp()).equals(itemDataKey);
	}

	/** One sweep resolves equipment stats AND high-alch prices for every
	 *  banked item — compositions/stats are client-thread reads. */
	private void ensureItemData()
	{
		if (dataReady() || itemDataComputing || clientThread == null || itemManager == null)
		{
			return;
		}
		itemDataComputing = true;
		String key = "@" + state.getBankTimestamp();
		Map<Integer, Integer> bank = state.getBankSnapshot();
		clientThread.invokeLater(() ->
		{
			Map<Integer, ItemEquipmentStats> equipment = new HashMap<>();
			Map<Integer, Long> prices = new HashMap<>();
			for (int id : bank.keySet())
			{
				ItemStats stats = itemManager.getItemStats(id);
				if (stats != null && stats.getEquipment() != null)
				{
					equipment.put(id, stats.getEquipment());
				}
				long haPrice = itemManager.getItemComposition(id).getHaPrice();
				if (haPrice > 0)
				{
					prices.put(id, haPrice);
				}
			}
			SwingUtilities.invokeLater(() ->
			{
				equipStats = equipment;
				haPrices = prices;
				itemDataKey = key;
				itemDataComputing = false;
				rebuild();
			});
		});
	}

	/** The dropdown's stat index → its value on an equipable item. */
	static int statOf(ItemEquipmentStats e, int stat)
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

	/** The Item Stats-style hover: equipment bonuses grouped like the
	 *  game's equip screen, plus count and alch value. Falls back to
	 *  name + count while stats are unresolved (or headless). */
	private String statsTooltip(int itemId, int quantity)
	{
		StringBuilder sb = new StringBuilder("<html><b>")
			.append(state.itemName(itemId)).append("</b><br>×")
			.append(QuantityFormatter.quantityToStackSize(quantity)).append(" banked");
		Long haPrice = haPrices.get(itemId);
		if (haPrice != null)
		{
			sb.append("<br>High alch: ")
				.append(QuantityFormatter.quantityToStackSize(haPrice)).append(" gp each");
		}
		ItemEquipmentStats e = equipStats.get(itemId);
		if (e != null)
		{
			sb.append("<br><br>Attack &nbsp;— ").append(bonusLine(
				e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange()));
			sb.append("<br>Defence — ").append(bonusLine(
				e.getDstab(), e.getDslash(), e.getDcrush(), e.getDmagic(), e.getDrange()));
			sb.append("<br>Str ").append(sign(e.getStr()))
				.append(" · Ranged str ").append(sign(e.getRstr()))
				.append(" · Magic dmg ").append(Math.round(e.getMdmg())).append('%')
				.append(" · Prayer ").append(sign(e.getPrayer()));
		}
		return sb.append("</html>").toString();
	}

	private static String bonusLine(int stab, int slash, int crush, int magic, int range)
	{
		return "Stab " + sign(stab) + " · Slash " + sign(slash) + " · Crush " + sign(crush)
			+ " · Magic " + sign(magic) + " · Range " + sign(range);
	}

	private static String sign(int value)
	{
		return value > 0 ? "+" + value : String.valueOf(value);
	}

	// ── snapshot line + banked XP summary (unchanged grammar) ─────────

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
