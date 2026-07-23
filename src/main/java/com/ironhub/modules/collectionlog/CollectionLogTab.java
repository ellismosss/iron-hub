package com.ironhub.modules.collectionlog;

import com.ironhub.data.ClogPack;
import com.ironhub.data.ClogRanksPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The collection log, as a place to look at your collection (Luke,
 * 2026-07-24 — it used to be nothing but a ranked to-do list). The shape
 * follows the game's own overview screen, adapted to 225px:
 *
 * <ul>
 * <li>a hero banner framing "Collections Logged: n/N" between the rank you
 *     have reached and the one you are climbing to, each shown by its staff;
 * <li>the log's five tabs as icon tiles with their counts and fill bars;
 * <li>a category view listing that tab's pages, drilling into the page's own
 *     item grid — sprites solid when owned, ghosted when not, exactly as the
 *     interface draws them;
 * <li>and the old Time-To-Next-Slot ranking kept, moved to its own section
 *     at the foot and clamped to ten rows.
 * </ul>
 *
 * <p>Everything above the fold comes from the game's own catalog
 * ({@link ClogCatalog}), so pages, ordering and slot counts are the log's,
 * not a table we maintain.
 */
class CollectionLogTab extends JPanel
{
	/** Luke's clamp on the ranking section. */
	private static final int SUGGESTIONS = 10;
	/** Row ceilings (the Bank tab's grammar). */
	private static final int MAX_PAGES = 60;
	private static final int MAX_SEARCH_ITEMS = 50;
	/** The newest slots the overview shows. */
	private static final int LATEST = 10;
	private static final String[] TAB_ICONS = {"bosses", "raids", "clues", "minigames", "other"};
	/** Marks a child that keeps its own click (the +/x glyphs). */
	private static final String OWN_ACTION = "clog.ownAction";
	private static final int CARD_WRAP = 180;

	private final CollectionLogModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = RebuildGate.install(this, this::onStateChanged);
	private final SpriteCache sprites;
	private final Set<Integer> slayerActivities;

	// persistent chrome
	private final StonePanel hero;
	private final StoneProgressBar bar;
	private final JPanel tabRow = new JPanel();
	private final StoneTextField search;
	private final JPanel content = new JPanel();

	// view state
	private String openTab;   // null = the overview
	private String openPage;  // null = the tab's page list
	private List<Object> lastFingerprint = List.of();

	CollectionLogTab(CollectionLogModule module, AccountState state, ItemManager itemManager,
		OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		this.sprites = new SpriteCache(itemManager, listener);

		// Activities that count as "Slayer" for the ranking (Log Adviser's
		// rule): a Slayer level requirement, minus boat bounty tasks.
		Set<Integer> slayer = new HashSet<>();
		for (ClogPack.Activity a : module.pack().activities)
		{
			if (a.reqs.stream().anyMatch(r -> r.startsWith("skill:Slayer:"))
				&& !a.name.toLowerCase(Locale.ROOT).contains("bounty task"))
			{
				slayer.add(a.index);
			}
		}
		this.slayerActivities = slayer;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		bar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(hero);
		add(Box.createVerticalStrut(4));

		tabRow.setLayout(new BoxLayout(tabRow, BoxLayout.X_AXIS));
		tabRow.setOpaque(false);
		tabRow.setAlignmentX(LEFT_ALIGNMENT);
		add(tabRow);
		add(Box.createVerticalStrut(4));

		search = new StoneTextField(theme, "Search items or pages…");
		add(search);
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}
		});
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuildAll();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void onStateChanged()
	{
		rankingCache = null;
		List<Object> fingerprint = fingerprint();
		if (!fingerprint.equals(lastFingerprint))
		{
			rebuildAll();
		}
	}

	private List<Object> fingerprint()
	{
		List<Object> print = new ArrayList<>();
		print.add(state.getClogObtained().size());
		print.add(state.getClogSkipped());
		print.add(selectedClogGoals());
		print.add(state.getClogBaseline());
		print.add(state.getVarp(VarPlayerID.COLLECTION_COUNT));
		print.add(catalogPrint());
		print.add(openTab);
		print.add(openPage);
		return print;
	}

	/** Cheap identity for the catalog: it only moves on a game update. */
	private String catalogPrint()
	{
		StringBuilder out = new StringBuilder();
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			out.append(tab.name).append(tab.pages.size()).append('/');
		}
		return out.toString();
	}

	private void rebuildAll()
	{
		rebuildHero();
		rebuildTabs();
		rebuildContent();
	}

	// ── the hero banner ───────────────────────────────────────────────

	/**
	 * "Collections Logged: 1,190/1,200" between two staves — the game's own
	 * framing, where the denominator is the NEXT RANK's threshold, not the
	 * log's size, and the bar fills across the band between the two ranks.
	 */
	private void rebuildHero()
	{
		int slots = loggedSlots();
		int total = totalSlots();
		ClogRanksPack ranks = module.ranks();
		ClogRanksPack.Rank reached = ranks == null ? null : ranks.reached(slots, total);
		ClogRanksPack.Rank next = ranks == null ? null : ranks.next(slots, total);
		int floor = reached == null ? 0 : ranks.threshold(reached, total);
		int ceiling = next == null ? total : ranks.threshold(next, total);

		hero.removeAll();
		JPanel top = row();
		top.add(staff(reached));
		// glue BOTH sides: the count stays centred between the two staves
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Collections Logged", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(String.format(Locale.ROOT, "%,d / %,d", slots, ceiling),
			OsrsSkin.VALUE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(staff(next));
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		bar.setFraction(ceiling > floor ? (double) (slots - floor) / (ceiling - floor) : 1);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		hero.add(bar);

		JPanel labels = row();
		labels.add(new OsrsLabel(reached == null ? "Unranked" : ranks.label(reached),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		labels.add(Box.createHorizontalGlue());
		labels.add(new OsrsLabel(next == null ? "Every rank claimed" : ranks.label(next),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(labels);
		hero.add(labels);

		hero.add(Box.createVerticalStrut(2));
		hero.add(syncLine());
		cap(hero);
		hero.revalidate();
		hero.repaint();
	}

	/** A rank's staff sprite, tooltipped with the rank it stands for. */
	private JComponent staff(ClogRanksPack.Rank rank)
	{
		JLabel icon = new JLabel();
		icon.setAlignmentY(TOP_ALIGNMENT);
		if (rank == null)
		{
			icon.setPreferredSize(new Dimension(24, 32));
			icon.setToolTipText("No rank yet — the first staff comes at "
				+ (module.ranks() == null ? "your first milestone"
					: module.ranks().ranks.get(0).slots + " slots"));
			return icon;
		}
		java.awt.Image sprite = sprites.get(rank.itemId, -1, 32);
		if (sprite != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		else
		{
			icon.setPreferredSize(new Dimension(24, 32));
		}
		icon.setToolTipText(module.ranks().label(rank) + " — "
			+ String.format(Locale.ROOT, "%,d", rank.slots) + " slots, claimed from The Collector");
		return icon;
	}

	/** The sync state, in one honest line (unchanged semantics). */
	private JComponent syncLine()
	{
		String text;
		Color colour;
		String tip;
		if (state.getClogBaseline() < 0)
		{
			colour = OsrsSkin.TITLE;
			text = "Open the log and press Log Sync to import";
			tip = "Open your collection log in-game and press the Log Sync button in its "
				+ "header — every obtained slot imports in one click.";
		}
		else if (!module.inSync())
		{
			colour = OsrsSkin.TITLE;
			text = "New slots since last sync · press Log Sync";
			tip = "Your in-game slot count moved past the last full sync — open the "
				+ "collection log and press Log Sync to catch up.";
		}
		else
		{
			colour = OsrsSkin.VALUE;
			long syncedMs = state.getClogSyncedMs();
			text = "Synced" + (syncedMs > 0
				? " · " + Format.relativeTime(System.currentTimeMillis() - syncedMs) : "");
			tip = "Live drops keep the data current between full syncs.";
		}
		OsrsLabel line = OsrsLabel.wrapped(text, CARD_WRAP, colour, OsrsSkin.smallFont());
		line.leftAligned();
		line.setToolTipText(tip);
		return line;
	}

	// ── the five tab tiles ────────────────────────────────────────────

	private void rebuildTabs()
	{
		tabRow.removeAll();
		List<PersistedState.ClogTab> catalog = state.getClogCatalog();
		for (int i = 0; i < catalog.size(); i++)
		{
			PersistedState.ClogTab tab = catalog.get(i);
			Set<Integer> items = tabItems(tab);
			int owned = obtainedIn(items);
			String name = tab.name;
			java.awt.Image icon = OsrsIcons.clogTab(theme,
				TAB_ICONS[Math.min(i, TAB_ICONS.length - 1)]);
			if (i > 0)
			{
				tabRow.add(Box.createHorizontalStrut(3));
			}
			tabRow.add(new ClogTabTile(theme, icon, owned, items.size(),
				name.equals(openTab), name + " · " + owned + "/" + items.size() + " slots",
				() -> openTab(name)));
		}
		tabRow.add(Box.createHorizontalGlue());
		tabRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			catalog.isEmpty() ? 0 : ClogTabTile.HEIGHT));
		tabRow.revalidate();
		tabRow.repaint();
	}

	private void openTab(String name)
	{
		// pressing the open tab returns to the overview, the way the game's
		// own tabs toggle
		openTab = name.equals(openTab) ? null : name;
		openPage = null;
		search.setText("");
		rebuildAll();
	}

	private void openPage(String name)
	{
		openPage = name;
		rebuildContent();
	}

	// ── content ───────────────────────────────────────────────────────

	private void rebuildContent()
	{
		lastFingerprint = fingerprint();
		content.removeAll();

		String term = search.getText().trim().toLowerCase(Locale.ROOT);
		if (state.getClogCatalog().isEmpty())
		{
			content.add(note("Log in once and Iron Hub reads your collection log's own "
				+ "pages straight from the game."));
		}
		else if (!term.isEmpty())
		{
			searchView(term);
		}
		else if (openPage != null)
		{
			pageView();
		}
		else if (openTab != null)
		{
			categoryView();
		}
		else
		{
			overview();
		}

		content.add(Box.createVerticalStrut(UiTokens.PAD));
		suggestions();

		content.revalidate();
		content.repaint();
	}

	/** The overview's own body: what you have collected most recently. */
	private void overview()
	{
		content.add(section("Latest collections"));
		List<Integer> latest = latestSlots();
		if (latest.isEmpty())
		{
			content.add(note("Slots you fill from here on show up here — an import tells us "
				+ "what you own, never when you got it."));
			return;
		}
		content.add(gridOf(latest, true));
	}

	/** Slots we watched fill, newest first. */
	private List<Integer> latestSlots()
	{
		List<Integer> dated = new ArrayList<>();
		for (int id : state.getClogObtained())
		{
			if (state.clogObtainedAt(id) > 0)
			{
				dated.add(id);
			}
		}
		dated.sort(Comparator.comparingLong(state::clogObtainedAt).reversed());
		return dated.size() > LATEST ? dated.subList(0, LATEST) : dated;
	}

	/** A tab's pages, each with its own fill count. */
	private void categoryView()
	{
		PersistedState.ClogTab tab = tabByName(openTab);
		if (tab == null)
		{
			openTab = null;
			overview();
			return;
		}
		content.add(section(tab.name));
		int shown = 0;
		for (PersistedState.ClogPage page : tab.pages)
		{
			if (shown++ >= MAX_PAGES)
			{
				content.add(note("+ " + (tab.pages.size() - MAX_PAGES)
					+ " more — search to narrow the list"));
				break;
			}
			content.add(pageRow(page));
		}
	}

	private JComponent pageRow(PersistedState.ClogPage page)
	{
		Set<Integer> items = pageItems(page);
		int owned = obtainedIn(items);
		boolean complete = owned >= items.size() && !items.isEmpty();

		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		row.setOpaque(true);
		row.setBackground(theme.background);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		OsrsLabel name = new OsrsLabel(page.name,
			// the interface's own colouring: green when a page is finished,
			// orange while it is not
			complete ? OsrsSkin.VALUE : OsrsSkin.TITLE, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(page.name);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(owned + "/" + items.size(), OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(row);
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(theme.hoverFill);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(theme.background);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				openPage(page.name);
			}
		};
		clickAnywhere(row, click);
		return row;
	}

	/** One page: its counters, then the log's own item grid. */
	private void pageView()
	{
		PersistedState.ClogTab tab = tabByName(openTab);
		PersistedState.ClogPage page = pageByName(tab, openPage);
		if (page == null)
		{
			openPage = null;
			categoryView();
			return;
		}
		content.add(backRow(tab == null ? "Overview" : tab.name));

		JPanel head = row();
		head.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		OsrsLabel title = new OsrsLabel(page.name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		title.setToolTipText(page.name);
		head.add(title);
		head.add(Box.createHorizontalGlue());
		cap(head);
		content.add(head);

		Set<Integer> items = pageItems(page);
		int owned = obtainedIn(items);
		// the game's own red/yellow/green scale, in the skin's palette (it
		// has no red, and nothing here is a warning): faint / orange / green
		Color colour = owned == 0 ? OsrsSkin.FAINT
			: owned >= items.size() ? OsrsSkin.VALUE : OsrsSkin.TITLE;
		JPanel counts = row();
		counts.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		counts.add(new OsrsLabel("Obtained: " + owned + "/" + items.size(),
			colour, OsrsSkin.font()));
		counts.add(Box.createHorizontalGlue());
		cap(counts);
		content.add(counts);

		List<String> kc = state.clogPageCounts(page.name);
		for (String line : kc)
		{
			content.add(smallLine(line, OsrsSkin.MUTED));
		}
		if (kc.isEmpty())
		{
			OsrsLabel hint = smallLine("Kill counts show once you open this page in-game",
				OsrsSkin.FAINT);
			hint.setToolTipText("The game only fills a page's counters while that page is "
				+ "on screen, so Iron Hub can only show the ones you have looked at.");
			content.add(hint);
		}

		content.add(Box.createVerticalStrut(3));
		content.add(gridOf(new ArrayList<>(items), false));
	}

	private JComponent backRow(String target)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));
		JLabel arrow = new JLabel(new PaintedIcon(PaintedIcon.Shape.CHEVRON_LEFT, 10));
		arrow.setForeground(OsrsSkin.MUTED);
		row.add(arrow);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(target, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		cap(row);
		clickAnywhere(row, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openPage = null;
				rebuildContent();
			}
		});
		return row;
	}

	/** Search runs over both halves of the log: the slots and the pages. */
	private void searchView(String term)
	{
		LinkedHashSet<Integer> items = new LinkedHashSet<>();
		List<PersistedState.ClogPage> pages = new ArrayList<>();
		List<String> pageTabs = new ArrayList<>();
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			for (PersistedState.ClogPage page : tab.pages)
			{
				if (page.name.toLowerCase(Locale.ROOT).contains(term))
				{
					pages.add(page);
					pageTabs.add(tab.name);
				}
				for (int id : page.items)
				{
					if (items.size() < MAX_SEARCH_ITEMS
						&& itemName(id).toLowerCase(Locale.ROOT).contains(term))
					{
						items.add(id);
					}
				}
			}
		}

		if (!items.isEmpty())
		{
			content.add(section("Items (" + items.size()
				+ (items.size() >= MAX_SEARCH_ITEMS ? "+" : "") + ")"));
			content.add(gridOf(new ArrayList<>(items), true));
		}
		if (!pages.isEmpty())
		{
			content.add(section("Pages (" + pages.size() + ")"));
			for (int i = 0; i < pages.size() && i < MAX_PAGES; i++)
			{
				String tabName = pageTabs.get(i);
				PersistedState.ClogPage page = pages.get(i);
				JComponent row = pageRow(page);
				row.setToolTipText(tabName + " · " + page.name);
				clickAnywhere(row, new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						openTab = tabName;
						search.setText("");
						openPage(page.name);
					}
				});
				content.add(row);
			}
		}
		if (items.isEmpty() && pages.isEmpty())
		{
			content.add(note("Nothing in the log matches that."));
		}
	}

	/** The item grid, wired for goals and the wiki. */
	private JComponent gridOf(List<Integer> ids, boolean ownedOnly)
	{
		List<ClogItemGrid.Cell> cells = new ArrayList<>();
		for (int id : ids)
		{
			boolean owned = state.getClogObtained().contains(id);
			if (ownedOnly && !owned)
			{
				continue;
			}
			cells.add(new ClogItemGrid.Cell(id, itemName(id), owned, state.clogQuantity(id)));
		}
		ClogItemGrid grid = new ClogItemGrid(cells, sprites);
		grid.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				menu(grid, e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				menu(grid, e);
			}
		});
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		holder.add(grid);
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private void menu(ClogItemGrid grid, MouseEvent e)
	{
		ClogItemGrid.Cell cell = grid.cellAt(e.getPoint());
		// right-click for options, the way the game does it — a left click on
		// a slot you are only looking at must never start tracking a goal
		if (cell == null || !e.isPopupTrigger())
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page (" + cell.name + ")");
		wiki.addActionListener(a -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
			+ cell.name.replace(" ", "_").replace("'", "%27")));
		menu.add(wiki);
		JMenuItem goal = new JMenuItem(isGoal(cell.itemId)
			? "Remove from Goals" : "Add to Goals");
		goal.addActionListener(a -> toggleGoal(cell.itemId, cell.name));
		menu.add(goal);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	// ── the ranking, kept as its own section at the foot ──────────────

	private void suggestions()
	{
		content.add(section("Easiest next slots"));
		List<ClogRanker.Ranked> ranked = ranking();
		if (ranked.isEmpty())
		{
			content.add(note("Every rankable slot is obtained."));
			return;
		}
		// ten rows, no more (Luke): this is a nudge at the foot of a browser,
		// not the browser itself
		int limit = Math.min(SUGGESTIONS, ranked.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(suggestionRow(ranked.get(i)));
		}
	}

	private JComponent suggestionRow(ClogRanker.Ranked ranked)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		row.setOpaque(true);
		row.setBackground(theme.background);
		if (ranked.display != null)
		{
			JLabel icon = new JLabel();
			java.awt.Image sprite = sprites.get(ranked.display.itemId, -1, 16);
			if (sprite != null)
			{
				icon.setIcon(new javax.swing.ImageIcon(sprite));
			}
			row.add(icon);
			row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		// the ACTIVITY names the row, as it always has: two activities can
		// share a next slot ("Revenant ether"), and two identical rows read
		// as a bug rather than as two places to go
		OsrsLabel name = new OsrsLabel(ranked.activity.name,
			ranked.locked ? OsrsSkin.FAINT : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText((ranked.display == null ? ranked.activity.name
			: "Next: " + ranked.display.name)
			+ (ranked.locked ? " — locked · needs " + ranked.missing : "")
			+ " · " + ranked.slotsLeft + " of " + ranked.slotsTotal + " slots left");
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel time = new OsrsLabel("~" + Format.hours(ranked.hours),
			OsrsSkin.MUTED, OsrsSkin.smallFont());
		time.setToolTipText("Expected time to this activity's next slot at ironman rates");
		row.add(time);
		if (ranked.display != null)
		{
			row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			row.add(goalGlyph(ranked.display.itemId, ranked.display.name));
		}
		cap(row);
		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(theme.hoverFill);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(theme.background);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					skipMenu(ranked, e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					skipMenu(ranked, e);
				}
			}
		};
		clickAnywhere(row, interaction);
		return row;
	}

	private void skipMenu(ClogRanker.Ranked ranked, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem skip = new JMenuItem("Skip " + ranked.activity.name);
		skip.addActionListener(a -> state.setClogSkipped(ranked.activity.index, true));
		menu.add(skip);
		if (!state.getClogSkipped().isEmpty())
		{
			JMenuItem unskip = new JMenuItem("Unskip everything ("
				+ state.getClogSkipped().size() + ")");
			unskip.addActionListener(a ->
			{
				for (int index : new ArrayList<>(state.getClogSkipped()))
				{
					state.setClogSkipped(index, false);
				}
			});
			menu.add(unskip);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private List<ClogRanker.Ranked> rankingCache;

	private List<ClogRanker.Ranked> ranking()
	{
		if (rankingCache == null)
		{
			rankingCache = ClogRanker.rank(module.pack(), state.getClogObtained(),
				state.getClogSkipped(), state);
		}
		return rankingCache;
	}

	// ── goals ─────────────────────────────────────────────────────────

	private Set<String> selectedClogGoals()
	{
		Set<String> ids = new HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("clog:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	private boolean isGoal(int itemId)
	{
		return state.getSelectedGoals().contains("clog:" + itemId);
	}

	private JLabel goalGlyph(int itemId, String slotName)
	{
		boolean goal = isGoal(itemId);
		JLabel glyph = new JLabel(goal ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(goal ? "Remove " + slotName + " from Goals"
			: "Add " + slotName + " as a goal in Goals");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.putClientProperty(OWN_ACTION, Boolean.TRUE);
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleGoal(itemId, slotName);
			}
		});
		return glyph;
	}

	/** A slot joins (or leaves) the Goal planner as a {@code clog:} goal. */
	private void toggleGoal(int itemId, String slotName)
	{
		if (isGoal(itemId))
		{
			state.removeGoalSeed("clog:" + itemId);
			return;
		}
		ClogPack.Activity activity = activityFor(itemId);
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clog(itemId, slotName,
			activity == null ? sourcePage(itemId) : activity.name,
			activity == null ? List.of() : activity.reqs));
		if (state.getClogObtained().contains(itemId))
		{
			state.setUnlocked("clogitem_" + itemId, true);
		}
	}

	/** The clog pack's activity for a slot — its rates and requirements are
	 *  what make the goal routable. Null when the pack has never seen it. */
	private ClogPack.Activity activityFor(int itemId)
	{
		for (ClogPack.Activity activity : module.pack().activities)
		{
			for (ClogPack.Item item : activity.items)
			{
				if (item.itemId == itemId)
				{
					return activity;
				}
			}
		}
		return null;
	}

	/** The log page a slot sits on — an honest label when the ranking pack
	 *  has no activity for it. */
	private String sourcePage(int itemId)
	{
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			for (PersistedState.ClogPage page : tab.pages)
			{
				for (int id : page.items)
				{
					if (id == itemId)
					{
						return page.name;
					}
				}
			}
		}
		return "";
	}

	// ── catalog helpers ───────────────────────────────────────────────

	private PersistedState.ClogTab tabByName(String name)
	{
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			if (tab.name.equals(name))
			{
				return tab;
			}
		}
		return null;
	}

	private static PersistedState.ClogPage pageByName(PersistedState.ClogTab tab, String name)
	{
		if (tab == null || name == null)
		{
			return null;
		}
		for (PersistedState.ClogPage page : tab.pages)
		{
			if (page.name.equals(name))
			{
				return page;
			}
		}
		return null;
	}

	/** A page's slots, deduped: the log shares a slot across pages, and a
	 *  page can list the same item twice (the game does the same). */
	private static LinkedHashSet<Integer> pageItems(PersistedState.ClogPage page)
	{
		LinkedHashSet<Integer> items = new LinkedHashSet<>();
		for (int id : page.items)
		{
			items.add(id);
		}
		return items;
	}

	private static Set<Integer> tabItems(PersistedState.ClogTab tab)
	{
		LinkedHashSet<Integer> items = new LinkedHashSet<>();
		for (PersistedState.ClogPage page : tab.pages)
		{
			for (int id : page.items)
			{
				items.add(id);
			}
		}
		return items;
	}

	private int obtainedIn(Set<Integer> items)
	{
		int owned = 0;
		for (int id : items)
		{
			if (state.getClogObtained().contains(id))
			{
				owned++;
			}
		}
		return owned;
	}

	/** The player's slot count: the game's own varp when it has arrived,
	 *  else what we have imported. */
	private int loggedSlots()
	{
		int varp = state.getVarp(VarPlayerID.COLLECTION_COUNT);
		return varp > 0 ? varp : state.getClogObtained().size();
	}

	/** The log's size: the title we read on open (game truth), else the
	 *  catalog's own unique slot count, else the ranking pack's. */
	private int totalSlots()
	{
		if (state.getCollectionLogTotal() > 0)
		{
			return state.getCollectionLogTotal();
		}
		Set<Integer> unique = new HashSet<>();
		for (PersistedState.ClogTab tab : state.getClogCatalog())
		{
			unique.addAll(tabItems(tab));
		}
		return unique.isEmpty() ? module.pack().slots.size() : unique.size();
	}

	private Map<Integer, String> names;

	/** A slot's name: the ranking pack first (it names every slot it knows),
	 *  then the item-sources projection for anything newer. */
	private String itemName(int itemId)
	{
		if (names == null)
		{
			names = new java.util.HashMap<>();
			for (ClogPack.Slot slot : module.pack().slots)
			{
				names.putIfAbsent(slot.itemId, slot.name);
			}
		}
		String known = names.get(itemId);
		if (known != null)
		{
			return known;
		}
		com.ironhub.data.ItemSourcesPack sources = module.itemSources();
		com.ironhub.data.ItemSourcesPack.Entry entry =
			sources == null ? null : sources.entry(itemId);
		String name = entry == null ? null : entry.getName();
		if (name == null || name.isEmpty())
		{
			name = state.itemName(itemId);
		}
		names.put(itemId, name);
		return name;
	}

	// ── test seams ────────────────────────────────────────────────────

	/** Open a tab (and optionally one of its pages) as a click would. */
	void showForRender(String tab, String page)
	{
		openTab = tab;
		openPage = page;
		rebuildAll();
	}

	// ── layout helpers (the shared skinned-tab grammar) ───────────────

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private static JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(6, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private static OsrsLabel smallLine(String text, Color colour)
	{
		OsrsLabel label = OsrsLabel.wrapped(text, UiTokens.PANEL_WIDTH - 24, colour,
			OsrsSkin.smallFont());
		label.leftAligned();
		label.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		cap(label);
		return label;
	}

	private static JComponent note(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, UiTokens.PANEL_WIDTH - 30,
			OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Attach a click to a container AND its passive children — labels with
	 *  tooltips register their own listeners and would swallow clicks. */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof JComponent
				&& Boolean.TRUE.equals(((JComponent) child).getClientProperty(OWN_ACTION)))
			{
				continue; // keeps its own action
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
