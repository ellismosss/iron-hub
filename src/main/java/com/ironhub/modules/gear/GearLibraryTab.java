package com.ironhub.modules.gear;

import com.ironhub.data.EquipmentPack;
import com.ironhub.data.ItemSourcesPack;
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
	private static final int PAGE_SIZE = COLUMNS * 15;
	private boolean chartExpanded;
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

	private boolean owns(EquipmentPack.Item item)
	{
		for (int id : item.ids)
		{
			if (state.canonicalStock(id) > 0)
			{
				return true;
			}
		}
		return false;
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
		for (EquipmentPack.Item item : pageItems())
		{
			print.add(item.primaryId());
			print.add(owns(item));
		}
		print.add(selected);
		print.add(page);
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

	private List<EquipmentPack.Item> visible()
	{
		return library.query(search.getText(), slotKey(), owned, access, sort, ascending);
	}

	/** The current page's slice of the filtered result. */
	private List<EquipmentPack.Item> pageItems()
	{
		List<EquipmentPack.Item> all = visible();
		int from = Math.min(page * PAGE_SIZE, all.size());
		int to = Math.min(from + PAGE_SIZE, all.size());
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
		List<EquipmentPack.Item> all = visible();
		int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		if (page >= pages)
		{
			page = pages - 1;
		}

		JPanel summary = row();
		summary.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));
		summary.add(new OsrsLabel(all.size() + (all.size() == 1 ? " item" : " items"),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		summary.add(Box.createHorizontalGlue());
		if (pages > 1)
		{
			summary.add(new OsrsLabel("page " + (page + 1) + " / " + pages,
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		cap(summary);
		list.add(summary);

		if (all.isEmpty())
		{
			list.add(note("Nothing matches. Widen the filters or clear the search."));
			list.revalidate();
			list.repaint();
			return;
		}

		List<EquipmentPack.Item> items = pageItems();
		for (int start = 0; start < items.size(); start += COLUMNS)
		{
			JPanel gridRow = row();
			boolean selectedInRow = false;
			for (int col = 0; col < COLUMNS && start + col < items.size(); col++)
			{
				EquipmentPack.Item item = items.get(start + col);
				if (col > 0)
				{
					gridRow.add(Box.createHorizontalStrut(3));
				}
				gridRow.add(tile(item));
				selectedInRow |= item.primaryId() == selected;
			}
			gridRow.add(Box.createHorizontalGlue());
			cap(gridRow);
			list.add(gridRow);
			list.add(Box.createVerticalStrut(3));
			// the selected item's detail card spans the full width, right
			// under its row — so context stays put in a grid
			if (selectedInRow)
			{
				EquipmentPack.Item chosen = byId(items, selected);
				if (chosen != null)
				{
					list.add(detailCard(chosen, owns(chosen)));
					list.add(Box.createVerticalStrut(3));
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

	private static EquipmentPack.Item byId(List<EquipmentPack.Item> items, int id)
	{
		for (EquipmentPack.Item item : items)
		{
			if (item.primaryId() == id)
			{
				return item;
			}
		}
		return null;
	}

	private GearItemTile tile(EquipmentPack.Item item)
	{
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 28);
		return new GearItemTile(theme, item.name, sprite, owns(item), isTracked(item),
			item.primaryId() == selected, tileTooltip(item),
			() ->
			{
				selected = item.primaryId() == selected ? -1 : item.primaryId();
				rebuildGrid();
			},
			e -> rowMenu(item, e));
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
		row.add(pagerButton("< Prev", page > 0, () ->
		{
			page--;
			selected = -1;
			rebuildGrid();
		}));
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel((page + 1) + " / " + pages, OsrsSkin.MUTED, OsrsSkin.smallFont()));
		row.add(Box.createHorizontalGlue());
		row.add(pagerButton("Next >", page < pages - 1, () ->
		{
			page++;
			selected = -1;
			rebuildGrid();
		}));
		cap(row);
		return row;
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

		// the header carries a LARGER sprite on the left (Luke), the name and
		// the meta beside it, and the track affordance on the right
		JPanel titleLine = row();
		JLabel bigIcon = new JLabel();
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 32);
		if (sprite != null)
		{
			bigIcon.setIcon(new javax.swing.ImageIcon(sprite));
			bigIcon.setBorder(new EmptyBorder(0, 0, 0, UiTokens.ROW_GAP));
		}
		bigIcon.setVerticalAlignment(javax.swing.SwingConstants.TOP);
		titleLine.add(bigIcon);
		JPanel titleText = new JPanel();
		titleText.setLayout(new BoxLayout(titleText, BoxLayout.Y_AXIS));
		titleText.setOpaque(false);
		titleText.setAlignmentY(TOP_ALIGNMENT);
		OsrsLabel title = new OsrsLabel(item.name,
			own ? OsrsSkin.VALUE : OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable();
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
		long value = marketValue(item);
		if (value > 0)
		{
			String valueText = (hasLivePrice(item) ? "GE " : "Alch ") + Format.gp(value);
			titleText.add(new OsrsLabel(valueText, OsrsSkin.MUTED, OsrsSkin.smallFont())
				.leftAligned());
		}
		titleLine.add(titleText);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		titleLine.add(trackGlyph(item, own));
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
		if (own)
		{
			card.add(Box.createVerticalStrut(2));
			card.add(new OsrsLabel("You own this", OsrsSkin.VALUE, OsrsSkin.smallFont()).leftAligned());
		}
		cap(card);

		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(1, 0, 2, 0));
		holder.add(card, BorderLayout.CENTER);
		cap(holder);
		return holder;
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
