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
 * chosen), an equipment-stat ranking dropdown, and an icon strip that is the
 * mode switchboard — a Highest-alchs view (Alchemiser semantics: HA price x
 * quantity or per-item, with persisted per-item exclusions and a reset),
 * five combat stat-group rankings (best gear in the bank per group), a
 * Runecrafting essence view over data/xp-actions.json, and per-skill
 * banked-XP views over the Banked Experience data port (with a persisted
 * per-skill target level + progress meter). Rows multi-select — selected
 * items glow in the real bank via the shared restock overlay — and hovering
 * any row shows the item's equipment stats (Item Stats-style tooltip). All
 * item values resolve on the CLIENT THREAD once per bank snapshot and
 * cache. Frameless — the host's header plate names the module.
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
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StoneTextField search;
	private final javax.swing.JComboBox<String> statFilter;
	private final JPanel actionsHolder = new JPanel();
	private final JPanel skillStrip = new JPanel();
	private final JPanel list = new JPanel();
	private final StoneChipRow xpView;
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
	private final Set<Integer> expandedItems = new java.util.HashSet<>();

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
		BankedXpPack bankedXpPack, com.ironhub.data.XpActionsPack xpActionsPack,
		boolean gridView, java.util.function.Consumer<Boolean> onViewChange, OsrsTheme theme)
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
		this.xpView = new StoneChipRow(theme, false, "Grid", "List");
		this.alchSort = new StoneChipRow(theme, false, "Stack", "Each");
		this.alchSort.onChange(i -> rebuild());
		this.targetField = new StoneTextField(theme, "Target…");
		this.targetField.setColumns(3);
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

	/** Test seam: toggle a modifier as its checkbox row would. */
	void toggleModifier(String name)
	{
		if (!activeModifiers.remove(name))
		{
			activeModifiers.add(name);
		}
		rebuild();
	}

	private void rebuild()
	{
		boolean targetHadFocus = targetField.isFocusOwner();
		list.removeAll();
		lastShownIds = List.of(); // set by addRows; stale ids must not linger
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
		pushBankDisplay();
		rebuildBankedXp();
		actionsHolder.revalidate();
		actionsHolder.repaint();
		skillStrip.revalidate();
		skillStrip.repaint();
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
		Map<Integer, Long> prices = haPrices;
		List<Row> rows = prices.keySet().stream()
			.filter(bank::containsKey)
			.filter(id -> !state.isAlchExcluded(id))
			.filter(id -> matches(state.itemName(id), query))
			.sorted(alchComparator(prices, bank, alchSort.getSelected() == 1)
				.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
			.map(id -> new Row(id,
				alchFigure(stackValue(prices, bank, id), prices.get(id)), true))
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

	/** Stack sort = total HA value of the banked stack; Each = per item. */
	static Comparator<Integer> alchComparator(Map<Integer, Long> prices,
		Map<Integer, Integer> bank, boolean byEach)
	{
		return Comparator.comparingLong(
			id -> byEach ? -prices.get(id) : -stackValue(prices, bank, id));
	}

	/** "941K/192 gp/ea" — both figures RS-decimal truncated. */
	static String alchFigure(long stack, long each)
	{
		return QuantityFormatter.quantityToRSDecimalStack(
			(int) Math.min(Integer.MAX_VALUE, stack), true)
			+ "/" + QuantityFormatter.quantityToRSDecimalStack(
			(int) Math.min(Integer.MAX_VALUE, each), true) + " gp/ea";
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

		// header card mirrors the skill views: level, target, progress
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.add(new OsrsLabel(Skill.RUNECRAFT.getName() + " — level " + level,
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		addTargetControls(card, Skill.RUNECRAFT.getName(), state.getXp(Skill.RUNECRAFT), bankedTotal);
		cap(card);
		list.add(card);
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
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(itemIcon(itemId));
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(name, OsrsSkin.LABEL, OsrsSkin.smallFont())
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
		for (Map.Entry<Integer, List<BankedXpPack.Entry>> e : byItem.entrySet())
		{
			if (bank.containsKey(e.getKey()))
			{
				BankedXpPack.Entry pick = chosenEntry(e.getKey(), e.getValue());
				chosen.put(e.getKey(), pick);
				totalXp += bank.get(e.getKey()) * effectiveXp(pick);
			}
		}

		list.add(skillHeader(totalXp));
		for (BankedXpPack.Modifier modifier : skillModifiers(skillMode))
		{
			list.add(modifierRow(modifier));
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
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (int id : lastShownIds)
			{
				group.add(itemRow(new Row(id,
					formatXp(bank.get(id) * effectiveXp(chosen.get(id))) + " xp", false),
					bank.get(id)));
				if (expandedItems.contains(id))
				{
					group.add(skillDetail(id, byItem.get(id), chosen.get(id), bank.get(id)));
				}
			}
			cap(group);
			list.add(group);
			if (ids.size() > MAX_RESULTS)
			{
				list.add(faintLine("+ " + (ids.size() - MAX_RESULTS) + " more — refine your search"));
			}
		}
		addSecondariesSection(bank, chosen);
	}

	/**
	 * The visible Secondaries section (Luke: the per-row expansion stayed
	 * too hidden): every secondary the CHOSEN entries of ALL counted items
	 * require, aggregated — required = ceil(sum of qty × item count),
	 * available = owned everywhere. Absent entirely when nothing needs one.
	 */
	private void addSecondariesSection(Map<Integer, Integer> bank,
		Map<Integer, BankedXpPack.Entry> chosen)
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
		required.entrySet().stream()
			.sorted(Comparator.<Map.Entry<Integer, Double>>comparingDouble(e -> -e.getValue())
				.thenComparing(e -> state.itemName(e.getKey()).toLowerCase(Locale.ROOT)))
			.forEach(e ->
			{
				long need = (long) Math.ceil(e.getValue());
				long have = state.ownedCount(e.getKey());
				group.add(iconNameValueRow(e.getKey(), state.itemName(e.getKey()),
					QuantityFormatter.quantityToStackSize(have)
						+ "/" + QuantityFormatter.quantityToStackSize(need),
					have >= need ? OsrsSkin.VALUE : OsrsSkin.MUTED));
			});
		cap(group);
		list.add(group);
	}

	/** Skill icon + level now → level banked + xp to next, in a stone card. */
	private JComponent skillHeader(double totalXp)
	{
		int level = state.getRealLevel(skillMode);
		int xpNow = state.getXp(skillMode);
		int banked = Math.min(99,
			net.runelite.api.Experience.getLevelForXp((int) Math.min(200_000_000, xpNow + totalXp)));
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.add(new OsrsLabel(skillMode.getName() + " — level " + level,
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		card.add(new OsrsLabel("Banked: " + formatXp(totalXp) + " xp · level banked: " + banked
			+ (banked > level ? " (+" + (banked - level) + ")" : ""),
			OsrsSkin.VALUE, OsrsSkin.smallFont()).leftAligned());
		if (level < 99)
		{
			card.add(new OsrsLabel("Next level: "
				+ formatXp(net.runelite.api.Experience.getXpForLevel(level + 1) - xpNow) + " xp",
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		addTargetControls(card, skillMode.getName(), xpNow, totalXp);
		cap(card);
		return card;
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

	/** Commit on document change when parseable 2..99 (guarded-change in
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
			if (target >= 2 && target <= 99)
			{
				state.setBankSkillTarget(skillName, target);
			}
		}
		catch (NumberFormatException e)
		{
			// partial or non-numeric input — the last committed target stands
		}
	}

	/** The target input + (when set) progress meter and reach line. */
	private void addTargetControls(JPanel card, String skillName, int xpNow, double bankedTotal)
	{
		card.add(Box.createVerticalStrut(2));
		JPanel fieldCap = new JPanel(new java.awt.BorderLayout());
		fieldCap.setOpaque(false);
		fieldCap.add(targetField);
		// 3 columns clips the placeholder ("Targe") — measured ink, not
		// nominal columns, decides the width; the cap keeps it small
		Dimension pref = targetField.getPreferredSize();
		int placeholderWidth = targetField.getFontMetrics(targetField.getFont())
			.stringWidth("Target…") + 12; // edges 2+2 + padding 4+4
		pref = new Dimension(Math.max(pref.width, placeholderWidth), pref.height);
		fieldCap.setPreferredSize(pref);
		fieldCap.setMaximumSize(pref);
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(fieldCap);
		row.add(Box.createHorizontalGlue());
		cap(row);
		card.add(row);

		int target = state.getBankSkillTarget(skillName);
		if (target >= 2)
		{
			double goalXp = net.runelite.api.Experience.getXpForLevel(target);
			com.ironhub.ui.osrs.StoneMeter meter = new com.ironhub.ui.osrs.StoneMeter(
				theme, OsrsSkin.PROGRESS_BLUE, Math.min(1, (xpNow + bankedTotal) / goalXp));
			meter.setAlignmentX(LEFT_ALIGNMENT);
			card.add(Box.createVerticalStrut(2));
			card.add(meter);
			int reaches = Math.min(99, net.runelite.api.Experience.getLevelForXp(
				(int) Math.min(200_000_000, xpNow + bankedTotal)));
			card.add(new OsrsLabel("Banked reaches level " + reaches + " of target " + target,
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
	}

	/** One modifier toggle (small font); active reshapes every total. */
	private JComponent modifierRow(BankedXpPack.Modifier modifier)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		StoneCheckbox box = new StoneCheckbox(theme, activeModifiers.contains(modifier.getName()));
		row.add(box);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(modifier.getName(), OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!activeModifiers.remove(modifier.getName()))
				{
					activeModifiers.add(modifier.getName());
				}
				rebuild();
			}
		});
		cap(row);
		return row;
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
	 * The icon strip IS the mode switchboard: the High Alchemy spell (wiki
	 * art, bundled), the five combat stat groups, Runecrafting, then one
	 * icon per banked-XP pack skill. The active view's icon sits on the
	 * select fill; clicking it again returns to search. Skill icons need
	 * the game client's icon manager — those cells are absent headless.
	 */
	private void rebuildSkillStrip()
	{
		skillStrip.removeAll();
		java.awt.image.BufferedImage alchArt =
			com.ironhub.ui.osrs.OsrsIcons.image(theme, "high_alchemy");
		if (alchArt != null)
		{
			skillStrip.add(stripCell(new ImageIcon(alchArt), mode == Mode.ALCH,
				"Highest alchs", () ->
				{
					mode = mode == Mode.ALCH ? Mode.SEARCH : Mode.ALCH;
					skillMode = null;
				}));
		}
		if (skillIcons == null)
		{
			return;
		}
		for (StatGroup group : StatGroup.values())
		{
			skillStrip.add(stripCell(new ImageIcon(skillIcons.getSkillImage(group.icon, true)),
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
		}
		skillStrip.add(stripCell(new ImageIcon(skillIcons.getSkillImage(Skill.RUNECRAFT, true)),
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
			skillStrip.add(stripCell(new ImageIcon(skillIcons.getSkillImage(skill, true)),
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

	/** icon · name · muted count · green figure (+ exclude box in the alch
	 *  view; plain search rows show just the count). Click selects —
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
		// quantity first, in light text — then the figure
		row.add(new OsrsLabel("×" + QuantityFormatter.quantityToStackSize(quantity),
			OsrsSkin.MUTED, rowFont));
		if (spec.figure != null)
		{
			row.add(Box.createHorizontalStrut(4));
			OsrsLabel value = new OsrsLabel(spec.figure, OsrsSkin.VALUE, rowFont);
			if (mode == Mode.SKILL)
			{
				// the xp value opens the item's activity + secondaries detail;
				// the rest of the row still selects
				value.setToolTipText("Activity & secondaries");
				value.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						if (!expandedItems.remove(itemId))
						{
							expandedItems.add(itemId);
						}
						rebuild();
					}
				});
			}
			row.add(value);
		}

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

	// ── banked XP summary (unchanged grammar) ─────────────────────────

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
