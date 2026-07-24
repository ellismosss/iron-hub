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
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneTextField;
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
	private final StoneTextField search;
	private final JComboBox<String> slotBox;
	private final JComboBox<String> sortBox;
	private final JLabel sortDirection;
	private final StoneChipRow ownedChips;
	private final StoneChipRow accessChips;
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
	/** How the grid folds items: not at all, by variant, or by armour set. */
	private enum GroupMode { NONE, VARIANTS, SETS }
	private GroupMode groupMode = GroupMode.NONE;
	/** Hide Leagues / Deadman rewards. */
	private boolean hideLeagues;
	/** The one group whose members are expanded (only one at a time, Luke). */
	private String expandedGroup;
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

		search = new StoneTextField(theme, "Search all gear…");
		add(search);
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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

		slotBox = StoneComboBoxUI.skin(new JComboBox<>(SLOT_LABELS), theme);
		slotBox.addActionListener(e -> rebuildList());
		sortBox = StoneComboBoxUI.skin(new JComboBox<>(sortLabels()), theme);
		sortBox.addActionListener(e ->
		{
			EquipmentLibrary.Sort chosen = EquipmentLibrary.Sort.values()[sortBox.getSelectedIndex()];
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

		ownedChips = new StoneChipRow(theme, true, "All", "Owned", "Missing");
		ownedChips.onChange(i ->
		{
			owned = EquipmentLibrary.Owned.values()[i];
			rebuildList();
		});
		add(ownedChips);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		accessChips = new StoneChipRow(theme, true, "Any", "Members", "Free");
		accessChips.onChange(i ->
		{
			access = EquipmentLibrary.Access.values()[i];
			rebuildList();
		});
		add(accessChips);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(toggleRow(
			toggle("Group variants", () -> groupMode == GroupMode.VARIANTS,
				on -> setGroupMode(on ? GroupMode.VARIANTS : GroupMode.NONE)),
			toggle("Show sets", () -> groupMode == GroupMode.SETS,
				on -> setGroupMode(on ? GroupMode.SETS : GroupMode.NONE))));
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(toggleRow(
			toggle("Hide Leagues / DMM", () -> hideLeagues, on ->
			{
				hideLeagues = on;
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
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		slotBox.setMaximumSize(new Dimension(90, 22));
		slotBox.setPreferredSize(new Dimension(90, 22));
		sortBox.setMaximumSize(new Dimension(92, 22));
		sortBox.setPreferredSize(new Dimension(92, 22));
		row.add(slotBox);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(sortBox);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(sortDirection);
		row.add(Box.createHorizontalGlue());
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return row;
	}

	private void refreshDirection()
	{
		sortDirection.setIcon(new PaintedIcon(ascending
			? PaintedIcon.Shape.TRIANGLE_UP : PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		sortDirection.getParent();
	}

	/** Grouping is one mode at a time: turning Sets on turns Variants off. */
	private void setGroupMode(GroupMode mode)
	{
		groupMode = mode;
		expandedGroup = null;
		rebuildList();
	}

	/** A labelled checkbox bound to a boolean getter/setter. */
	private JComponent toggle(String text, java.util.function.BooleanSupplier get,
		java.util.function.Consumer<Boolean> set)
	{
		com.ironhub.ui.osrs.StoneCheckbox box =
			new com.ironhub.ui.osrs.StoneCheckbox(theme, get.getAsBoolean());
		OsrsLabel label = new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font());
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				set.accept(!get.getAsBoolean());
			}
		};
		box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		box.addMouseListener(click);
		label.addMouseListener(click);
		JPanel unit = new JPanel();
		unit.setLayout(new BoxLayout(unit, BoxLayout.X_AXIS));
		unit.setOpaque(false);
		unit.add(box);
		unit.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		unit.add(label);
		return unit;
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
			if (state.ownedCount(id) > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** The container the item sits in ("Bank"/"Inventory"/"Worn"), or null. */
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
		print.add(groupMode);
		print.add(hideLeagues);
		print.add(expandedGroup);
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

	/** A rendered position: a single item, or a group of variants (>1). */
	private static final class Unit
	{
		final String base;
		final List<EquipmentPack.Item> items;

		Unit(String base, List<EquipmentPack.Item> items)
		{
			this.base = base;
			this.items = items;
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

	/** The filtered result as units — one per item (NONE), one per variant
	 *  group (VARIANTS, base name = name minus trailing parentheticals), or
	 *  one per armour set (SETS, set name = name minus its piece-type word). */
	private List<Unit> visibleUnits()
	{
		List<EquipmentPack.Item> items = visible();
		if (groupMode == GroupMode.NONE)
		{
			List<Unit> units = new ArrayList<>(items.size());
			for (EquipmentPack.Item item : items)
			{
				units.add(new Unit(item.name, List.of(item)));
			}
			return units;
		}
		java.util.LinkedHashMap<String, List<EquipmentPack.Item>> groups =
			new java.util.LinkedHashMap<>();
		for (EquipmentPack.Item item : items)
		{
			String key = groupMode == GroupMode.SETS ? setKey(item.name) : baseName(item.name);
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
		}
		List<Unit> units = new ArrayList<>(groups.size());
		groups.forEach((key, list) ->
		{
			// the base variant leads its group (Luke: "Rune scimitar" before
			// "Rune scimitar (saradomin)") — the shortest name is the base,
			// ties keep the sort order
			list.sort(java.util.Comparator.comparingInt((EquipmentPack.Item i) -> i.name.length()));
			units.add(new Unit(key, list));
		});
		return units;
	}

	/** A name with its trailing "(...)" variant markers stripped. */
	static String baseName(String name)
	{
		String base = name.replaceAll("(\\s*\\([^)]*\\))+$", "").trim();
		return base.isEmpty() ? name : base;
	}

	/** Piece-type words, longest first, stripped to find an armour set's
	 *  name ("Masori body (f)" → "Masori", "Ancestral robe top" → "Ancestral"). */
	private static final String[] PIECE_TOKENS = {
		"robe top", "robe bottom", "robe legs", "robe skirt", "full helm", "med helm",
		"sq shield", "platebody", "plateskirt", "platelegs", "chainbody", "chainskirt",
		"chestplate", "kiteshield", "robetop", "robeskirt", "gauntlets", "vambraces",
		"tassets", "greaves", "helmet", "gloves", "bracers", "chaps", "boots", "coif",
		"cowl", "hood", "body", "legs", "skirt", "helm", "mask", "hat", "top", "spurs"};

	/** An item's armour-set name: its base name minus a trailing piece word,
	 *  or the whole name for a standalone item (a singleton "set"). */
	static String setKey(String name)
	{
		String base = baseName(name);
		String lower = base.toLowerCase(Locale.ROOT);
		for (String token : PIECE_TOKENS)
		{
			if (lower.endsWith(" " + token))
			{
				String prefix = base.substring(0, base.length() - token.length() - 1).trim();
				return prefix.isEmpty() ? base : prefix;
			}
		}
		return base;
	}

	/** Two columns of large tiles for sets, four small ones otherwise. */
	private int columns()
	{
		return groupMode == GroupMode.SETS ? 2 : COLUMNS;
	}

	private int pageSize()
	{
		return columns() * PAGE_ROWS;
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
		int index = slotBox.getSelectedIndex();
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
		int cols = columns();
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
		if (pages > 1)
		{
			summary.add(new OsrsLabel("page " + (page + 1) + " / " + pages,
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		cap(summary);
		list.add(summary);

		if (allUnits.isEmpty())
		{
			list.add(note("Nothing matches. Widen the filters or clear the search."));
			list.revalidate();
			list.repaint();
			return;
		}

		List<Unit> units = pageUnits();
		for (int start = 0; start < units.size(); start += cols)
		{
			List<Unit> rowUnits = units.subList(start, Math.min(start + cols, units.size()));
			JPanel gridRow = row();
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
			// after the row: a selected singleton's detail card, and the one
			// expanded group's members (each 4-wide, adhering to the filters)
			for (Unit unit : rowUnits)
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

		if (pages > 1)
		{
			list.add(pager(pages));
		}
		list.revalidate();
		list.repaint();
	}

	/** An expanded group's member tiles (4-wide, indented), with the selected
	 *  member's detail card under its sub-row. */
	private void addMemberBlock(Unit group)
	{
		for (int start = 0; start < group.items.size(); start += COLUMNS)
		{
			List<EquipmentPack.Item> rowItems =
				group.items.subList(start, Math.min(start + COLUMNS, group.items.size()));
			JPanel gridRow = row();
			gridRow.setBorder(new EmptyBorder(0, 8, 0, 0)); // indent members
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
			for (EquipmentPack.Item member : rowItems)
			{
				if (member.primaryId() == selected)
				{
					list.add(detailCard(member, owns(member)));
					list.add(Box.createVerticalStrut(3));
				}
			}
		}
	}

	/** A unit's tile: a single item, or a group tile (variant count badge,
	 *  larger when a set) whose click expands its members — one at a time. */
	private GearItemTile unitTile(Unit unit)
	{
		if (!unit.isGroup())
		{
			return itemTile(unit.lead());
		}
		EquipmentPack.Item lead = unit.lead();
		boolean sets = groupMode == GroupMode.SETS;
		java.awt.Image sprite = sprites.get(lead.primaryId(), -1, sets ? 32 : 28);
		boolean ownsAny = showTick() && unit.items.stream().anyMatch(this::owns);
		boolean expanded = unit.base.equals(expandedGroup);
		String noun = sets ? " pieces" : " variants";
		return new GearItemTile(theme, unit.base, sprite, ownsAny, false, expanded,
			unit.items.size(), sets,
			unit.base + " — " + unit.items.size() + noun,
			() ->
			{
				expandedGroup = expanded ? null : unit.base;
				selected = -1;
				rebuildGrid();
			},
			e -> { });
	}

	private GearItemTile itemTile(EquipmentPack.Item item)
	{
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 28);
		return new GearItemTile(theme, item.name, sprite, showTick() && owns(item),
			isTracked(item), item.primaryId() == selected, 1, false, tileTooltip(item),
			() ->
			{
				selected = item.primaryId() == selected ? -1 : item.primaryId();
				rebuildGrid();
			},
			e -> rowMenu(item, e));
	}

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

	private JComponent pager(int pages)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		row.add(pagerButton("< Prev", page > 0, () -> goToPage(page - 1)));
		row.add(Box.createHorizontalGlue());
		// the current page is a tight typeable box (room for two digits) —
		// jump straight to a page
		StoneTextField pageField = new StoneTextField(theme, "");
		pageField.setText(String.valueOf(page + 1));
		Dimension boxSize = new Dimension(22, 18);
		pageField.setMaximumSize(boxSize);
		pageField.setPreferredSize(boxSize);
		pageField.setMinimumSize(boxSize);
		pageField.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		pageField.setToolTipText("Type a page number and press Enter");
		pageField.addActionListener(e ->
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
		row.add(new OsrsLabel(" / " + pages, OsrsSkin.MUTED, OsrsSkin.smallFont()));
		row.add(Box.createHorizontalGlue());
		row.add(pagerButton("Next >", page < pages - 1, () -> goToPage(page + 1)));
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

	private JComponent pagerButton(String text, boolean enabled, Runnable onClick)
	{
		OsrsLabel label = new OsrsLabel(text, enabled ? OsrsSkin.LABEL : OsrsSkin.FAINT,
			OsrsSkin.smallFont());
		if (enabled)
		{
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					onClick.run();
				}
			});
		}
		return label;
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
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

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
	 * "You own this — Bank" (green) when it sits in a readable container;
	 * "Obtained DD/MM/YY" (orange) when the collection log recorded it but we
	 * do not currently see it — banks get cleared, items get sold, so the two
	 * are worth telling apart (Luke). POH costume storage / STASH and the
	 * like aren't readable yet, so an item there reads as obtained, not owned.
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
	private void addStatBlock(StonePanel card, EquipmentPack.Item item)
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
		StonePanel plate = new StonePanel(theme);
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
		setGroupMode(on ? GroupMode.VARIANTS : GroupMode.NONE);
	}

	void showSetsForTest(boolean on)
	{
		setGroupMode(on ? GroupMode.SETS : GroupMode.NONE);
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
		sortBox.setSelectedIndex(sort.ordinal());
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
