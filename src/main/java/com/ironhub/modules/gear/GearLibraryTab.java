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
	private static final int MAX_ROWS = 50;
	private static final int WRAP = 185;

	private final AccountState state;
	private final EquipmentPack pack;
	private final EquipmentLibrary library;
	private final ItemSourcesPack itemSources;
	private final OsrsTheme theme;
	private final SpriteCache sprites;
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
	private final Set<Integer> expanded = new java.util.HashSet<>();
	private boolean chartExpanded;
	private List<Object> lastPrint = List.of();

	GearLibraryTab(AccountState state, EquipmentPack pack, ItemSourcesPack itemSources,
		ItemManager itemManager, OsrsTheme theme, GearTab chart)
	{
		this.state = state;
		this.pack = pack;
		this.itemSources = itemSources;
		this.theme = theme;
		this.chart = chart;
		this.sprites = new SpriteCache(itemManager, listener);
		this.library = new EquipmentLibrary(pack, this::owns);

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
		rebuildList();
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
		// player owns, and which items are tracked
		print.add(trackedGear());
		for (EquipmentPack.Item item : visible())
		{
			print.add(item.primaryId());
			print.add(owns(item));
		}
		print.add(expanded);
		print.add(chartExpanded);
		return print;
	}

	private Set<String> trackedGear()
	{
		Set<String> ids = new java.util.HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("supply:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	// ── the list ──────────────────────────────────────────────────────

	private List<EquipmentPack.Item> visible()
	{
		return library.query(search.getText(), slotKey(), owned, access, sort, ascending);
	}

	private String slotKey()
	{
		int index = slotBox.getSelectedIndex();
		return index >= 0 && index < SLOT_KEYS.length ? SLOT_KEYS[index] : null;
	}

	private void rebuildList()
	{
		lastPrint = fingerprint();
		list.removeAll();
		List<EquipmentPack.Item> items = visible();

		JPanel summary = row();
		summary.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));
		summary.add(new OsrsLabel(items.size() + (items.size() == 1 ? " item" : " items"),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		summary.add(Box.createHorizontalGlue());
		cap(summary);
		list.add(summary);

		int limit = Math.min(MAX_ROWS, items.size());
		for (int i = 0; i < limit; i++)
		{
			list.add(itemRow(items.get(i)));
		}
		if (items.isEmpty())
		{
			list.add(note("Nothing matches. Widen the filters or clear the search."));
		}
		else if (limit < items.size())
		{
			list.add(note("+ " + (items.size() - limit) + " more — refine your search or filters"));
		}
		list.revalidate();
		list.repaint();
	}

	private JComponent itemRow(EquipmentPack.Item item)
	{
		boolean own = owns(item);
		boolean open = expanded.contains(item.primaryId());
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setAlignmentX(LEFT_ALIGNMENT);
		container.setOpaque(!open);
		if (!open)
		{
			container.setBackground(theme.background);
		}
		container.setBorder(new EmptyBorder(open ? 0 : 1, 0, open ? 0 : 1, 0));

		JPanel head = row();
		head.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		JLabel icon = new JLabel();
		java.awt.Image sprite = sprites.get(item.primaryId(), -1, 16);
		if (sprite != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		head.add(icon);
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel name = new OsrsLabel(item.name,
			own ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText(item.name + (own ? " — owned" : ""));
		head.add(name);
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		String metric = metricText(item);
		if (!metric.isEmpty())
		{
			head.add(new OsrsLabel(metric, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		cap(head);
		container.add(head);

		if (open)
		{
			container.add(detailCard(item, own));
		}

		container.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!open)
				{
					container.setBackground(theme.hoverFill);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!open)
				{
					container.setBackground(theme.background);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					rowMenu(item, e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					if (!expanded.remove(item.primaryId()))
					{
						expanded.add(item.primaryId());
					}
					rebuildList();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					rowMenu(item, e);
				}
			}
		};
		attach(head, click);
		container.addMouseListener(click);
		cap(container);
		return container;
	}

	/** The metric shown on a row's right for the active sort. */
	private String metricText(EquipmentPack.Item item)
	{
		if (sort == EquipmentLibrary.Sort.SPEED)
		{
			return item.speed > 0 ? item.speed + "t" : "";
		}
		if (sort == EquipmentLibrary.Sort.NAME || sort == EquipmentLibrary.Sort.VALUE)
		{
			return item.value() > 0 ? Format.gp(item.value()) : "";
		}
		int bonus = library.metric(item, sort);
		return signed(bonus);
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

		JPanel titleLine = row();
		OsrsLabel title = new OsrsLabel(item.name,
			own ? OsrsSkin.VALUE : OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable();
		title.setToolTipText(item.name);
		titleLine.add(title);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		titleLine.add(trackGlyph(item, own));
		cap(titleLine);
		card.add(titleLine);

		StringBuilder meta = new StringBuilder(slotName(item.slot));
		meta.append(item.members ? " · members" : " · free-to-play");
		if (item.speed > 0)
		{
			meta.append(" · speed ").append(item.speed);
		}
		card.add(new OsrsLabel(meta.toString(), OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());

		// value
		if (item.value() > 0)
		{
			String valueText = item.ge > 0
				? "GE " + Format.gp(item.ge) + (item.alch > 0 ? " · alch " + Format.gp(item.alch) : "")
				: "Alch " + Format.gp(item.alch);
			card.add(new OsrsLabel(valueText, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}

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
		return state.getSelectedGoals().contains("supply:" + item.primaryId());
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
			state.removeGoalSeed("supply:" + item.primaryId());
		}
		else
		{
			// the same one-shot obtain goal the wiki-gear "+" seeds, so the
			// two affordances dedupe on the item id
			state.addGoalSeed(GoalSeeds.supply(item.primaryId(), item.name, 1));
		}
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
		expanded.add(itemId);
		rebuildList();
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

	private static void attach(JComponent container, MouseAdapter click)
	{
		for (java.awt.Component child : container.getComponents())
		{
			// the +/× glyph keeps its own action; everything else forwards
			if (child instanceof JLabel && ((JLabel) child).getIcon() == null
				&& ("+".equals(((JLabel) child).getText()) || "×".equals(((JLabel) child).getText())))
			{
				continue;
			}
			child.addMouseListener(click);
		}
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
