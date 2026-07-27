package com.ironhub.modules.gear;

import com.ironhub.data.EquipmentPack;
import com.ironhub.data.ItemSourcesPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2Dropdown;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The Gear module's home (Luke, 2026-07-24): a searchable, filterable,
 * sortable LIBRARY of every wearable in the game — the whole
 * {@link EquipmentPack}, not an 'optimal' few — with the older progression
 * chart folded into a collapsible section beneath it.
 *
 * <p>Filter by slot, by owned/unowned, by members/free; sort by value or by
 * any single combat bonus. A row's sprite, name and the active metric; a
 * click expands it into the item's full stat block, where it comes from
 * ({@link ItemSourcesPack}) and a track affordance. Ownership and where-from
 * are read live so the library never disagrees with the rest of the plugin.
 */
class GearLibraryTab extends JPanel
{
	/** The 12 slots, plus an all-slots first option. */
	private static final String[] SLOT_LABELS = {"All slots", "Head", "Cape", "Neck",
		"Ammo", "Weapon", "Two-handed", "Body", "Shield", "Legs", "Hands", "Feet", "Ring"};
	private static final String[] SLOT_KEYS = {null, "head", "cape", "neck", "ammo",
		"weapon", "2h", "body", "shield", "legs", "hands", "feet", "ring"};
	/** Row ceiling (the Bank tab's grammar): the library is ~3,900 items. */
	private static final int WRAP = 185;

	private final AccountState state;
	private final EquipmentPack pack;
	private final EquipmentLibrary library;
	private final ItemSourcesPack itemSources;
	private final ItemManager itemManager; // null in headless tests
	private final net.runelite.client.callback.ClientThread clientThread; // null in headless tests
	private final OsrsTheme theme;
	private final SpriteCache sprites;
	/**
	 * Live GE prices, resolved in ONE client-thread sweep (the Bank tab's
	 * rule). {@link ItemManager#getItemPrice} calls getItemComposition, which
	 * asserts the client thread — calling it on the EDT during the default
	 * Value sort threw and left the tab unmounted (Luke's report). Until the
	 * sweep lands, the value is the offline high-alch fallback.
	 */
	private volatile java.util.Map<Integer, Integer> priceCache = java.util.Map.of();
	private boolean pricesRequested;
	private final Runnable listener = RebuildGate.install(this, this::onStateChanged);
	/** The progression chart, hosted in a collapsible section below. */
	private final GearTab chart;

	// controls
	private final V2TextField search;
	private final V2Dropdown slotBox;
	private final V2Dropdown sortBox;
	private final JLabel sortDirection;
	private final V2ChipRow ownedChips;
	private final V2ChipRow accessChips;
	private final JPanel list = new JPanel();
	private final JLabel chartTriangle;
	private final JPanel chartSlot = new JPanel();

	private EquipmentLibrary.Sort sort = EquipmentLibrary.Sort.VALUE;
	private boolean ascending;
	private EquipmentLibrary.Owned owned = EquipmentLibrary.Owned.ALL;
	private EquipmentLibrary.Access access = EquipmentLibrary.Access.ALL;
	/** The item whose detail card is open (its primaryId), or -1. */
	private int selected = -1;
	/** 0-based page into the filtered result. */
	private int page;
	/** Four across; a page is fifteen rows so a big slot still pages sanely. */
	private static final int COLUMNS = 4;
	private static final int PAGE_ROWS = 15;
	private boolean chartExpanded;
	/** The two foldings COMPOSE (Luke, 2026-07-28): sets group the grid,
	 *  variants fold an expanded set's members (or the grid when sets off). */
	private boolean groupVariants;
	private boolean groupSets;
	/** Hide Leagues / Deadman rewards ("Show seasonal" unchecked). */
	private boolean hideLeagues;
	/** The one group whose members are expanded (only one at a time, Luke). */
	private String expandedGroup;
	/** With both modes on: the one variant group open INSIDE the open set. */
	private String expandedMemberGroup;
	private List<Object> lastPrint = List.of();

	GearLibraryTab(AccountState state, EquipmentPack pack, ItemSourcesPack itemSources,
		ItemManager itemManager, net.runelite.client.callback.ClientThread clientThread,
		OsrsTheme theme, GearTab chart)
	{
		this.state = state;
		this.pack = pack;
		this.itemSources = itemSources;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.theme = theme;
		this.chart = chart;
		this.sprites = new SpriteCache(itemManager, listener);
		this.library = new EquipmentLibrary(pack, this::owns, this::marketValue);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		search = new V2TextField(theme, "Search all gear…", null);
		add(search);
		search.editor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildList();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildList();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildList();
			}
		});
		add(Box.createVerticalStrut(4));

		slotBox = new V2Dropdown(theme, SLOT_LABELS);
		slotBox.onChange(i -> rebuildList());
		sortBox = new V2Dropdown(theme, sortLabels());
		sortBox.onChange(i ->
		{
			EquipmentLibrary.Sort chosen = EquipmentLibrary.Sort.values()[i];
			if (chosen != sort)
			{
				sort = chosen;
				ascending = !sort.descendingByDefault;
				refreshDirection();
			}
			rebuildList();
		});
		sortDirection = new JLabel();
		sortDirection.setForeground(OsrsSkin.TITLE);
		OsrsSkin.crisp(sortDirection);
		sortDirection.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		sortDirection.setToolTipText("Flip the sort direction");
		sortDirection.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				ascending = !ascending;
				refreshDirection();
				rebuildList();
			}
		});
		refreshDirection();
		add(controlsRow());
		add(Box.createVerticalStrut(4));

		ownedChips = new V2ChipRow(theme, true, "All", "Owned", "Missing");
		ownedChips.onChange(i ->
		{
			owned = EquipmentLibrary.Owned.values()[i];
			rebuildList();
		});
		add(ownedChips);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		accessChips = new V2ChipRow(theme, true, "Any", "Members", "Free");
		accessChips.onChange(i ->
		{
			access = EquipmentLibrary.Access.values()[i];
			rebuildList();
		});
		add(accessChips);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(toggleRow(
			toggle("Group variants", () -> groupVariants, on ->
			{
				groupVariants = on;
				clearExpansion();
				rebuildList();
			}),
			toggle("Show sets", () -> groupSets, on ->
			{
				groupSets = on;
				clearExpansion();
				rebuildList();
			})));
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(toggleRow(
			// checked = seasonal items included (Luke, 2026-07-28 rename —
			// the old "Hide Leagues / DMM" read backwards)
			toggle("Show seasonal", () -> !hideLeagues, on ->
			{
				hideLeagues = !on;
				rebuildList();
			})));
		add(Box.createVerticalStrut(6));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);

		chartTriangle = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		chartTriangle.setForeground(OsrsSkin.MUTED);
		add(Box.createVerticalStrut(6));
		add(chartHeader());
		chartSlot.setLayout(new BoxLayout(chartSlot, BoxLayout.Y_AXIS));
		chartSlot.setOpaque(false);
		chartSlot.setAlignmentX(LEFT_ALIGNMENT);
		add(chartSlot);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		requestPrices();
		rebuildList();
	}

	/**
	 * Fill the price cache once, on the client thread, then rebuild — so the
	 * EDT never calls getItemPrice (which needs the client thread). Prices
	 * change slowly; one sweep per tab lifetime is enough.
	 */
	private void requestPrices()
	{
		if (pricesRequested || itemManager == null || clientThread == null)
		{
			return;
		}
		pricesRequested = true;
		clientThread.invokeLater(() ->
		{
			java.util.Map<Integer, Integer> prices = new java.util.HashMap<>();
			for (EquipmentPack.Item item : pack.items)
			{
				int price = itemManager.getItemPrice(item.primaryId());
				if (price > 0)
				{
					prices.put(item.primaryId(), price);
				}
			}
			priceCache = prices;
			javax.swing.SwingUtilities.invokeLater(this::rebuildGrid);
		});
	}

	void dispose()
	{
		state.removeListener(listener);
		chart.dispose();
	}

	private static String[] sortLabels()
	{
		EquipmentLibrary.Sort[] values = EquipmentLibrary.Sort.values();
		String[] labels = new String[values.length];
		for (int i = 0; i < values.length; i++)
		{
			labels[i] = values[i].label;
		}
		return labels;
	}

	/** Slot dropdown, sort dropdown and the direction glyph in one row. */
	private JComponent controlsRow()
	{
		// the row FOLLOWS its dropdowns: they grow in place when opened, and a
		// row pinned to one control height would clip the open list
		JPanel row = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		// width only — the height is the dropdown's own
		slotBox.width(90);
		sortBox.width(92);
		row.add(slotBox);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(sortBox);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(sortDirection);
		row.add(Box.createHorizontalGlue());
		return row;
	}

	private void refreshDirection()
	{
		sortDirection.setIcon(new PaintedIcon(ascending
			? PaintedIcon.Shape.TRIANGLE_UP : PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		sortDirection.getParent();
	}

	private void clearExpansion()
	{
		expandedGroup = null;
		expandedMemberGroup = null;
	}

	/** The mode checkboxes, so a mode change can untick its rival. */
	private final java.util.Map<String, V2Checkbox> toggles = new java.util.HashMap<>();

	/** A labelled checkbox bound to a boolean getter/setter. The atom fires
	 *  the toggle and the CALLER flips its state (the V2Checkbox contract —
	 *  missing the flip was why no tick ever showed; Luke, 2026-07-28). */
	private JComponent toggle(String text, java.util.function.BooleanSupplier get,
		java.util.function.Consumer<Boolean> set)
	{
		// the checkbox ATOM carries its own box, label, hover and hit target
		JPanel unit = new JPanel();
		unit.setLayout(new BoxLayout(unit, BoxLayout.X_AXIS));
		unit.setOpaque(false);
		V2Checkbox box = new V2Checkbox(theme, text, get.getAsBoolean(), () ->
		{
			boolean on = !get.getAsBoolean();
			set.accept(on);
			syncToggles();
		});
		toggles.put(text, box);
		unit.add(box);
		return unit;
	}

	/** Every mode checkbox re-reads its getter, so seams and clicks agree. */
	private void syncToggles()
	{
		toggles.forEach((text, box) ->
		{
			boolean on = "Group variants".equals(text) ? groupVariants
				: "Show sets".equals(text) ? groupSets
				: !hideLeagues;
			box.state(on ? V2Checkbox.State.ON : V2Checkbox.State.OFF);
		});
	}

	private JComponent toggleRow(JComponent... controls)
	{
		JPanel row = row();
		for (int i = 0; i < controls.length; i++)
		{
			if (i > 0)
			{
				row.add(Box.createHorizontalStrut(UiTokens.PAD));
			}
			row.add(controls[i]);
		}
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/**
	 * EXACT ownership — any of the item's OWN ids in a readable container.
	 * Not canonicalStock: that sums the whole ItemVariationMapping group, so
	 * a plain Rune platebody made every clue-reward recolour read as owned
	 * (Luke's report). Each recolour is its own equipment entry with its own
	 * ids, so exact per-id counting keeps them distinct.
	 */
	private boolean owns(EquipmentPack.Item item)
	{
		for (int id : item.ids)
		{
			// ownedAnywhere counts the "Where's my stuff" storages too, so an
			// item that only sits in a POH costume storage / STASH / boat reads
			// as owned rather than "Obtained".
			if (state.ownedAnywhere(id))
			{
				return true;
			}
		}
		return false;
	}

	/** Where the item was last seen ("Bank"/"Inventory"/"Worn", or a tracked
	 *  storage label like "Fancy dress box (PoH)"), or null. */
	private String ownedLocation(EquipmentPack.Item item)
	{
		for (int id : item.ids)
		{
			String where = state.whereOwned(id);
			if (where != null)
			{
				return where;
			}
		}
		return null;
	}

	// ── state ─────────────────────────────────────────────────────────

	private void onStateChanged()
	{
		List<Object> print = fingerprint();
		if (!print.equals(lastPrint))
		{
			rebuildList();
		}
	}

	private List<Object> fingerprint()
	{
		List<Object> print = new ArrayList<>();
		// only re-render when something the library reads has moved: what the
		// player owns, which items are tracked, the page and the selection
		print.add(trackedGear());
		for (Unit unit : pageUnits())
		{
			for (EquipmentPack.Item item : unit.items)
			{
				print.add(item.primaryId());
				print.add(owns(item));
			}
		}
		print.add(selected);
		print.add(page);
		print.add(groupVariants);
		print.add(groupSets);
		print.add(hideLeagues);
		print.add(expandedGroup);
		print.add(expandedMemberGroup);
		print.add(owned);
		print.add(chartExpanded);
		return print;
	}

	private Set<String> trackedGear()
	{
		Set<String> ids = new java.util.HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("gear:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	// ── the grid ──────────────────────────────────────────────────────

	/** A rendered position: a single item, a variant group, or a SET. */
	private static final class Unit
	{
		final String base;
		final List<EquipmentPack.Item> items;
		/** True only for a curated armour set — the double tile. */
		final boolean set;

		Unit(String base, List<EquipmentPack.Item> items)
		{
			this(base, items, false);
		}

		Unit(String base, List<EquipmentPack.Item> items, boolean set)
		{
			this.base = base;
			this.items = items;
			this.set = set;
		}

		EquipmentPack.Item lead()
		{
			return items.get(0);
		}

		boolean isGroup()
		{
			return items.size() > 1;
		}
	}

	private List<EquipmentPack.Item> visible()
	{
		return library.query(search.getText(), slotKey(), owned, access, sort, ascending,
			hideLeagues);
	}

	/** The filtered result as units — one per item, one per variant group
	 *  (base name = name minus trailing parentheticals), or one per armour
	 *  set (set name = name minus its piece-type word). Sets win the grid;
	 *  variants then fold inside an expanded set ({@link #memberUnits}). */
	private List<Unit> visibleUnits()
	{
		List<EquipmentPack.Item> items = visible();
		if (!groupSets && !groupVariants)
		{
			List<Unit> units = new ArrayList<>(items.size());
			for (EquipmentPack.Item item : items)
			{
				units.add(new Unit(item.name, List.of(item)));
			}
			return units;
		}
		// sets mode keys through the curated catalogue; anything outside a
		// set falls back to variant folding (when on) or stands alone
		java.util.function.Function<String, String> key = !groupSets
			? GearLibraryTab::baseName
			: name ->
			{
				String set = curatedSet(name);
				return set != null ? set : groupVariants ? baseName(name) : name;
			};
		return fold(items, key);
	}

	private static List<Unit> fold(List<EquipmentPack.Item> items,
		java.util.function.Function<String, String> key)
	{
		java.util.LinkedHashMap<String, List<EquipmentPack.Item>> groups =
			new java.util.LinkedHashMap<>();
		for (EquipmentPack.Item item : items)
		{
			groups.computeIfAbsent(key.apply(item.name), k -> new ArrayList<>()).add(item);
		}
		List<Unit> units = new ArrayList<>(groups.size());
		groups.forEach((base, list) ->
		{
			// the base variant leads its group (Luke: "Rune scimitar" before
			// "Rune scimitar (saradomin)") — the shortest name is the base,
			// ties keep the sort order
			list.sort(java.util.Comparator.comparingInt((EquipmentPack.Item i) -> i.name.length()));
			// a unit is a SET exactly when its key came from the matcher —
			// dynamic modifier sets included
			units.add(new Unit(base, list, base.equals(curatedSet(list.get(0).name))));
		});
		return units;
	}

	/** An expanded set's members: variant groups when both modes are on,
	 *  else one unit per piece. */
	private List<Unit> memberUnits(Unit group)
	{
		if (groupSets && groupVariants)
		{
			return fold(group.items, GearLibraryTab::baseName);
		}
		List<Unit> units = new ArrayList<>(group.items.size());
		for (EquipmentPack.Item item : group.items)
		{
			units.add(new Unit(item.name, List.of(item)));
		}
		return units;
	}

	/** A name with its trailing "(...)" variant markers stripped. */
	static String baseName(String name)
	{
		String base = name.replaceAll("(\\s*\\([^)]*\\))+$", "").trim();
		return base.isEmpty() ? name : base;
	}

	/**
	 * Luke's curated armour sets (2026-07-28) — the ONLY names that group
	 * as sets. The old piece-word heuristic invented pseudo-sets ("Rune
	 * heraldic") and promoted variant pairs to set tiles; now an item joins
	 * a set only when its base name is a curated set name plus a recognised
	 * piece word, with explicit rules for the families whose piece names
	 * don't carry the set's name (Barrows brothers, god d'hides and
	 * vestments, 3rd age tools/melee, "… of darkness", Elder chaos).
	 */
	private static final String[] SET_NAMES = {
		"3rd age tools", "3rd age druidic", "3rd age melee", "Bronze", "Iron", "Steel",
		"Black", "Mithril", "Adamant", "Rune", "Dragon", "White", "Initiate", "Shayzien",
		"Samurai", "Proselyte", "Inquisitor's", "Rock-shell", "Void Knight", "Granite",
		"Blood moon", "Obsidian", "Barrows", "Justiciar", "Oathplate", "Torva", "Yak-hide",
		"Fighter", "Leather", "Frog-leather", "Snakeskin", "Ranger", "Green d'hide",
		"Spined", "Blue d'hide", "Red d'hide", "Black d'hide", "Mixed hide",
		"Blessed d'hide", "Hueycoatl hide", "Crystal", "Armadyl", "Eclipse moon", "Masori",
		"Zamorak monk", "Wizard", "Ghostly", "Dark Squall", "Elder chaos druid", "Xerician",
		"Mystic", "Enchanted", "Robes of darkness", "Skeletal", "Splitbark", "Swampbark",
		"Infinity", "Bloodbark", "Lunar", "Dagon'hai", "Blue moon", "Ancestral", "Virtus",
		"Priest", "Monk's", "Shade", "Druid's", "Ancient ceremonial", "Elite black",
		"Vestment", "Sunfire fanatic"};

	/** Lower-cased set names, longest first, so "Elite black" and
	 *  "Black d'hide" win over "Black". */
	private static final List<String> SETS_LOWER = buildSetsLower();
	private static final java.util.Map<String, String> SET_DISPLAY = buildSetDisplay();

	private static List<String> buildSetsLower()
	{
		List<String> lower = new ArrayList<>();
		for (String set : SET_NAMES)
		{
			lower.add(set.toLowerCase(Locale.ROOT));
		}
		lower.sort(java.util.Comparator.comparingInt(String::length).reversed());
		return lower;
	}

	private static java.util.Map<String, String> buildSetDisplay()
	{
		java.util.Map<String, String> map = new java.util.HashMap<>();
		for (String set : SET_NAMES)
		{
			map.put(set.toLowerCase(Locale.ROOT), set);
		}
		return map;
	}

	private static final String[] BARROWS_BROTHERS = {
		"ahrim's", "dharok's", "guthan's", "karil's", "torag's", "verac's"};
	private static final String[] GOD_PREFIXES = {
		"saradomin", "guthix", "zamorak", "armadyl", "bandos", "ancient"};
	private static final Set<String> VESTMENT_PIECES = Set.of(
		"mitre", "stole", "crozier", "robe top", "robe legs", "cloak");
	private static final Set<String> THIRD_AGE_TOOLS = Set.of(
		"axe", "pickaxe", "harpoon", "felling axe");
	private static final Set<String> THIRD_AGE_MELEE = Set.of(
		"full helmet", "platebody", "platelegs", "kiteshield");

	/**
	 * The curated set an item belongs to, or null for a non-set item.
	 * Weapons and ammo join their tier's set too (Luke, 2026-07-28), and a
	 * one-word modifier line over a curated set — "Echo virtus mask",
	 * "Twisted ancestral hat", "Radiant oathplate chest", "Dark infinity
	 * top" — is its OWN set, named "<Modifier> <Set>".
	 */
	static String curatedSet(String name)
	{
		String base = baseName(name).replace('\u2019', '\'');
		String lower = base.toLowerCase(Locale.ROOT);
		for (String brother : BARROWS_BROTHERS)
		{
			if (lower.startsWith(brother + " "))
			{
				return "Barrows";
			}
		}
		for (String god : GOD_PREFIXES)
		{
			if (lower.startsWith(god + " "))
			{
				String rest = lower.substring(god.length() + 1);
				if (rest.startsWith("d'hide") || rest.equals("coif") || rest.equals("bracers"))
				{
					return "Blessed d'hide";
				}
				if (VESTMENT_PIECES.contains(rest))
				{
					return "Vestment";
				}
			}
		}
		if (lower.startsWith("3rd age "))
		{
			String rest = lower.substring("3rd age ".length());
			if (THIRD_AGE_TOOLS.contains(rest))
			{
				return "3rd age tools";
			}
			if (THIRD_AGE_MELEE.contains(rest))
			{
				return "3rd age melee";
			}
		}
		if (lower.endsWith(" of darkness"))
		{
			return "Robes of darkness";
		}
		if (lower.startsWith("elder chaos "))
		{
			return "Elder chaos druid";
		}
		for (String set : SETS_LOWER)
		{
			if (lower.startsWith(set + " "))
			{
				return SET_DISPLAY.get(set);
			}
		}
		// a modifier line: the set name starts at the SECOND word
		int space = lower.indexOf(' ');
		if (space > 0)
		{
			String rest = lower.substring(space + 1);
			for (String set : SETS_LOWER)
			{
				if (rest.startsWith(set + " ") || rest.equals(set))
				{
					return base.substring(0, space) + " " + SET_DISPLAY.get(set);
				}
			}
		}
		return null;
	}

	private int pageSize()
	{
		return COLUMNS * PAGE_ROWS;
	}

	private List<Unit> pageUnits()
	{
		List<Unit> all = visibleUnits();
		int size = pageSize();
		int from = Math.min(page * size, all.size());
		int to = Math.min(from + size, all.size());
		return all.subList(from, to);
	}

	private String slotKey()
	{
		int index = slotBox.selected();
		return index >= 0 && index < SLOT_KEYS.length ? SLOT_KEYS[index] : null;
	}

	/** Any control change clears the selection and returns to page one. */
	private void rebuildList()
	{
		page = 0;
		selected = -1;
		rebuildGrid();
	}

	private void rebuildGrid()
	{
		lastPrint = fingerprint();
		list.removeAll();
		List<Unit> allUnits = visibleUnits();
		int totalItems = visible().size();
		int cols = COLUMNS;
		int pages = Math.max(1, (allUnits.size() + pageSize() - 1) / pageSize());
		if (page >= pages)
		{
			page = pages - 1;
		}

		JPanel summary = row();
		summary.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));
		summary.add(new OsrsLabel(totalItems + (totalItems == 1 ? " item" : " items"),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		summary.add(Box.createHorizontalGlue());
		cap(summary);
		list.add(summary);

		if (allUnits.isEmpty())
		{
			list.add(note("Nothing matches. Widen the filters or clear the search."));
			list.revalidate();
			list.repaint();
			return;
		}

		renderUnits(pageUnits());

		if (pages > 1)
		{
			list.add(pager(pages));
		}
		list.revalidate();
		list.repaint();
	}

	/**
	 * The grid, packed. Small tiles flow four across; a SET group is a
	 * double tile taking four tiles' space, the smalls that follow it in
	 * sort order wrapping beside it in a 2x2 block (Luke, 2026-07-28).
	 */
	private void renderUnits(List<Unit> units)
	{
		// two queues in sort order: every set PULLS the next four smalls
		// forward to fill its band completely — sets pair up only when the
		// smalls have run out (Luke, 2026-07-28: no half-empty bands)
		java.util.ArrayDeque<Unit> bigs = new java.util.ArrayDeque<>();
		java.util.ArrayDeque<Unit> smalls = new java.util.ArrayDeque<>();
		for (Unit unit : units)
		{
			(unit.set && unit.isGroup() ? bigs : smalls).add(unit);
		}
		while (!bigs.isEmpty())
		{
			Unit big = bigs.poll();
			if (smalls.size() >= 4 || (bigs.isEmpty() && !smalls.isEmpty()))
			{
				List<Unit> wrap = new ArrayList<>();
				while (wrap.size() < 4 && !smalls.isEmpty())
				{
					wrap.add(smalls.poll());
				}
				addBandRow(List.of(big), wrap);
				List<Unit> shown = new ArrayList<>();
				shown.add(big);
				shown.addAll(wrap);
				addExpansions(shown);
			}
			else if (!bigs.isEmpty())
			{
				Unit pair = bigs.poll();
				addBandRow(List.of(big, pair), List.of());
				addExpansions(List.of(big, pair));
			}
			else
			{
				addBandRow(List.of(big), List.of());
				addExpansions(List.of(big));
			}
		}
		flushSmallRows(new ArrayList<>(smalls));
	}

	/** Pending small units as centred 4-wide rows, expansions after each. */
	private void flushSmallRows(List<Unit> pending)
	{
		for (int start = 0; start < pending.size(); start += COLUMNS)
		{
			List<Unit> rowUnits = pending.subList(start,
				Math.min(start + COLUMNS, pending.size()));
			JPanel gridRow = row();
			// glue BOTH sides — rows centre in the column (the reference grammar)
			gridRow.add(Box.createHorizontalGlue());
			for (int col = 0; col < rowUnits.size(); col++)
			{
				if (col > 0)
				{
					gridRow.add(Box.createHorizontalStrut(3));
				}
				gridRow.add(unitTile(rowUnits.get(col)));
			}
			gridRow.add(Box.createHorizontalGlue());
			cap(gridRow);
			list.add(gridRow);
			list.add(Box.createVerticalStrut(3));
			addExpansions(rowUnits);
		}
		pending.clear();
	}

	/** One band: the big set tile(s) with up to four smalls stacked 2x2
	 *  beside them. */
	private void addBandRow(List<Unit> bigs, List<Unit> wrap)
	{
		JPanel bandRow = row();
		bandRow.add(Box.createHorizontalGlue());
		for (int b = 0; b < bigs.size(); b++)
		{
			if (b > 0)
			{
				bandRow.add(Box.createHorizontalStrut(3));
			}
			JComponent tile = unitTile(bigs.get(b));
			tile.setAlignmentY(TOP_ALIGNMENT);
			bandRow.add(tile);
		}
		if (!wrap.isEmpty())
		{
			bandRow.add(Box.createHorizontalStrut(3));
			JPanel stack = new JPanel();
			stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
			stack.setOpaque(false);
			stack.setAlignmentY(TOP_ALIGNMENT);
			for (int start = 0; start < wrap.size(); start += 2)
			{
				if (start > 0)
				{
					stack.add(Box.createVerticalStrut(3));
				}
				JPanel mini = row();
				for (int col = 0; col < 2 && start + col < wrap.size(); col++)
				{
					if (col > 0)
					{
						mini.add(Box.createHorizontalStrut(3));
					}
					mini.add(unitTile(wrap.get(start + col)));
				}
				mini.add(Box.createHorizontalGlue());
				cap(mini);
				stack.add(mini);
			}
			stack.setMaximumSize(stack.getPreferredSize());
			bandRow.add(stack);
		}
		bandRow.add(Box.createHorizontalGlue());
		cap(bandRow);
		list.add(bandRow);
		list.add(Box.createVerticalStrut(3));
	}

	/** A shown unit's expansions: the selected singleton's detail card, or
	 *  the one expanded group's members. */
	private void addExpansions(List<Unit> shown)
	{
		for (Unit unit : shown)
		{
			if (!unit.isGroup() && unit.lead().primaryId() == selected)
			{
				list.add(detailCard(unit.lead(), owns(unit.lead())));
				list.add(Box.createVerticalStrut(3));
			}
			else if (unit.isGroup() && unit.base.equals(expandedGroup))
			{
				addMemberBlock(unit);
			}
		}
	}

	/** An expanded set's members (4-wide, indented): variant groups when
	 *  both modes are on — one openable at a time — else the pieces
	 *  themselves, with the selected member's detail card under its row. */
	private void addMemberBlock(Unit group)
	{
		List<Unit> members = memberUnits(group);
		for (int start = 0; start < members.size(); start += COLUMNS)
		{
			List<Unit> rowUnits = members.subList(start,
				Math.min(start + COLUMNS, members.size()));
			JPanel gridRow = row();
			gridRow.setBorder(new EmptyBorder(0, 8, 0, 0)); // indent members
			for (int col = 0; col < rowUnits.size(); col++)
			{
				if (col > 0)
				{
					gridRow.add(Box.createHorizontalStrut(3));
				}
				gridRow.add(memberTile(rowUnits.get(col)));
			}
			gridRow.add(Box.createHorizontalGlue());
			cap(gridRow);
			list.add(gridRow);
			list.add(Box.createVerticalStrut(3));
			for (Unit member : rowUnits)
			{
				if (!member.isGroup() && member.lead().primaryId() == selected)
				{
					list.add(detailCard(member.lead(), owns(member.lead())));
					list.add(Box.createVerticalStrut(3));
				}
				else if (member.isGroup() && member.base.equals(expandedMemberGroup))
				{
					addVariantItems(member);
				}
			}
		}
	}

	/** A member unit's tile: a piece, or a variant group inside the set. */
	private JComponent memberTile(Unit member)
	{
		if (!member.isGroup())
		{
			return itemTile(member.lead());
		}
		boolean open = member.base.equals(expandedMemberGroup);
		java.awt.Image sprite = sprites.get(member.lead().primaryId(), -1, 28);
		boolean ownsAny = showTick() && member.items.stream().anyMatch(this::owns);
		V2Tile tile = new V2Tile(theme, sprite, member.base, TILE_ART, () ->
		{
			expandedMemberGroup = open ? null : member.base;
			selected = -1;
			rebuildGrid();
		}).width(TILE_WIDTH).captionLines(2)
			.owned(ownsAny).selected(open).badge(member.items.size());
		tile.setToolTipText(member.base + " — " + member.items.size() + " variants");
		return tile;
	}

	/** The open variant group's items, indented one step further. */
	private void addVariantItems(Unit member)
	{
		for (int start = 0; start < member.items.size(); start += COLUMNS)
		{
			List<EquipmentPack.Item> rowItems = member.items.subList(start,
				Math.min(start + COLUMNS, member.items.size()));
			JPanel gridRow = row();
			gridRow.setBorder(new EmptyBorder(0, 16, 0, 0));
			for (int col = 0; col < rowItems.size(); col++)
			{
				if (col > 0)
				{
					gridRow.add(Box.createHorizontalStrut(3));
				}
				gridRow.add(itemTile(rowItems.get(col)));
			}
			gridRow.add(Box.createHorizontalGlue());
			cap(gridRow);
			list.add(gridRow);
			list.add(Box.createVerticalStrut(3));
			for (EquipmentPack.Item item : rowItems)
			{
				if (item.primaryId() == selected)
				{
					list.add(detailCard(item, owns(item)));
					list.add(Box.createVerticalStrut(3));
				}
			}
		}
	}

	/** A unit's tile: a single item, or a group tile (variant count badge,
	 *  larger when a set) whose click expands its members — one at a time. */
	private V2Tile unitTile(Unit unit)
	{
		if (!unit.isGroup())
		{
			return itemTile(unit.lead());
		}
		EquipmentPack.Item lead = unit.lead();
		// a curated SET is a double tile — four tiles' worth of grid (Luke,
		// 2026-07-28); a variant group stays a regular tile
		boolean big = unit.set;
		java.awt.Image sprite = sprites.get(lead.primaryId(), -1, big ? 40 : 28);
		boolean ownsAny = showTick() && unit.items.stream().anyMatch(this::owns);
		boolean expanded = unit.base.equals(expandedGroup);
		String noun = unit.set ? " pieces" : " variants";
		V2Tile tile = new V2Tile(theme, sprite, unit.base, big ? TILE_ART_BIG : TILE_ART, () ->
		{
			expandedGroup = expanded ? null : unit.base;
			expandedMemberGroup = null;
			selected = -1;
			rebuildGrid();
		}).width(big ? TILE_WIDTH_BIG : TILE_WIDTH).captionLines(2)
			.owned(ownsAny).selected(expanded).badge(unit.items.size());
		tile.setToolTipText(unit.base + " — " + unit.items.size() + noun);
		return tile;
	}

	private V2Tile itemTile(EquipmentPack.Item item)
	{
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 28);
		V2Tile tile = new V2Tile(theme, sprite, item.name, TILE_ART, () ->
		{
			selected = item.primaryId() == selected ? -1 : item.primaryId();
			rebuildGrid();
		}).width(TILE_WIDTH).captionLines(2)
			.owned(showTick() && owns(item)).selected(item.primaryId() == selected);
		// tracked is the READY status edge — V1 painted it as an orange bevel
		tile.status(isTracked(item) ? V2Tile.Status.READY : V2Tile.Status.PLAIN);
		tile.onRightClick(e -> rowMenu(item, e));
		tile.setToolTipText(tileTooltip(item));
		return tile;
	}

	/** The grid's tile geometry — the art band, the caption sits under it. */
	private static final int TILE_ART = 34;
	private static final int TILE_WIDTH = 52;
	/** The double set tile: two columns wide, and EXACTLY two small tiles
	 *  tall — its art band absorbs the second row's caption + gutter, since
	 *  the big tile carries only one caption (Luke, 2026-07-28). */
	private static final int TILE_WIDTH_BIG = 2 * TILE_WIDTH + 3;
	private static final int TILE_ART_BIG = TILE_ART
		+ (TILE_ART + V2Tokens.TIGHT + 2 * V2Tokens.LINE_PITCH + V2Tokens.ROW) + 3;

	/** The owned tick shows only in the "All" view — it is redundant when the
	 *  Owned filter already means every tile is owned (Luke). */
	private boolean showTick()
	{
		return owned == EquipmentLibrary.Owned.ALL;
	}

	/** Slot · value · the active metric — the tile shows only the sprite and
	 *  name, so its numbers ride in the tooltip. */
	private String tileTooltip(EquipmentPack.Item item)
	{
		StringBuilder tip = new StringBuilder(item.name);
		tip.append(" — ").append(slotName(item.slot));
		long value = marketValue(item);
		if (value > 0)
		{
			tip.append(" · ").append(Format.gp(value));
		}
		if (sort != EquipmentLibrary.Sort.NAME && sort != EquipmentLibrary.Sort.VALUE)
		{
			tip.append(" · ").append(sort.label).append(' ').append(metricText(item));
		}
		if (owns(item))
		{
			tip.append(" · owned");
		}
		return tip.toString();
	}

	/** The Quests pager exactly (Luke, 2026-07-28): centred ui/arrows
	 *  around "Page x/y". */
	private JComponent pager(int pages)
	{
		JPanel row = row();
		row.add(Box.createHorizontalGlue());
		row.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
			com.ironhub.ui.v2.V2SpriteButton.ARROW_LEFT, () ->
			{
				if (page > 0)
				{
					goToPage(page - 1);
				}
			}));
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel("Page ", OsrsSkin.MUTED, OsrsSkin.smallFont()));
		// the page number is TYPEABLE — Enter jumps; three digits' worth of
		// box, no more (Luke, 2026-07-28; the atom's width(), because its
		// size overrides ignore the setXxxSize setters)
		V2TextField pageField = V2TextField.plain(theme, "", null).width(34);
		pageField.setText(String.valueOf(page + 1));
		pageField.editor().setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		pageField.setToolTipText("Type a page number and press Enter");
		pageField.editor().addActionListener(e ->
		{
			try
			{
				goToPage(Integer.parseInt(pageField.getText().trim()) - 1);
			}
			catch (NumberFormatException ignored)
			{
				pageField.setText(String.valueOf(page + 1));
			}
		});
		row.add(pageField);
		row.add(new OsrsLabel("/" + pages, OsrsSkin.MUTED, OsrsSkin.smallFont()));
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
			com.ironhub.ui.v2.V2SpriteButton.ARROW_RIGHT, () ->
			{
				if (page < pages - 1)
				{
					goToPage(page + 1);
				}
			}));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private void goToPage(int target)
	{
		int pages = Math.max(1, (visibleUnits().size() + pageSize() - 1) / pageSize());
		page = Math.max(0, Math.min(target, pages - 1));
		selected = -1;
		rebuildGrid();
	}

	/** The item's market value: the swept live GE price, else high alch.
	 *  Reads the cache only — never getItemPrice, which needs the client
	 *  thread and would throw on the EDT. */
	private long marketValue(EquipmentPack.Item item)
	{
		int price = priceCache.getOrDefault(item.primaryId(), 0);
		return price > 0 ? price : item.alch;
	}

	/** True when the shown value is a live GE price (vs the alch fallback). */
	private boolean hasLivePrice(EquipmentPack.Item item)
	{
		return priceCache.getOrDefault(item.primaryId(), 0) > 0;
	}

	/** The metric shown for the active sort (tooltip / detail). */
	private String metricText(EquipmentPack.Item item)
	{
		if (sort == EquipmentLibrary.Sort.SPEED)
		{
			return item.speed > 0 ? item.speed + "t" : "";
		}
		if (sort == EquipmentLibrary.Sort.NAME || sort == EquipmentLibrary.Sort.VALUE)
		{
			long value = marketValue(item);
			return value > 0 ? Format.gp(value) : "";
		}
		return signed((int) library.metric(item, sort));
	}

	private static String signed(int bonus)
	{
		return (bonus > 0 ? "+" : "") + bonus;
	}

	// ── the expanded stat card ────────────────────────────────────────

	private JComponent detailCard(EquipmentPack.Item item, boolean own)
	{
		// the opened item is the one live readout on the page — the Card
		V2Surface card = V2Surface.card(theme);

		// the header carries a LARGER sprite on the left (Luke), vertically
		// centred against the name / slot / value block, with the track
		// affordance (and a bank-filler cue when unwieldable) on the right
		JPanel titleLine = row();
		JLabel bigIcon = new JLabel();
		bigIcon.setPreferredSize(new Dimension(40, 36));
		bigIcon.setMinimumSize(new Dimension(40, 36));
		bigIcon.setMaximumSize(new Dimension(40, 36));
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 36);
		if (sprite != null)
		{
			bigIcon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		bigIcon.setBorder(new EmptyBorder(0, 0, 0, UiTokens.ROW_GAP));
		bigIcon.setAlignmentY(CENTER_ALIGNMENT);
		titleLine.add(bigIcon);
		JPanel titleText = new JPanel();
		titleText.setLayout(new BoxLayout(titleText, BoxLayout.Y_AXIS));
		titleText.setOpaque(false);
		titleText.setAlignmentY(CENTER_ALIGNMENT);
		// the name is NOT green for an owned item (Luke) — ownership shows as
		// the tile's corner tick and the status line below
		OsrsLabel title = new OsrsLabel(item.name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		title.setToolTipText(item.name);
		titleText.add(title);
		StringBuilder meta = new StringBuilder(slotName(item.slot));
		meta.append(item.members ? " · members" : " · free-to-play");
		if (item.speed > 0)
		{
			meta.append(" · speed ").append(item.speed);
		}
		titleText.add(new OsrsLabel(meta.toString(), OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned());
		// value: live GE and high alch side by side ("GE 96.9K · HA 21.1K")
		String valueText = valueLine(item);
		if (valueText != null)
		{
			titleText.add(new OsrsLabel(valueText, OsrsSkin.MUTED, OsrsSkin.smallFont())
				.leftAligned());
		}
		titleLine.add(titleText);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		// a bank-filler cue when the player lacks the level to wield it
		List<Requirement> unmet = unmetWieldReqs(item);
		if (!unmet.isEmpty())
		{
			JLabel filler = new JLabel();
			java.awt.Image icon = com.ironhub.ui.osrs.OsrsIcons.image(theme, "bank_filler");
			if (icon != null)
			{
				filler.setIcon(new javax.swing.ImageIcon(icon));
			}
			filler.setAlignmentY(TOP_ALIGNMENT);
			filler.setToolTipText("You can't wield this yet — needs " + unmet.stream()
				.map(Requirement::describe).collect(java.util.stream.Collectors.joining(", ")));
			titleLine.add(filler);
			titleLine.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		JLabel track = trackGlyph(item, own);
		track.setAlignmentY(TOP_ALIGNMENT);
		titleLine.add(track);
		cap(titleLine);
		card.add(titleLine);

		card.add(Box.createVerticalStrut(3));
		addStatBlock(card, item);

		// where it comes from + reqs, straight from the KB projection
		if (!own && itemSources != null)
		{
			String sources = itemSources.sourceLine(item.primaryId(), state,
				state.getItemSourcePref(item.primaryId()));
			if (sources != null)
			{
				card.add(Box.createVerticalStrut(2));
				card.add(OsrsLabel.wrapped(sources, WRAP, OsrsSkin.FAINT, OsrsSkin.smallFont())
					.leftAligned());
			}
		}
		card.add(Box.createVerticalStrut(2));
		card.add(ownershipLine(item, own));
		cap(card);

		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(1, 0, 2, 0));
		holder.add(card, BorderLayout.CENTER);
		cap(holder);
		return holder;
	}

	/** "GE 96.9K · HA 21.1K", either half present; null when nothing is known. */
	private String valueLine(EquipmentPack.Item item)
	{
		List<String> parts = new ArrayList<>();
		if (hasLivePrice(item))
		{
			parts.add("GE " + Format.gp(priceCache.get(item.primaryId())));
		}
		if (item.alch > 0)
		{
			parts.add("HA " + Format.gp(item.alch));
		}
		return parts.isEmpty() ? null : String.join(" · ", parts);
	}

	/**
	 * "You own this · Bank" (green) when it sits in a readable container — bank,
	 * inventory, worn, or any storage the "Where's my stuff" tracker has seen it
	 * in (e.g. "Fancy dress box (PoH)"); "Obtained DD/MM/YY" (orange) when the
	 * collection log recorded it but we do not currently see it anywhere — banks
	 * get cleared, items get sold, so the two are worth telling apart (Luke).
	 */
	private JComponent ownershipLine(EquipmentPack.Item item, boolean own)
	{
		if (own)
		{
			String where = ownedLocation(item);
			String text = where == null ? "You own this" : "You own this · " + where;
			return new OsrsLabel(text, OsrsSkin.VALUE, OsrsSkin.smallFont()).leftAligned();
		}
		long obtainedAt = obtainedDate(item);
		if (obtainedAt > 0)
		{
			return new OsrsLabel("Obtained " + shortDate(obtainedAt), OsrsSkin.TITLE,
				OsrsSkin.smallFont()).leftAligned();
		}
		if (clogObtained(item))
		{
			return new OsrsLabel("Obtained", OsrsSkin.TITLE, OsrsSkin.smallFont()).leftAligned();
		}
		return new OsrsLabel("Not owned", OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned();
	}

	/**
	 * The item's unmet skill requirements to wield/wear it, from the KB (its
	 * equip reqs across all its ids). Empty when it can be equipped or when
	 * no reqs are known.
	 */
	private List<Requirement> unmetWieldReqs(EquipmentPack.Item item)
	{
		if (itemSources == null)
		{
			return List.of();
		}
		List<Requirement> unmet = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		for (int id : item.ids)
		{
			List<String> reqs = itemSources.reqs(id);
			if (reqs == null)
			{
				continue;
			}
			for (String raw : reqs)
			{
				// only a HARD skill gate (skill:) is an EQUIP requirement —
				// skillb: is a boostable action/obtain gate (the whip's
				// spurious "Slayer 85" is skillb:, its real wield gate is
				// skill:Attack:70), and quest/item reqs are about obtaining
				if (raw.startsWith("skill:") && seen.add(raw))
				{
					Requirement req = Requirements.parse(raw);
					if (!req.isMet(state))
					{
						unmet.add(req);
					}
				}
			}
		}
		return unmet;
	}

	/** The collection log's recorded obtained-time for the item, or 0. */
	private long obtainedDate(EquipmentPack.Item item)
	{
		for (int id : item.ids)
		{
			long at = state.clogObtainedAt(id);
			if (at > 0)
			{
				return at;
			}
		}
		return 0;
	}

	/** Whether the collection log has any of the item's ids logged. */
	private boolean clogObtained(EquipmentPack.Item item)
	{
		for (int id : item.ids)
		{
			if (state.getClogObtained().contains(id))
			{
				return true;
			}
		}
		return false;
	}

	private static String shortDate(long epochMs)
	{
		java.time.LocalDate date = java.time.Instant.ofEpochMilli(epochMs)
			.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
		return String.format(Locale.ROOT, "%02d/%02d/%02d",
			date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
	}

	private static final String[][] STAT_GROUPS = {
		{"Attack", "Stab", "Slash", "Crush", "Magic", "Ranged"},
		{"Defence", "Stab", "Slash", "Crush", "Magic", "Ranged"},
		{"Other", "Str", "Ranged str", "Magic dmg", "Prayer"},
	};
	private static final int[][] STAT_INDICES = {
		{EquipmentPack.A_STAB, EquipmentPack.A_SLASH, EquipmentPack.A_CRUSH,
			EquipmentPack.A_MAGIC, EquipmentPack.A_RANGE},
		{EquipmentPack.D_STAB, EquipmentPack.D_SLASH, EquipmentPack.D_CRUSH,
			EquipmentPack.D_MAGIC, EquipmentPack.D_RANGE},
		{EquipmentPack.STR, EquipmentPack.RSTR, EquipmentPack.MDMG, EquipmentPack.PRAYER},
	};

	/** The Equipment-Stats groups, only rows that carry a bonus. */
	private void addStatBlock(V2Surface card, EquipmentPack.Item item)
	{
		boolean any = false;
		for (int g = 0; g < STAT_GROUPS.length; g++)
		{
			List<String> parts = new ArrayList<>();
			for (int s = 1; s < STAT_GROUPS[g].length; s++)
			{
				int bonus = item.stats[STAT_INDICES[g][s - 1]];
				if (bonus != 0)
				{
					parts.add(STAT_GROUPS[g][s] + " " + signed(bonus));
				}
			}
			if (parts.isEmpty())
			{
				continue;
			}
			any = true;
			JPanel line = row();
			line.add(new OsrsLabel(STAT_GROUPS[g][0], OsrsSkin.MUTED, OsrsSkin.smallFont()));
			line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			line.add(OsrsLabel.wrapped(String.join("  ", parts), 130, OsrsSkin.BAR_TEXT,
				OsrsSkin.smallFont()).leftAligned());
			line.add(Box.createHorizontalGlue());
			cap(line);
			card.add(line);
		}
		if (!any)
		{
			card.add(new OsrsLabel("No combat bonuses", OsrsSkin.FAINT, OsrsSkin.smallFont())
				.leftAligned());
		}
	}

	// ── tracking ──────────────────────────────────────────────────────

	private boolean isTracked(EquipmentPack.Item item)
	{
		return state.getSelectedGoals().contains("gear:" + item.primaryId());
	}

	private JLabel trackGlyph(EquipmentPack.Item item, boolean own)
	{
		if (own)
		{
			return new JLabel();
		}
		boolean tracked = isTracked(item);
		JLabel glyph = new JLabel(tracked ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.boldFont());
		glyph.setForeground(tracked ? OsrsSkin.TITLE : OsrsSkin.FAINT);
		glyph.setToolTipText(tracked ? "Stop tracking " + item.name
			: "Track " + item.name + " as a goal");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleTrack(item);
			}
		});
		return glyph;
	}

	private void toggleTrack(EquipmentPack.Item item)
	{
		if (isTracked(item))
		{
			state.removeGoalSeed("gear:" + item.primaryId());
		}
		else
		{
			// an "obtain this equipment" goal — family Gear, named for the item
			// alone, decomposed to its real obtain tasks by the engine
			state.addGoalSeed(GoalSeeds.gear(item.primaryId(), item.name, obtainReqs(item)));
		}
	}

	/**
	 * The item's wield/access requirements from the KB, gathered across ALL
	 * its ids — a chargeable item states them on a different id than the one
	 * that drops (Tumeken's shadow's ToA quest + Magic 85 sit on the charged
	 * 27275, while 27277 is the raw drop).
	 */
	private List<String> obtainReqs(EquipmentPack.Item item)
	{
		if (itemSources == null)
		{
			return List.of();
		}
		LinkedHashSet<String> reqs = new LinkedHashSet<>();
		for (int id : item.ids)
		{
			List<String> ownReqs = itemSources.reqs(id);
			if (ownReqs != null)
			{
				reqs.addAll(ownReqs);
			}
		}
		return new ArrayList<>(reqs);
	}

	private void rowMenu(EquipmentPack.Item item, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page (" + item.name + ")");
		wiki.addActionListener(a -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
			+ item.name.replace(" ", "_").replace("'", "%27")));
		menu.add(wiki);
		if (!owns(item))
		{
			JMenuItem track = new JMenuItem(isTracked(item) ? "Stop tracking" : "Track as goal");
			track.addActionListener(a -> toggleTrack(item));
			menu.add(track);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	// ── the progression chart, folded away below ──────────────────────

	private JComponent chartHeader()
	{
		// a titled block that presses — the Slab (§12)
		V2Surface plate = V2Surface.slab(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		plate.add(chartTriangle);
		plate.add(Box.createHorizontalGlue());
		plate.add(new OsrsLabel("Progression chart", OsrsSkin.TITLE, OsrsSkin.boldFont()));
		plate.add(Box.createHorizontalGlue());
		plate.add(Box.createHorizontalStrut(10));
		plate.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		plate.setToolTipText("The recommended upgrade path, slot by slot");
		plate.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleChart();
			}
		});
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(plate);
		cap(holder);
		return holder;
	}

	private void toggleChart()
	{
		chartExpanded = !chartExpanded;
		chartTriangle.setIcon(new PaintedIcon(chartExpanded
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		chartSlot.removeAll();
		if (chartExpanded)
		{
			chartSlot.add(chart);
		}
		chartSlot.revalidate();
		chartSlot.repaint();
		revalidate();
		repaint();
	}

	// ── test seams ────────────────────────────────────────────────────

	void searchForTest(String term)
	{
		search.setText(term);
		rebuildList();
	}

	void groupVariantsForTest(boolean on)
	{
		groupVariants = on;
		clearExpansion();
		syncToggles();
		rebuildList();
	}

	void showSetsForTest(boolean on)
	{
		groupSets = on;
		clearExpansion();
		syncToggles();
		rebuildList();
	}

	void hideLeaguesForTest(boolean on)
	{
		hideLeagues = on;
		rebuildList();
	}

	void expandGroupForTest(String base)
	{
		expandedGroup = base;
		rebuildGrid();
	}

	void sortForTest(EquipmentLibrary.Sort sort)
	{
		this.sort = sort;
		sortBox.setSelected(sort.ordinal());
		ascending = !sort.descendingByDefault;
		refreshDirection();
		rebuildList();
	}

	void expandForTest(int itemId)
	{
		selected = itemId;
		rebuildGrid();
	}

	void pageForTest(int page)
	{
		this.page = page;
		rebuildGrid();
	}

	void expandChartForTest()
	{
		if (!chartExpanded)
		{
			toggleChart();
		}
	}

	List<EquipmentPack.Item> visibleForTest()
	{
		return visible();
	}

	// ── layout helpers ────────────────────────────────────────────────

	private static String slotName(String slot)
	{
		switch (slot)
		{
			case "2h": return "Two-handed";
			case "ammo": return "Ammunition";
			default: return Character.toUpperCase(slot.charAt(0)) + slot.substring(1);
		}
	}

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent note(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, WRAP, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
