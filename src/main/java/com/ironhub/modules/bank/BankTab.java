package com.ironhub.modules.bank;

import com.ironhub.data.BankedXpPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneCheckbox;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.RenderingHints;
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
 * chosen), an equipment-stat ranking dropdown, and an icon strip that is the
 * mode switchboard — a Highest-alchs view (Alchemiser semantics: HA price x
 * quantity or per-item, with persisted per-item exclusions and a reset),
 * five combat stat-group rankings (best gear in the bank per group), a
 * Runecrafting essence view over data/xp-actions.json, and per-skill
 * banked-XP views over the Banked Experience data port (with a persisted
 * per-skill target level + progress meter, auto-filled with the first
 * unbanked level when unset). Rows select with the list click grammar
 * (plain = exclusive, cmd/ctrl = toggle, shift = range) — selected items
 * glow in the real bank via the shared restock overlay, grow a stats card
 * with a Compare split, and hovering any row shows the item's equipment
 * stats (Item Stats-style tooltip). All item values resolve on the CLIENT
 * THREAD once per bank snapshot and cache. Frameless — the host's header
 * plate names the module.
 */
class BankTab extends JPanel
{
	/** Top-20 (Luke): the list ranks, it does not enumerate. */
	private static final int MAX_RESULTS = 20;
	/** Free-standing wrapped hints: the panel minus the tab's side padding. */
	private static final int HINT_WIDTH = UiTokens.PANEL_WIDTH - 20;

	// gameval names differ from the wiki's (BLANKRUNE = rune essence) —
	// pinned against data/index/item-names.json in BankTabTest
	static final int PURE_ESSENCE = net.runelite.api.gameval.ItemID.BLANKRUNE_HIGH;
	static final int RUNE_ESSENCE = net.runelite.api.gameval.ItemID.BLANKRUNE;
	static final int DAEYALT_ESSENCE = net.runelite.api.gameval.ItemID.BLANKRUNE_DAEYALT;

	/** Index 0 = no filter; the rest map through {@link #statOf}. */
	private static final String[] STAT_NAMES = {
		"Filter by stat…",
		"Stab attack", "Slash attack", "Crush attack", "Magic attack", "Ranged attack",
		"Melee strength", "Ranged strength", "Magic damage", "Prayer",
		"Stab defence", "Slash defence", "Crush defence", "Magic defence", "Ranged defence",
	};

	private enum Mode
	{
		SEARCH, ALCH, SKILL, STAT_GROUP, RUNECRAFT
	}

	/** The strip's combat rankings: bank gear by its best bonus in the group. */
	enum StatGroup
	{
		ATTACK(Skill.ATTACK), STRENGTH(Skill.STRENGTH), DEFENCE(Skill.DEFENCE),
		RANGED(Skill.RANGED), MAGIC(Skill.MAGIC);

		final Skill icon;

		StatGroup(Skill icon)
		{
			this.icon = icon;
		}
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
	private final com.ironhub.data.XpActionsPack xpActionsPack;
	/** The ids actually rendered by the last addRows (capped) — the SKILL
	 *  view sends exactly these to the bank. */
	private List<Integer> lastShownIds = List.of();
	/** The UNCAPPED result ids of the last addRows — stat-group views send
	 *  the whole ranking to the bank while the sidebar stays top-20. */
	private List<Integer> fullResultIds = List.of();
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StoneTextField search;
	private final javax.swing.JComboBox<String> statFilter;
	private final JPanel actionsHolder = new JPanel();
	private final JPanel skillStrip = new JPanel();
	private final JPanel statsHolder = new JPanel();
	private final JPanel list = new JPanel();
	private final StoneChipRow alchSort;
	private final JPanel xpSection = new JPanel();
	/** One long-lived field serves the SKILL and RC target inputs — a
	 *  per-rebuild field would eat keystrokes (each commit rebuilds). */
	private final StoneTextField targetField;
	private boolean seedingTarget;

	private Mode mode = Mode.SEARCH;
	private Skill skillMode;
	private StatGroup statGroup;
	/** The RC method the player picked (action name), else best available. */
	private String rcMethod;
	// skill-view session state (Banked Experience interface)
	private final Set<String> activeModifiers = new java.util.HashSet<>();
	private final Map<Integer, String> chosenActivity = new HashMap<>();
	/** The last clicked row's index in the current list order — shift+click
	 *  ranges from it (the click grammar, Luke 2026-07-17). */
	private int lastClickedIndex = -1;
	/** Item-stats card compare: 0 = single column, else the index (within
	 *  the selected order) shown beside the first; presses cycle it. */
	private int comparePick;
	/** Aggregated secondaries of the open SKILL view, need order — the bank
	 *  collect appends them after the main resources. */
	private List<Integer> skillSecondaryIds = List.of();

	/** Per-item equipment stats + high-alch prices for {@link #itemDataKey}'s
	 *  bank snapshot — one client-thread sweep serves the stat ranking, the
	 *  alch view and the hover tooltips. */
	/** CUMULATIVE per-item caches (stats and alch prices are immutable per
	 *  id): a bank change only ever scans the NEW ids, in chunks of a game
	 *  frame — the old whole-bank sweep in one client-thread callback was
	 *  a seconds-long game stall on a cold composition cache (Luke's
	 *  "crashing when I open my bank", 2026-07-18). */
	private final Map<Integer, ItemEquipmentStats> equipStats =
		new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<Integer, Long> haPrices =
		new java.util.concurrent.ConcurrentHashMap<>();
	private final Set<Integer> scannedIds =
		java.util.concurrent.ConcurrentHashMap.newKeySet();
	private String itemDataKey = "";
	private boolean itemDataComputing;
	/** Ids resolved per client frame — keeps each callback inside the frame
	 *  budget instead of one long stall. */
	private static final int SCAN_CHUNK = 128;

	BankTab(AccountState state, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.game.SkillIconManager skillIcons, Set<Integer> selection,
		java.util.function.BiConsumer<List<Integer>, String> onBankDisplay,
		BankedXpPack bankedXpPack, com.ironhub.data.XpActionsPack xpActionsPack,
		OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.skillIcons = skillIcons;
		this.selection = selection;
		this.onBankDisplay = onBankDisplay;
		this.bankedXpPack = bankedXpPack;
		this.xpActionsPack = xpActionsPack;
		this.theme = theme;
		this.search = new StoneTextField(theme, "Search bank…");
		this.statFilter = com.ironhub.ui.osrs.StoneComboBoxUI.skin(
			new javax.swing.JComboBox<>(STAT_NAMES), theme);
		this.alchSort = new StoneChipRow(theme, false, "Each", "Stack");
		this.alchSort.onChange(i -> rebuild());
		this.targetField = new StoneTextField(theme, null);
		this.targetField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				commitTarget();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				commitTarget();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				commitTarget();
			}
		});
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

		skillStrip.setLayout(new BoxLayout(skillStrip, BoxLayout.Y_AXIS));
		skillStrip.setOpaque(false);
		skillStrip.setAlignmentX(LEFT_ALIGNMENT);
		add(skillStrip);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		statsHolder.setLayout(new BoxLayout(statsHolder, BoxLayout.Y_AXIS));
		statsHolder.setOpaque(false);
		statsHolder.setAlignmentX(LEFT_ALIGNMENT);
		add(statsHolder);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// section header — always the tile grid (Luke, 2026-07-17: the
		// Grid/List chips are gone)
		JPanel xpHeader = new JPanel();
		xpHeader.setLayout(new BoxLayout(xpHeader, BoxLayout.X_AXIS));
		xpHeader.setOpaque(false);
		xpHeader.setAlignmentX(LEFT_ALIGNMENT);
		xpHeader.setBorder(new EmptyBorder(8, 4, 3, 4));
		xpHeader.add(new OsrsLabel("Banked XP", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		xpHeader.add(Box.createHorizontalGlue());
		cap(xpHeader);
		add(xpHeader);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		xpSection.setLayout(new BoxLayout(xpSection, BoxLayout.Y_AXIS));
		xpSection.setOpaque(false);
		xpSection.setAlignmentX(LEFT_ALIGNMENT);
		add(xpSection);
		add(Box.createVerticalGlue());

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

	/** Test seam: open a skill's banked-XP view as an icon click would. */
	void showSkillView(Skill skill)
	{
		mode = Mode.SKILL;
		skillMode = skill;
		seedTargetField();
		rebuild();
	}

	/** Test seam: open the Runecrafting essence view as its icon click would. */
	void showRunecraftView()
	{
		mode = Mode.RUNECRAFT;
		skillMode = null;
		seedTargetField();
		rebuild();
	}

	/** Test seam: what the target field currently shows. */
	String targetFieldText()
	{
		return targetField.getText();
	}

	/** Toggle a modifier as its checkbox row does (also the test seam).
	 *  Ticking one member of an exclusiveGroup unticks the group's others —
	 *  the upstream compatibleWith enforcement, carried in the pack. */
	void toggleModifier(String name)
	{
		if (!activeModifiers.remove(name))
		{
			activeModifiers.add(name);
			String group = exclusiveGroupOf(name);
			if (group != null)
			{
				activeModifiers.removeIf(other -> !other.equals(name)
					&& group.equals(exclusiveGroupOf(other)));
			}
		}
		rebuild();
	}

	private void rebuild()
	{
		boolean targetHadFocus = targetField.isFocusOwner();
		list.removeAll();
		lastShownIds = List.of(); // set by addRows; stale ids must not linger
		fullResultIds = List.of();
		skillSecondaryIds = List.of();
		rebuildActions();
		rebuildSkillStrip();
		Map<Integer, Integer> bank = state.getBankSnapshot();

		if (bank.isEmpty())
		{
			list.add(faintLine("Open your bank once to take a snapshot."));
		}
		else
		{
			String query = search.getText().trim();
			int stat = statFilter.getSelectedIndex();
			boolean needsItemData = mode == Mode.ALCH || mode == Mode.STAT_GROUP
				|| !selection.isEmpty() // the item-stats card wants equipment stats
				|| (mode == Mode.SEARCH && stat > 0);
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
			else if (mode == Mode.STAT_GROUP)
			{
				rebuildStatGroup(bank, query);
			}
			else if (mode == Mode.RUNECRAFT)
			{
				rebuildRunecraft(bank);
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
					.map(id -> new Row(id, null, false)) // the count IS the figure
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
		rebuildStats(); // after the view build — the card follows list order
		pushBankDisplay();
		rebuildBankedXp();
		actionsHolder.revalidate();
		actionsHolder.repaint();
		skillStrip.revalidate();
		skillStrip.repaint();
		statsHolder.revalidate();
		statsHolder.repaint();
		list.revalidate();
		list.repaint();
		if (targetHadFocus)
		{
			// the commit's own rebuild re-parents the field — keep the caret
			targetField.requestFocusInWindow();
		}
	}

	// ── views ─────────────────────────────────────────────────────────

	/** Alchemiser semantics: HA price × quantity (or per-item, the chip
	 *  row's pick), descending; excluded items hidden (persisted), each
	 *  row's checkbox excludes it. */
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
		JPanel sortHolder = new JPanel();
		sortHolder.setLayout(new BoxLayout(sortHolder, BoxLayout.X_AXIS));
		sortHolder.setOpaque(false);
		sortHolder.setAlignmentX(LEFT_ALIGNMENT);
		sortHolder.setBorder(new EmptyBorder(0, 4, 3, 4));
		sortHolder.add(alchSort);
		sortHolder.add(Box.createHorizontalGlue());
		cap(sortHolder);
		list.add(sortHolder);
		state.autoReturnAlchExclusions(bank); // qty-1 exclusions outgrown return
		Map<Integer, Long> prices = haPrices;
		List<Row> rows = prices.keySet().stream()
			.filter(bank::containsKey)
			.filter(id -> !state.isAlchExcluded(id, bank.get(id)))
			.filter(id -> matches(state.itemName(id), query))
			.sorted(alchComparator(prices, bank, alchSort.getSelected() == 0)
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.map(id -> new Row(id,
				alchFigure(stackValue(prices, bank, id), prices.get(id), bank.get(id)), true))
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

	/** Each = per-item HA price (the default chip); Stack = HA x quantity. */
	static Comparator<Integer> alchComparator(Map<Integer, Long> prices,
		Map<Integer, Integer> bank, boolean byEach)
	{
		return Comparator.comparingLong(
			id -> byEach ? -prices.get(id) : -stackValue(prices, bank, id));
	}

	/** "941K/192 gp/ea" — both figures RS-decimal truncated. A single item
	 *  has no stack figure: just "192 gp/ea" (Luke, 2026-07-17). */
	static String alchFigure(long stack, long each, int quantity)
	{
		String eachText = QuantityFormatter.quantityToRSDecimalStack(
			(int) Math.min(Integer.MAX_VALUE, each), true) + " gp/ea";
		if (quantity <= 1)
		{
			return eachText;
		}
		return QuantityFormatter.quantityToRSDecimalStack(
			(int) Math.min(Integer.MAX_VALUE, stack), true) + "/" + eachText;
	}

	private static long stackValue(Map<Integer, Long> prices, Map<Integer, Integer> bank, int id)
	{
		return prices.get(id) * bank.getOrDefault(id, 0);
	}

	/** Bank equipment ranked by its best bonus in the clicked stat group. */
	private void rebuildStatGroup(Map<Integer, Integer> bank, String query)
	{
		if (clientThread == null || itemManager == null)
		{
			list.add(faintLine("Equipment stats need the game client."));
			return;
		}
		if (!dataReady())
		{
			list.add(faintLine("Reading equipment stats…"));
			return;
		}
		Map<Integer, ItemEquipmentStats> equipment = equipStats;
		StatGroup group = statGroup;
		List<Row> rows = equipment.keySet().stream()
			.filter(bank::containsKey)
			.filter(id -> groupValue(equipment.get(id), group) > 0)
			.filter(id -> matches(state.itemName(id), query))
			.sorted(Comparator.<Integer>comparingInt(id -> -groupValue(equipment.get(id), group))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.map(id -> new Row(id, "+" + groupValue(equipment.get(id), group), false))
			.collect(Collectors.toList());
		addRows(rows, bank);
	}

	/** A stat group's headline number on an equipable item. */
	static int groupValue(ItemEquipmentStats e, StatGroup group)
	{
		switch (group)
		{
			case ATTACK: return Math.max(e.getAstab(), Math.max(e.getAslash(), e.getAcrush()));
			case STRENGTH: return e.getStr();
			case DEFENCE: return Math.max(Math.max(e.getDstab(), e.getDslash()),
				Math.max(e.getDcrush(), Math.max(e.getDmagic(), e.getDrange())));
			case RANGED: return Math.max(e.getArange(), e.getRstr());
			case MAGIC: return Math.max(e.getAmagic(), Math.round(e.getMdmg()));
			default: return 0;
		}
	}

	// ── Runecrafting view (data/xp-actions.json) ──────────────────────

	/** Essence counts, a Regular-method selector gated on the player's
	 *  level, and the banked RC xp those essences are worth. */
	private void rebuildRunecraft(Map<Integer, Integer> bank)
	{
		int level = state.getRealLevel(Skill.RUNECRAFT);
		int pure = bank.getOrDefault(PURE_ESSENCE, 0);
		int rune = bank.getOrDefault(RUNE_ESSENCE, 0);
		int daeyalt = bank.getOrDefault(DAEYALT_ESSENCE, 0);

		com.ironhub.data.XpActionsPack.SkillActions ladder = xpActionsPack.ladder(Skill.RUNECRAFT);
		List<com.ironhub.data.XpActionsPack.XpAction> methods = ladder == null ? List.of()
			: ladder.actions.stream()
				.filter(a -> "Regular".equals(a.type) && a.level <= level)
				.sorted(Comparator.comparingInt(a -> a.level))
				.collect(Collectors.toList());
		com.ironhub.data.XpActionsPack.XpAction current = null;
		for (com.ironhub.data.XpActionsPack.XpAction action : methods)
		{
			if (action.name.equals(rcMethod))
			{
				current = action;
			}
		}
		if (current == null && !methods.isEmpty())
		{
			current = methods.get(methods.size() - 1); // best the level allows
		}
		double daeyaltXp = current == null ? Double.NaN : daeyaltXpFor(ladder, current.name);
		double bankedTotal = current == null ? 0
			: pure * current.xp + (Double.isNaN(daeyaltXp) ? 0 : daeyalt * daeyaltXp);

		// header card mirrors the skill views (the banked breakdown line
		// below carries the total, so the shared banked line is skipped)
		list.add(headerCard(Skill.RUNECRAFT.getName(), level,
			state.getXp(Skill.RUNECRAFT), bankedTotal, 0, false));
		list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		// essence counts — absent = 0, shown honestly
		StonePanel essences = new StonePanel(theme);
		essences.setLayout(new BoxLayout(essences, BoxLayout.Y_AXIS));
		essences.setAlignmentX(LEFT_ALIGNMENT);
		essences.add(iconNameValueRow(PURE_ESSENCE, "Pure essence",
			"×" + QuantityFormatter.quantityToStackSize(pure), OsrsSkin.MUTED));
		essences.add(iconNameValueRow(RUNE_ESSENCE, "Rune essence",
			"×" + QuantityFormatter.quantityToStackSize(rune), OsrsSkin.MUTED));
		essences.add(iconNameValueRow(DAEYALT_ESSENCE, "Daeyalt essence",
			"×" + QuantityFormatter.quantityToStackSize(daeyalt), OsrsSkin.MUTED));
		cap(essences);
		list.add(essences);
		list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		if (methods.isEmpty())
		{
			list.add(faintLine("No runecrafting methods at your level in the pack."));
			return;
		}
		javax.swing.JComboBox<String> picker = com.ironhub.ui.osrs.StoneComboBoxUI.skin(
			new javax.swing.JComboBox<>(methods.stream()
				.map(a -> a.name).toArray(String[]::new)), theme);
		picker.setSelectedIndex(methods.indexOf(current));
		picker.setAlignmentX(LEFT_ALIGNMENT);
		List<com.ironhub.data.XpActionsPack.XpAction> options = methods;
		picker.addActionListener(e ->
		{
			rcMethod = options.get(picker.getSelectedIndex()).name;
			rebuild();
		});
		list.add(picker);
		list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		list.add(new OsrsLabel("Banked: " + formatXp(pure * current.xp) + " xp · "
			+ QuantityFormatter.quantityToStackSize(pure) + " × " + trimNumber(current.xp) + " xp",
			OsrsSkin.VALUE, OsrsSkin.smallFont()).leftAligned().squeezable());
		if (daeyalt > 0)
		{
			// a method with no Daeyalt rate in the pack stays honest: "?"
			list.add(new OsrsLabel(Double.isNaN(daeyaltXp)
				? "Daeyalt: ? — no Daeyalt rate for this method"
				: "Daeyalt: " + formatXp(daeyalt * daeyaltXp) + " xp · "
					+ QuantityFormatter.quantityToStackSize(daeyalt)
					+ " × " + trimNumber(daeyaltXp) + " xp",
				Double.isNaN(daeyaltXp) ? OsrsSkin.MUTED : OsrsSkin.VALUE,
				OsrsSkin.smallFont()).leftAligned().squeezable());
		}
	}

	/** The Daeyalt twin of a Regular action (same name), else NaN. */
	private static double daeyaltXpFor(com.ironhub.data.XpActionsPack.SkillActions ladder,
		String name)
	{
		for (com.ironhub.data.XpActionsPack.XpAction action : ladder.actions)
		{
			if ("Daeyalt".equals(action.type) && name.equals(action.name))
			{
				return action.xp;
			}
		}
		return Double.NaN;
	}

	/** A display-only row: 16px sprite + small-font name + right text. */
	private JPanel iconNameValueRow(int itemId, String name, String rightText,
		Color rightColor)
	{
		return iconNameValueRow(itemId, name, rightText, rightColor, OsrsSkin.LABEL);
	}

	private JPanel iconNameValueRow(int itemId, String name, String rightText,
		Color rightColor, Color nameColor)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(itemIcon(itemId));
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(name, nameColor, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(rightText, rightColor, OsrsSkin.smallFont()));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** The 16px scaled item sprite (blank headless — never invented). */
	private JLabel itemIcon(int itemId)
	{
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
		return icon;
	}

	/**
	 * The Banked Experience interface (Luke, 2026-07-17): level now → level
	 * banked, xp to next, modifier toggles, then the owned items ranked by
	 * total banked XP — each expandable into an activity selector with
	 * effective xp and secondaries (have/need). Totals count EVERY owned
	 * item at its chosen activity under the active modifiers; the list
	 * shows the top 20.
	 */
	private void rebuildSkillView(Map<Integer, Integer> bank, String query)
	{
		Map<Integer, List<BankedXpPack.Entry>> byItem = entriesByItem(skillMode);
		Map<Integer, BankedXpPack.Entry> chosen = new HashMap<>();
		double totalXp = 0;
		double selectionXp = 0;
		for (Map.Entry<Integer, List<BankedXpPack.Entry>> e : byItem.entrySet())
		{
			if (bank.containsKey(e.getKey()))
			{
				BankedXpPack.Entry pick = chosenEntry(e.getKey(), e.getValue());
				chosen.put(e.getKey(), pick);
				double xp = bank.get(e.getKey()) * effectiveXp(pick);
				totalXp += xp;
				if (selection.contains(e.getKey()))
				{
					selectionXp += xp;
				}
			}
		}

		// the CHOSEN activities of selected counted items gate the modifier
		// rows: a modifier none of them can use greys out (Luke, 2026-07-17)
		Set<String> selectedActivities = chosen.entrySet().stream()
			.filter(e -> selection.contains(e.getKey()))
			.map(e -> e.getValue().getActivity())
			.collect(Collectors.toSet());

		list.add(skillHeader(totalXp, selectionXp));
		for (BankedXpPack.Modifier modifier : skillModifiers(skillMode))
		{
			list.add(modifierRow(modifier, selectedActivities));
		}
		list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		List<Integer> ids = chosen.keySet().stream()
			.filter(id -> matches(state.itemName(id), query))
			.sorted(Comparator.<Integer>comparingDouble(
					id -> -bank.get(id) * effectiveXp(chosen.get(id)))
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.collect(Collectors.toList());
		if (ids.isEmpty())
		{
			list.add(faintLine("No banked " + skillMode.getName() + " XP found."));
		}
		else
		{
			lastShownIds = ids.subList(0, Math.min(ids.size(), MAX_RESULTS));
			fullResultIds = ids;
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			int index = 0;
			for (int id : lastShownIds)
			{
				group.add(itemRow(new Row(id,
					formatXp(bank.get(id) * effectiveXp(chosen.get(id))) + " xp", false),
					bank.get(id), index++));
			}
			cap(group);
			list.add(group);
			if (ids.size() > MAX_RESULTS)
			{
				list.add(faintLine("+ " + (ids.size() - MAX_RESULTS) + " more — refine your search"));
			}
		}

		// exactly one selected counted item: its activity chooser + detail
		// sit VISIBLY under the results (the value-click expansion hid it)
		Integer single = selection.size() == 1 && chosen.containsKey(selection.iterator().next())
			? selection.iterator().next() : null;
		if (single != null)
		{
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			StonePanel detailCard = new StonePanel(theme);
			detailCard.setLayout(new BoxLayout(detailCard, BoxLayout.Y_AXIS));
			detailCard.setAlignmentX(LEFT_ALIGNMENT);
			detailCard.add(new OsrsLabel(state.itemName(single), OsrsSkin.LABEL,
				OsrsSkin.smallFont()).leftAligned().squeezable());
			detailCard.add(skillDetail(single, byItem.get(single), chosen.get(single),
				bank.get(single)));
			cap(detailCard);
			list.add(detailCard);
		}
		addSecondariesSection(bank, chosen, single == null ? null : chosen.get(single));
	}

	/**
	 * The visible Secondaries section (Luke: the per-row expansion stayed
	 * too hidden): every secondary the CHOSEN entries of ALL counted items
	 * require, aggregated — required = ceil(sum of qty × item count),
	 * available = owned everywhere. Absent entirely when nothing needs one.
	 */
	private void addSecondariesSection(Map<Integer, Integer> bank,
		Map<Integer, BankedXpPack.Entry> chosen, BankedXpPack.Entry selectedEntry)
	{
		Map<Integer, Double> required = new HashMap<>();
		for (Map.Entry<Integer, BankedXpPack.Entry> e : chosen.entrySet())
		{
			if (e.getValue().getSecondaries() == null)
			{
				continue;
			}
			for (BankedXpPack.ItemQty secondary : e.getValue().getSecondaries())
			{
				required.merge(secondary.getItemId(),
					secondary.getQty() * bank.getOrDefault(e.getKey(), 0), Double::sum);
			}
		}
		if (required.isEmpty())
		{
			return;
		}
		// one item selected: its chosen activity's own secondaries stay lit,
		// the rest grey out (Luke, 2026-07-17)
		Set<Integer> litSecondaries = selectedEntry == null ? null
			: selectedEntry.getSecondaries() == null ? Set.of()
			: selectedEntry.getSecondaries().stream()
				.map(BankedXpPack.ItemQty::getItemId).collect(Collectors.toSet());
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(8, 4, 3, 4));
		header.add(new OsrsLabel("Secondaries", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		header.add(Box.createHorizontalGlue());
		cap(header);
		list.add(header);

		StonePanel group = new StonePanel(theme);
		group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
		group.setAlignmentX(LEFT_ALIGNMENT);
		int corner = theme.cornerStamp.length;
		group.setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));
		List<Map.Entry<Integer, Double>> inNeedOrder = required.entrySet().stream()
			.sorted(Comparator.<Map.Entry<Integer, Double>>comparingDouble(e -> -e.getValue())
				.thenComparing(e -> state.itemName(e.getKey()).toLowerCase(Locale.ROOT)))
			.collect(Collectors.toList());
		// the bank collect appends these after the main resources (need order)
		skillSecondaryIds = inNeedOrder.stream().map(Map.Entry::getKey)
			.collect(Collectors.toList());
		inNeedOrder.forEach(e ->
		{
			long need = (long) Math.ceil(e.getValue());
			long have = state.ownedCount(e.getKey());
			boolean greyed = litSecondaries != null && !litSecondaries.contains(e.getKey());
			group.add(iconNameValueRow(e.getKey(), state.itemName(e.getKey()),
				QuantityFormatter.quantityToStackSize(have)
					+ "/" + QuantityFormatter.quantityToStackSize(need),
				greyed ? OsrsSkin.FAINT
					: have >= need ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				greyed ? OsrsSkin.FAINT : OsrsSkin.LABEL));
		});
		cap(group);
		list.add(group);
	}

	/** The view header card: bold name — level (banked in green), banked xp,
	 *  xp to next, then the target controls. Shared by SKILL and RC views. */
	private JComponent skillHeader(double totalXp, double selectionXp)
	{
		return headerCard(skillMode.getName(), state.getRealLevel(skillMode),
			state.getXp(skillMode), totalXp, selectionXp, true);
	}

	private JComponent headerCard(String skillName, int level, int xpNow,
		double totalXp, double selectionXp, boolean showBankedLine)
	{
		int banked = bankedLevel(xpNow, totalXp);
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		// name BOLD, " — 72" regular, " (74 banked)" green regular — three
		// labels because the fonts differ (Luke, 2026-07-17)
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.add(new OsrsLabel(skillName, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		title.add(new OsrsLabel(" — " + level, OsrsSkin.TITLE, OsrsSkin.font()).leftAligned());
		title.add(new OsrsLabel(" (" + banked + " banked)", OsrsSkin.VALUE, OsrsSkin.font())
			.leftAligned());
		title.add(Box.createHorizontalGlue());
		cap(title);
		card.add(title);
		if (showBankedLine)
		{
			card.add(new OsrsLabel("Banked: " + formatXp(totalXp) + " xp",
				OsrsSkin.VALUE, OsrsSkin.smallFont()).leftAligned());
		}
		if (level < 99)
		{
			card.add(new OsrsLabel("Next level: "
				+ formatXp(net.runelite.api.Experience.getXpForLevel(level + 1) - xpNow) + " xp",
				OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		}
		addTargetControls(card, skillName, level, xpNow, totalXp, selectionXp);
		cap(card);
		return card;
	}

	/** The level the banked xp reaches (virtual, capped at 126). */
	private static int bankedLevel(int xpNow, double totalXp)
	{
		return net.runelite.api.Experience.getLevelForXp(
			(int) Math.min(200_000_000, xpNow + totalXp));
	}

	// ── per-skill target level (persisted) ────────────────────────────

	/** The skill the target field commits to, per the open view. */
	private String targetSkillName()
	{
		return mode == Mode.RUNECRAFT ? Skill.RUNECRAFT.getName()
			: skillMode != null ? skillMode.getName() : null;
	}

	/** Reseed the field for the view being opened; guarded so the document
	 *  listener never commits mid-seed. */
	private void seedTargetField()
	{
		seedingTarget = true;
		int target = state.getBankSkillTarget(targetSkillName());
		targetField.setText(target > 0 ? String.valueOf(target) : "");
		seedingTarget = false;
	}

	/** Commit on document change when parseable 2..126 (guarded-change in
	 *  AccountState — same value never persists or notifies). */
	private void commitTarget()
	{
		String skillName = targetSkillName();
		if (seedingTarget || skillName == null)
		{
			return;
		}
		try
		{
			int target = Integer.parseInt(targetField.getText().trim());
			if (target >= 2 && target <= 126)
			{
				state.setBankSkillTarget(skillName, target);
			}
		}
		catch (NumberFormatException e)
		{
			// partial or non-numeric input — the last committed target stands
		}
	}

	/**
	 * "Target level:" + a 3-digit field, the level-flanked meter (fill
	 * lerping red to green with progress) and the centered banked/selected
	 * total. With no persisted target the field auto-fills with the first
	 * unbanked level (banked level + 1, capped 126) so the bar shows
	 * immediately — the auto value never persists, only user edits do.
	 */
	private void addTargetControls(JPanel card, String skillName, int level, int xpNow,
		double bankedTotal, double selectionXp)
	{
		int persisted = state.getBankSkillTarget(skillName);
		int autoTarget = Math.min(126, bankedLevel(xpNow, bankedTotal) + 1);
		int target = persisted >= 2 ? persisted : autoTarget;
		// no persisted target: the field mirrors the auto value (a user edit
		// in range persists and stops this; only edits persist, never auto).
		// A modifier toggle moves the banked level, so a stale earlier auto
		// value re-seeds too — unless the user is mid-edit in the field.
		if (persisted == 0 && !targetField.isFocusOwner()
			&& !targetField.getText().trim().equals(String.valueOf(autoTarget)))
		{
			seedingTarget = true;
			targetField.setText(String.valueOf(autoTarget));
			seedingTarget = false;
		}

		card.add(Box.createVerticalStrut(2));
		JPanel fieldCap = new JPanel(new java.awt.BorderLayout());
		fieldCap.setOpaque(false);
		fieldCap.add(targetField);
		// sized to measured 3-digit ink (max effective level = 126)
		Dimension pref = targetField.getPreferredSize();
		int digitsWidth = targetField.getFontMetrics(targetField.getFont())
			.stringWidth("126") + 12; // edges 2+2 + padding 4+4
		pref = new Dimension(digitsWidth, pref.height);
		fieldCap.setPreferredSize(pref);
		fieldCap.setMaximumSize(pref);
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel("Target level: ", OsrsSkin.LABEL, OsrsSkin.smallFont())
			.leftAligned());
		row.add(fieldCap);
		row.add(Box.createHorizontalGlue());
		cap(row);
		card.add(row);

		if (target >= 2)
		{
			double goalXp = net.runelite.api.Experience.getXpForLevel(target);
			double fraction = Math.min(1, (xpNow + bankedTotal) / goalXp);
			com.ironhub.ui.osrs.StoneMeter meter = new com.ironhub.ui.osrs.StoneMeter(
				theme, lerp(UiTokens.STATUS_WARNING, OsrsSkin.VALUE, fraction), fraction);
			meter.setAlignmentX(LEFT_ALIGNMENT);
			JPanel meterRow = new JPanel();
			meterRow.setLayout(new BoxLayout(meterRow, BoxLayout.X_AXIS));
			meterRow.setOpaque(false);
			meterRow.setAlignmentX(LEFT_ALIGNMENT);
			meterRow.add(new OsrsLabel(String.valueOf(level), OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned());
			meterRow.add(Box.createHorizontalStrut(4));
			meterRow.add(meter);
			meterRow.add(Box.createHorizontalStrut(4));
			meterRow.add(new OsrsLabel(String.valueOf(target), OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned());
			card.add(Box.createVerticalStrut(2));
			cap(meterRow);
			card.add(meterRow);
			// centered: what the bar is made of — the selection total when
			// anything is selected, else all banked xp
			JPanel totalRow = new JPanel();
			totalRow.setLayout(new BoxLayout(totalRow, BoxLayout.X_AXIS));
			totalRow.setOpaque(false);
			totalRow.setAlignmentX(LEFT_ALIGNMENT);
			totalRow.add(Box.createHorizontalGlue());
			totalRow.add(new OsrsLabel(selectionXp > 0
				? formatXp(selectionXp) + " xp selected"
				: formatXp(bankedTotal) + " xp banked",
				selectionXp > 0 ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned());
			totalRow.add(Box.createHorizontalGlue());
			cap(totalRow);
			card.add(totalRow);
		}
	}

	/** Linear blend a → b by t (the meter's red-to-green progress fill). */
	static Color lerp(Color a, Color b, double t)
	{
		double f = Math.max(0, Math.min(1, t));
		return new Color(
			(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f));
	}

	/**
	 * One modifier toggle (small font); active reshapes every total. When a
	 * selection exists and the modifier applies to NONE of the selected
	 * items' chosen activities, the row greys out and ignores clicks.
	 */
	private JComponent modifierRow(BankedXpPack.Modifier modifier, Set<String> selectedActivities)
	{
		boolean greyed = !selectedActivities.isEmpty()
			&& selectedActivities.stream().noneMatch(a -> applies(modifier, a));
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		StoneCheckbox box = new StoneCheckbox(theme, activeModifiers.contains(modifier.getName()));
		box.setDimmed(greyed);
		row.add(box);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(modifier.getName(),
			greyed ? OsrsSkin.FAINT : OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		if (greyed)
		{
			row.setToolTipText("Not usable by the selected item's activity");
		}
		else
		{
			row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					toggleModifier(modifier.getName());
				}
			});
		}
		cap(row);
		return row;
	}

	/** The modifier's exclusive group in the pack, or null (independent). */
	private String exclusiveGroupOf(String name)
	{
		if (bankedXpPack.getModifiers() != null)
		{
			for (BankedXpPack.Modifier modifier : bankedXpPack.getModifiers())
			{
				if (modifier.getName().equals(name))
				{
					return modifier.getExclusiveGroup();
				}
			}
		}
		return null;
	}

	/** Expanded item detail: activity selector, effective xp, level gate,
	 *  secondaries with have/need from owned counts. */
	private JComponent skillDetail(int itemId, List<BankedXpPack.Entry> options,
		BankedXpPack.Entry current, int quantity)
	{
		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		detail.setAlignmentX(LEFT_ALIGNMENT);
		detail.setBorder(new EmptyBorder(0, 22, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP));

		if (options.size() > 1)
		{
			String[] names = options.stream().map(BankedXpPack.Entry::getMethod)
				.toArray(String[]::new);
			javax.swing.JComboBox<String> picker = com.ironhub.ui.osrs.StoneComboBoxUI.skin(
				new javax.swing.JComboBox<>(names), theme);
			picker.setSelectedIndex(options.indexOf(current));
			picker.setAlignmentX(LEFT_ALIGNMENT);
			picker.addActionListener(e ->
			{
				chosenActivity.put(itemId, options.get(picker.getSelectedIndex()).getActivity());
				rebuild();
			});
			detail.add(picker);
			detail.add(Box.createVerticalStrut(2));
		}
		StringBuilder line = new StringBuilder(trimNumber(effectiveXp(current)))
			.append(" xp each · ").append(formatXp(quantity * effectiveXp(current))).append(" total");
		if (current.getLevel() > state.getRealLevel(skillMode))
		{
			line.append(" · needs ").append(current.getLevel());
		}
		detail.add(new OsrsLabel(line.toString(), OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		if (current.getSecondaries() != null)
		{
			for (BankedXpPack.ItemQty secondary : current.getSecondaries())
			{
				long need = (long) Math.ceil(secondary.getQty() * quantity);
				detail.add(new OsrsLabel("· " + trimNumber(secondary.getQty()) + "x "
					+ state.itemName(secondary.getItemId())
					+ " — have " + QuantityFormatter.quantityToStackSize(
						state.ownedCount(secondary.getItemId()))
					+ " of " + QuantityFormatter.quantityToStackSize(need),
					state.ownedCount(secondary.getItemId()) >= need
						? OsrsSkin.VALUE : OsrsSkin.MUTED,
					OsrsSkin.smallFont()).leftAligned().squeezable());
			}
		}
		return detail;
	}

	/** "72", "202.5", "0.04" — never trailing zeros, never invented digits. */
	private static String trimNumber(double value)
	{
		return value == Math.rint(value) ? String.valueOf((long) value)
			: String.valueOf(value);
	}

	private List<BankedXpPack.Modifier> skillModifiers(Skill skill)
	{
		List<BankedXpPack.Modifier> out = new ArrayList<>();
		if (bankedXpPack.getModifiers() != null)
		{
			for (BankedXpPack.Modifier modifier : bankedXpPack.getModifiers())
			{
				if (skill.getName().equalsIgnoreCase(modifier.getSkill()))
				{
					out.add(modifier);
				}
			}
		}
		return out;
	}

	private boolean applies(BankedXpPack.Modifier modifier, String activity)
	{
		if (modifier.isAppliesToAll())
		{
			return modifier.getIgnores() == null || !modifier.getIgnores().contains(activity);
		}
		return modifier.getAppliesTo() != null && modifier.getAppliesTo().contains(activity);
	}

	/** xpEach under the active modifiers (all ported ones are multipliers). */
	private double effectiveXp(BankedXpPack.Entry entry)
	{
		double xp = entry.getXpEach();
		for (BankedXpPack.Modifier modifier : skillModifiers(skillMode))
		{
			if (activeModifiers.contains(modifier.getName()) && applies(modifier, entry.getActivity()))
			{
				xp = "additive".equals(modifier.getType())
					? xp + modifier.getValue() : xp * modifier.getValue();
			}
		}
		return xp;
	}

	/** All entries per item for one skill (activity options). */
	private Map<Integer, List<BankedXpPack.Entry>> entriesByItem(Skill skill)
	{
		Map<Integer, List<BankedXpPack.Entry>> byItem = new HashMap<>();
		for (BankedXpPack.Entry entry : bankedXpPack.getEntries())
		{
			if (skill.getName().equalsIgnoreCase(entry.getSkill()))
			{
				byItem.computeIfAbsent(entry.getItemId(), k -> new ArrayList<>()).add(entry);
			}
		}
		return byItem;
	}

	/** The player's activity pick for an item, else the best effective xp. */
	private BankedXpPack.Entry chosenEntry(int itemId, List<BankedXpPack.Entry> options)
	{
		String picked = chosenActivity.get(itemId);
		if (picked != null)
		{
			for (BankedXpPack.Entry option : options)
			{
				if (picked.equals(option.getActivity()))
				{
					return option;
				}
			}
		}
		BankedXpPack.Entry best = options.get(0);
		for (BankedXpPack.Entry option : options)
		{
			if (effectiveXp(option) > effectiveXp(best))
			{
				best = option;
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
		if (row.getComponentCount() > 0)
		{
			cap(row);
			actionsHolder.add(row);
			actionsHolder.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	/**
	 * The icon strip IS the mode switchboard. Top row: the High Alchemy
	 * spell (wiki art, bundled), a 1px engraved separator, then the five
	 * combat stat groups. Below, on their own row(s): Runecrafting and one
	 * icon per banked-XP pack skill (Luke, 2026-07-17). The active view's
	 * icon sits on the select fill; clicking it again returns to search.
	 * Skill icons need the game client's icon manager — absent headless.
	 */
	private void rebuildSkillStrip()
	{
		skillStrip.removeAll();
		JPanel topRow = new JPanel();
		topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
		topRow.setOpaque(false);
		topRow.setAlignmentX(LEFT_ALIGNMENT);
		java.awt.image.BufferedImage alchArt =
			com.ironhub.ui.osrs.OsrsIcons.image(theme, "high_alchemy");
		if (alchArt != null)
		{
			topRow.add(stripCell(new ImageIcon(alchArt), mode == Mode.ALCH,
				"Highest alchs", () ->
				{
					mode = mode == Mode.ALCH ? Mode.SEARCH : Mode.ALCH;
					skillMode = null;
				}));
		}
		if (skillIcons != null)
		{
			if (alchArt != null)
			{
				// a subtle engraved divide: alchs are values, the rest skills
				topRow.add(Box.createHorizontalStrut(4));
				JPanel divider = new JPanel();
				divider.setBackground(theme.edgeDark);
				divider.setPreferredSize(new Dimension(1, 10));
				divider.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
				topRow.add(divider);
				topRow.add(Box.createHorizontalStrut(4));
			}
			for (StatGroup group : StatGroup.values())
			{
				topRow.add(stripCell(new ImageIcon(skillIcons.getSkillImage(group.icon, true)),
					mode == Mode.STAT_GROUP && group == statGroup,
					group.icon.getName() + " — bank gear ranked", () ->
					{
						if (mode == Mode.STAT_GROUP && group == statGroup)
						{
							mode = Mode.SEARCH;
							statGroup = null;
						}
						else
						{
							mode = Mode.STAT_GROUP;
							statGroup = group;
							skillMode = null;
						}
					}));
				topRow.add(Box.createHorizontalStrut(2));
			}
		}
		topRow.add(Box.createHorizontalGlue());
		skillStrip.add(topRow);
		if (skillIcons == null)
		{
			return;
		}
		skillStrip.add(Box.createVerticalStrut(2));
		JPanel skillRows = new JPanel(new java.awt.GridLayout(0, 8, 2, 2));
		skillRows.setOpaque(false);
		skillRows.setAlignmentX(LEFT_ALIGNMENT);
		skillRows.add(stripCell(new ImageIcon(skillIcons.getSkillImage(Skill.RUNECRAFT, true)),
			mode == Mode.RUNECRAFT, "Runecraft — banked essence", () ->
			{
				if (mode == Mode.RUNECRAFT)
				{
					mode = Mode.SEARCH;
				}
				else
				{
					mode = Mode.RUNECRAFT;
					skillMode = null;
					seedTargetField();
				}
			}));
		for (Skill skill : packSkills())
		{
			skillRows.add(stripCell(new ImageIcon(skillIcons.getSkillImage(skill, true)),
				mode == Mode.SKILL && skill == skillMode,
				skill.getName() + " — banked XP view", () ->
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
						seedTargetField();
					}
				}));
		}
		JPanel rowsHolder = new JPanel();
		rowsHolder.setLayout(new BoxLayout(rowsHolder, BoxLayout.X_AXIS));
		rowsHolder.setOpaque(false);
		rowsHolder.setAlignmentX(LEFT_ALIGNMENT);
		rowsHolder.add(skillRows);
		rowsHolder.add(Box.createHorizontalGlue());
		skillStrip.add(rowsHolder);
	}

	/** One switchboard cell: icon at native size, select fill when active. */
	private JPanel stripCell(javax.swing.Icon icon, boolean active, String tooltip,
		Runnable onPress)
	{
		JPanel cell = new JPanel(new java.awt.BorderLayout());
		cell.setOpaque(active);
		if (active)
		{
			cell.setBackground(theme.selectFill);
		}
		cell.setBorder(new EmptyBorder(2, 2, 2, 2));
		JLabel label = new JLabel(icon);
		cell.add(label, java.awt.BorderLayout.CENTER);
		cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		cell.setToolTipText(tooltip);
		// in a BoxLayout row an uncapped cell stretches and BorderLayout
		// centres the icon mid-panel — cap it (GridLayout ignores max)
		cell.setMaximumSize(cell.getPreferredSize());
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onPress.run();
				rebuild();
			}
		});
		return cell;
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
		/** The green headline ("252K xp", "+5", "941K/192 gp/ea") — null for
		 *  plain search rows, where the muted count IS the figure. */
		final String figure;
		final boolean excludable;

		Row(int itemId, String figure, boolean excludable)
		{
			this.itemId = itemId;
			this.figure = figure;
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
			// main resources first, then the relevant secondaries in need
			// order — the bank shows both (Luke, 2026-07-17)
			List<Integer> collect = new ArrayList<>(lastShownIds);
			for (Integer secondary : skillSecondaryIds)
			{
				if (!collect.contains(secondary))
				{
					collect.add(secondary);
				}
			}
			onBankDisplay.accept(collect, skillMode.getName() + " banked XP");
		}
		else if (mode == Mode.STAT_GROUP && statGroup != null)
		{
			// a combat-style search shows EVERYTHING in the bank interface,
			// highest ranking first (Luke) — the sidebar stays capped
			onBankDisplay.accept(fullResultIds, statGroup.icon.getName() + " equipment");
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
		fullResultIds = rows.stream().map(row -> row.itemId).collect(Collectors.toList());
		if (!rows.isEmpty())
		{
			// the item rows sit inside one notched frame, checklist-style
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			int index = 0;
			for (Row row : rows.subList(0, Math.min(rows.size(), MAX_RESULTS)))
			{
				group.add(itemRow(row, bank.getOrDefault(row.itemId, 0), index++));
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

	/** icon · name · muted count (only above 1) · green figure (+ exclude
	 *  box in the alch view). Click grammar: plain = exclusive select,
	 *  cmd/ctrl = toggle, shift = range — selected items glow in the real
	 *  bank; hover shows the item's stats. */
	private JPanel itemRow(Row spec, int quantity, int index)
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
		JLabel icon = itemIcon(itemId);
		row.add(icon);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		// every result list reads in the small font (Luke, 2026-07-17)
		java.awt.Font rowFont = OsrsSkin.smallFont();
		String tooltip = statsTooltip(itemId, quantity);
		OsrsLabel nameLabel = new OsrsLabel(state.itemName(itemId), OsrsSkin.LABEL, rowFont)
			.leftAligned().squeezable();
		nameLabel.setToolTipText(tooltip);
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());
		if (quantity > 1)
		{
			// quantity first, in light text — then the figure; a lone item's
			// "x1" says nothing (Luke, 2026-07-17)
			row.add(new OsrsLabel("×" + QuantityFormatter.quantityToStackSize(quantity),
				OsrsSkin.MUTED, rowFont));
		}
		if (spec.figure != null)
		{
			row.add(Box.createHorizontalStrut(4));
			row.add(new OsrsLabel(spec.figure, OsrsSkin.VALUE, rowFont));
		}

		MouseAdapter select = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				lastClickedIndex = applyClick(selection, lastShownIds, index,
					e.isControlDown() || e.isMetaDown(), e.isShiftDown(), lastClickedIndex);
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
			// the resting grey x says "this box excludes" without a hover
			StoneCheckbox exclude = new StoneCheckbox(theme, false, true);
			exclude.setToolTipText("Exclude from Highest alchs");
			exclude.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			exclude.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					// remembers the quantity: excluded at 1 auto-returns
					// once the player owns more (notify re-renders the list)
					state.setAlchExcluded(itemId, quantity);
				}
			});
			row.add(exclude);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/**
	 * The list click grammar (Luke, 2026-07-17), pure for testing: plain
	 * click = exclusive select (clicking the ONLY selected row deselects
	 * it); cmd/ctrl = toggle without clearing; shift = select the contiguous
	 * range from the last-clicked row in the current order. Returns the new
	 * last-clicked index.
	 */
	static int applyClick(Set<Integer> selection, List<Integer> order, int index,
		boolean toggle, boolean range, int lastIndex)
	{
		int itemId = order.get(index);
		if (range && lastIndex >= 0 && lastIndex < order.size())
		{
			for (int i = Math.min(index, lastIndex); i <= Math.max(index, lastIndex); i++)
			{
				selection.add(order.get(i));
			}
		}
		else if (toggle)
		{
			if (!selection.remove(itemId))
			{
				selection.add(itemId);
			}
		}
		else
		{
			boolean only = selection.size() == 1 && selection.contains(itemId);
			selection.clear();
			if (!only)
			{
				selection.add(itemId);
			}
		}
		return index;
	}

	// ── item stats card (selection) ───────────────────────────────────

	/** The selected items in the current list order, name order for any
	 *  selection outside the open list — a Set has no honest "first". */
	private List<Integer> selectedOrdered()
	{
		List<Integer> ordered = new ArrayList<>();
		for (Integer id : fullResultIds)
		{
			if (selection.contains(id))
			{
				ordered.add(id);
			}
		}
		selection.stream()
			.filter(id -> !ordered.contains(id))
			.sorted(Comparator.comparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.forEach(ordered::add);
		return ordered;
	}

	/**
	 * The selected item's stat card (Luke, 2026-07-17): the 36x32 sprite
	 * unscaled + readable equipment stats. Compare splits the card into two
	 * columns — first selected vs the next — and repeated presses cycle the
	 * right column through further selections ("+N more" says how many).
	 * Non-equipment shows name + count + alch value only.
	 */
	private void rebuildStats()
	{
		statsHolder.removeAll();
		List<Integer> ordered = selectedOrdered();
		if (ordered.isEmpty())
		{
			comparePick = 0;
			return;
		}
		if (comparePick >= ordered.size())
		{
			comparePick = 0;
		}
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		JPanel columns = new JPanel(new java.awt.GridLayout(1, comparePick > 0 ? 2 : 1, 8, 0));
		columns.setOpaque(false);
		columns.setAlignmentX(LEFT_ALIGNMENT);
		columns.add(statsColumn(ordered.get(0)));
		if (comparePick > 0)
		{
			columns.add(statsColumn(ordered.get(comparePick)));
		}
		card.add(columns);
		if (ordered.size() > 1)
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			JPanel controls = new JPanel();
			controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
			controls.setOpaque(false);
			controls.setAlignmentX(LEFT_ALIGNMENT);
			controls.add(new StoneButton(theme, "Compare", () ->
			{
				comparePick = comparePick + 1 < ordered.size() ? comparePick + 1 : 1;
				rebuild();
			}));
			if (ordered.size() > 2)
			{
				controls.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
				controls.add(new OsrsLabel("+" + (ordered.size() - 2)
					+ " more — press again", OsrsSkin.FAINT, OsrsSkin.smallFont())
					.leftAligned().squeezable());
			}
			controls.add(Box.createHorizontalGlue());
			cap(controls);
			card.add(controls);
		}
		cap(card);
		statsHolder.add(card);
		statsHolder.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
	}

	/** One column of the card: sprite, name, count, alch, equipment lines. */
	private JComponent statsColumn(int itemId)
	{
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		col.setAlignmentX(LEFT_ALIGNMENT);
		if (itemManager != null)
		{
			// the raw 36x32 sprite, unscaled
			JLabel icon = new JLabel();
			icon.setAlignmentX(LEFT_ALIGNMENT);
			AsyncBufferedImage sprite = itemManager.getImage(itemId);
			Runnable apply = () -> icon.setIcon(new ImageIcon(sprite));
			apply.run();
			sprite.onLoaded(apply);
			col.add(icon);
		}
		col.add(new OsrsLabel(state.itemName(itemId), OsrsSkin.LABEL, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		int quantity = state.getBankSnapshot().getOrDefault(itemId, 0);
		if (quantity > 1)
		{
			col.add(new OsrsLabel("×" + QuantityFormatter.quantityToStackSize(quantity),
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		Long haPrice = haPrices.get(itemId);
		if (haPrice != null)
		{
			col.add(new OsrsLabel("Alch " + QuantityFormatter.quantityToStackSize(haPrice)
				+ " gp", OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
		}
		ItemEquipmentStats e = equipStats.get(itemId);
		if (e != null)
		{
			col.add(Box.createVerticalStrut(2));
			statLine(col, "Atk / Def", OsrsSkin.FAINT);
			statLine(col, "Stab " + sign(e.getAstab()) + " / " + sign(e.getDstab()), OsrsSkin.MUTED);
			statLine(col, "Slash " + sign(e.getAslash()) + " / " + sign(e.getDslash()), OsrsSkin.MUTED);
			statLine(col, "Crush " + sign(e.getAcrush()) + " / " + sign(e.getDcrush()), OsrsSkin.MUTED);
			statLine(col, "Magic " + sign(e.getAmagic()) + " / " + sign(e.getDmagic()), OsrsSkin.MUTED);
			statLine(col, "Range " + sign(e.getArange()) + " / " + sign(e.getDrange()), OsrsSkin.MUTED);
			statLine(col, "Str " + sign(e.getStr()) + " · R.str " + sign(e.getRstr()), OsrsSkin.MUTED);
			statLine(col, "M.dmg " + Math.round(e.getMdmg()) + "% · Pray " + sign(e.getPrayer()),
				OsrsSkin.MUTED);
		}
		return col;
	}

	private static void statLine(JPanel col, String text, Color color)
	{
		col.add(new OsrsLabel(text, color, OsrsSkin.smallFont()).leftAligned().squeezable());
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
		String key = "@" + state.getBankTimestamp();
		List<Integer> missing = state.getBankSnapshot().keySet().stream()
			.filter(id -> !scannedIds.contains(id))
			.collect(Collectors.toList());
		if (missing.isEmpty())
		{
			itemDataKey = key; // everything current is already cached
			return;
		}
		itemDataComputing = true;
		java.util.Iterator<Integer> pending = missing.iterator();
		clientThread.invokeLater(() ->
		{
			int scanned = 0;
			while (pending.hasNext() && scanned++ < SCAN_CHUNK)
			{
				int id = pending.next();
				ItemStats stats = itemManager.getItemStats(id);
				if (stats != null && stats.getEquipment() != null)
				{
					equipStats.put(id, stats.getEquipment());
				}
				long haPrice = itemManager.getItemComposition(id).getHaPrice();
				if (haPrice > 0)
				{
					haPrices.put(id, haPrice);
				}
				scannedIds.add(id);
			}
			if (pending.hasNext())
			{
				return false; // continue on the next client frame
			}
			SwingUtilities.invokeLater(() ->
			{
				itemDataKey = key;
				itemDataComputing = false;
				rebuild();
			});
			return true;
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

	// ── banked XP summary (slab tiles, always the grid) ───────────────

	private void rebuildBankedXp()
	{
		xpSection.removeAll();
		Map<net.runelite.api.Skill, BankedXp.Result> totals = BankedXp.compute(state, bankedXpPack);
		if (totals.isEmpty())
		{
			xpSection.add(faintLine("No bankable XP found."));
		}
		else
		{
			JPanel grid = new JPanel(new java.awt.GridLayout(0, 3, 4, 4));
			grid.setOpaque(false);
			grid.setAlignmentX(LEFT_ALIGNMENT);
			totals.forEach((skill, result) -> grid.add(new XpTile(skill, result)));
			int pad = 3 - (totals.size() % 3);
			for (int i = 0; pad < 3 && i < pad; i++)
			{
				grid.add(emptyCell());
			}
			cap(grid);
			xpSection.add(grid);
		}
		xpSection.revalidate();
		xpSection.repaint();
	}

	private JPanel emptyCell()
	{
		JPanel cell = new JPanel();
		cell.setOpaque(false);
		return cell;
	}

	/**
	 * One banked-XP tile (slab grammar, 3 per row): skill icon (skipped
	 * headless), current level and banked level either side of a PAINTED
	 * arrow (the RuneScape fonts carry no arrow glyph), and a thin meter of
	 * progress from the current level to the NEXT within the xp table —
	 * fraction = (xpNow + banked - xpFor(cur)) / (xpFor(cur+1) - xpFor(cur)).
	 */
	private final class XpTile extends JComponent
	{
		private final java.awt.image.BufferedImage icon;
		private final int current;
		private final int banked;
		private final double fraction;

		XpTile(net.runelite.api.Skill skill, BankedXp.Result result)
		{
			this.icon = skillIcons == null ? null : skillIcons.getSkillImage(skill, true);
			int xpNow = state.getXp(skill);
			this.current = net.runelite.api.Experience.getLevelForXp(xpNow);
			this.banked = bankedLevel(xpNow, result.xp);
			if (current >= 126)
			{
				this.fraction = 1;
			}
			else
			{
				double floor = net.runelite.api.Experience.getXpForLevel(current);
				double ceil = net.runelite.api.Experience.getXpForLevel(current + 1);
				this.fraction = Math.max(0, Math.min(1,
					(xpNow + result.xp - floor) / (ceil - floor)));
			}
			setToolTipText(tooltip(skill, result));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(69, 36);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			int w = getWidth(), h = getHeight();
			com.ironhub.ui.osrs.StoneNavButton.paintSlab(g2, theme, w, h,
				theme.boxFill, theme.edgeLight);
			int x = 6;
			if (icon != null)
			{
				// native size, never rescaled — the container fits the art
				g2.drawImage(icon, x, Math.max(2, (h - 10 - icon.getHeight()) / 2), null);
				x += icon.getWidth() + 4;
			}
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setFont(OsrsSkin.smallFont());
			int baseline = 17;
			x = drawShadowed(g2, String.valueOf(current), x, baseline, OsrsSkin.MUTED);
			x += 3;
			// painted right arrow: 3px shaft + chevron head, text-centred
			g2.setColor(OsrsSkin.MUTED);
			int ay = baseline - 4;
			g2.fillRect(x, ay, 5, 1);
			for (int i = 0; i < 3; i++)
			{
				g2.fillRect(x + 3 + i, ay - 2 + i, 1, 1);
				g2.fillRect(x + 3 + i, ay + 2 - i, 1, 1);
			}
			x += 7 + 3;
			drawShadowed(g2, String.valueOf(banked), x, baseline, OsrsSkin.VALUE);
			// the thin meter (StoneMeter anatomy: recess trough, 1px inset fill)
			int barY = h - 10;
			g2.setColor(theme.recess);
			g2.fillRect(6, barY, w - 12, 5);
			g2.setColor(OsrsSkin.PROGRESS_BLUE);
			g2.fillRect(7, barY + 1, (int) Math.round((w - 14) * fraction), 3);
			OsrsSkin.outline(g2, theme.edgeDark, 6, barY, w - 12, 5);
		}

		/** Shadowed pixel text; returns the x after the drawn string. */
		private int drawShadowed(java.awt.Graphics2D g2, String text, int x, int baseline,
			Color color)
		{
			g2.setColor(OsrsSkin.TEXT_SHADOW);
			g2.drawString(text, x + 1, baseline + 1);
			g2.setColor(color);
			g2.drawString(text, x, baseline);
			return x + g2.getFontMetrics().stringWidth(text);
		}
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
