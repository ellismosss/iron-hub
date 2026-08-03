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
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
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
 * <li>a hero banner framing the log's total between the rank you have
 *     reached and the one you are climbing to (staves in bordered squares,
 *     the tier count riding the sprite bar), with a panel-side Sync log
 *     button and the overview's latest-collections icon strip beneath;
 * <li>the log's five tabs as icon tiles with their counts and fill bars;
 * <li>a category view of that tab's pages as a 2-wide grid of square DLV2
 *     CARD tiles (Luke, 2026-07-27) — bold inside captions on the log's
 *     orange/green scale, corner counts, meter strips — expanding a page's
 *     results in-line, one at a time: counters in the game's own label/value
 *     colours, then the item grid, sprites solid when owned, ghosted when
 *     not, exactly as the interface draws them;
 * <li>and the old Time-To-Next-Slot ranking on its own fold-out card at the
 *     foot, clamped to ten table rows.
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
	/** The category view's page grid: two PERFECT-SQUARE Cards across the
	 *  217px content column (2x106 + 4 = 216; Luke, 2026-07-27), captions
	 *  inside, a plain meter strip instead of a status edge. */
	private static final int PAGE_COLS = 2;
	private static final int PAGE_TILE = 106;
	/** The page emblem, sized to actually fill the card's art band. */
	private static final int PAGE_EMBLEM = 44;
	/** Announced but unreleased — a greyed tile at the end of the Raids
	 *  grid until the game's own catalog carries the page, at which point
	 *  the placeholder yields automatically. */
	private static final String UPCOMING_RAID = "The Fractured Archive";
	/** Marks a child that keeps its own click (the +/x glyphs). */
	private static final String OWN_ACTION = "clog.ownAction";
	/** The latest-collections strip's icon cap (7 x 28px fits the column). */
	private static final int LATEST_STRIP = 7;
	private static final int CARD_WRAP = 180;

	private final CollectionLogModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = RebuildGate.install(this, this::onStateChanged);
	// sprites bypass the fingerprint: an arriving icon changes no state, so
	// routing it into onStateChanged compared equal and never repainted —
	// blank icons until an unrelated rebuild (CA's tab had it right)
	private final Runnable spriteListener = RebuildGate.install(this, this::rebuildAll);
	private final SpriteCache sprites;
	private final Set<Integer> slayerActivities;

	// persistent chrome
	private final V2Surface hero;
	private final V2ProgressBar bar;
	/** The game overview's recent-slots icon strip, under the hero. */
	private final JPanel latestStrip = new JPanel();
	/** Panel Sync log feedback ("Open your collection log first"). */
	private String syncNote;
	private final JPanel tabRow = new JPanel();
	private final V2TextField search;
	private final JPanel content = new JPanel();

	// view state
	private String openTab;   // null = the overview
	private String openPage;  // null = the tab's page list
	/** The category grid's ONE in-line expanded page (Luke, 2026-07-27). */
	private String expandedPage;
	/** The Easiest-next-slots card starts folded (Luke, 2026-07-27). */
	private boolean suggestionsCollapsed = true;
	private List<Object> lastFingerprint = List.of();

	CollectionLogTab(CollectionLogModule module, AccountState state, ItemManager itemManager,
		OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		this.sprites = new SpriteCache(itemManager, spriteListener);

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

		// the log standing is the one live readout on the page — the Card,
		// with the SPRITE bar and the tier value riding on it (Luke,
		// 2026-07-27, matching the game's own overview)
		hero = V2Surface.card(theme);
		bar = new V2ProgressBar(theme);
		add(hero);
		add(Box.createVerticalStrut(4));
		latestStrip.setLayout(new BoxLayout(latestStrip, BoxLayout.Y_AXIS));
		latestStrip.setOpaque(false);
		latestStrip.setAlignmentX(LEFT_ALIGNMENT);
		add(latestStrip);

		tabRow.setLayout(new BoxLayout(tabRow, BoxLayout.X_AXIS));
		tabRow.setOpaque(false);
		tabRow.setAlignmentX(LEFT_ALIGNMENT);
		add(tabRow);
		add(Box.createVerticalStrut(4));

		search = new V2TextField(theme, "Search items or pages…", null);
		add(search);
		search.editor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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
		print.add(state.clogQuantitiesDigest()); // count-only harvests must re-render open pages
		print.add(state.getClogSkipped());
		print.add(selectedClogGoals());
		print.add(state.getClogBaseline());
		print.add(state.getVarp(VarPlayerID.COLLECTION_COUNT));
		print.add(catalogPrint());
		print.add(openTab);
		print.add(openPage);
		print.add(expandedPage);
		print.add(suggestionsCollapsed);
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
		// the staves sit IN-LINE with the two text lines, like the CA tab's
		// Ghommal's hilts — plain, no box (Luke's screenshot round,
		// 2026-07-27)
		top.add(staff(reached));
		// glue BOTH sides: the count stays centred between the two staves
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Collections Logged", OsrsSkin.TITLE, OsrsSkin.font()));
		// the WHOLE log's progress, in orange; the tier band's own count
		// rides on the bar below (Luke, 2026-07-27)
		middle.add(new OsrsLabel(String.format(Locale.ROOT, "%,d / %,d", slots, total),
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(staff(next));
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		bar.fraction(ceiling > floor ? (double) (slots - floor) / (ceiling - floor) : 1);
		bar.labels("", String.format(Locale.ROOT, "%,d / %,d", slots, ceiling), "");
		hero.add(bar);

		JPanel labels = row();
		labels.add(rankLabel(reached == null ? "Unranked" : ranks.label(reached)));
		labels.add(Box.createHorizontalGlue());
		labels.add(rankLabel(next == null ? "Every rank claimed" : ranks.label(next)));
		cap(labels);
		hero.add(labels);

		// the sync row exists only while a sync would ADD something: never
		// synced, or the in-game slot count drifted past the last sync
		// (drops landed while the plugin wasn't watching — mobile, another
		// machine, plugin off). In sync = no row at all (Luke, 2026-07-27).
		JComponent line = syncLine();
		if (line != null)
		{
			hero.add(Box.createVerticalStrut(2));
			JPanel syncRow = row();
			syncRow.add(line);
			syncRow.add(Box.createHorizontalGlue());
			syncRow.add(com.ironhub.ui.v2.V2ChipRow.action(theme, "Sync log", this::requestSync));
			cap(syncRow);
			hero.add(syncRow);
			if (syncNote != null)
			{
				hero.add(smallLine(syncNote, OsrsSkin.TITLE));
			}
		}
		cap(hero);
		hero.revalidate();
		hero.repaint();
		rebuildLatestStrip();
	}

	/** Panel-side Sync log: same sync as the in-log button, when the log is
	 *  open — otherwise an honest pointer, never an interface we open. */
	private void requestSync()
	{
		module.syncFromPanel(started ->
		{
			syncNote = started ? null : "Open your collection log first";
			rebuildHero();
		});
	}

	/** "VII: Rune" — the numeral orange, the rank name white (Luke,
	 *  2026-07-27, the game's own formatting). */
	private JComponent rankLabel(String label)
	{
		int split = label.indexOf(": ");
		if (split < 0)
		{
			return new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.smallFont());
		}
		JPanel pair = row();
		pair.add(new OsrsLabel(label.substring(0, split + 2),
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		pair.add(new OsrsLabel(label.substring(split + 2),
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		pair.setMaximumSize(pair.getPreferredSize());
		return pair;
	}

	/**
	 * The game overview's "Latest Collections" strip, per Luke's screenshot
	 * (2026-07-27): a centred orange header, then the newest obtained slots
	 * as plain icons side by side inside ONE long recessed box — 1px black
	 * outside a 1px grey inside — never a box per icon.
	 */
	private void rebuildLatestStrip()
	{
		latestStrip.removeAll();
		List<Integer> latest = latestSlots();
		if (!latest.isEmpty())
		{
			JPanel head = row();
			head.add(Box.createHorizontalGlue());
			head.add(new OsrsLabel("Latest Collections", OsrsSkin.TITLE, OsrsSkin.boldFont()));
			head.add(Box.createHorizontalGlue());
			cap(head);
			latestStrip.add(head);
			latestStrip.add(Box.createVerticalStrut(2));

			JPanel box = new JPanel();
			box.setLayout(new BoxLayout(box, BoxLayout.X_AXIS));
			box.setBackground(theme.recess);
			box.setOpaque(true);
			box.setAlignmentX(LEFT_ALIGNMENT);
			box.setBorder(javax.swing.BorderFactory.createCompoundBorder(
				javax.swing.BorderFactory.createCompoundBorder(
					new javax.swing.border.LineBorder(Color.BLACK, 1),
					new javax.swing.border.LineBorder(OsrsSkin.FAINT, 1)),
				new EmptyBorder(2, 2, 2, 2)));
			// distributed across the FULL row (C2, Luke 2026-08-03): minimal
			// margin at the far edges, one equal glue gap between each pair
			int shown = 0;
			for (int id : latest)
			{
				if (shown >= LATEST_STRIP)
				{
					break;
				}
				if (shown > 0)
				{
					box.add(Box.createHorizontalGlue());
				}
				shown++;
				JLabel icon = new JLabel();
				java.awt.Image sprite = sprites.getBox(id, 22);
				if (sprite != null)
				{
					icon.setIcon(new javax.swing.ImageIcon(sprite));
				}
				icon.setPreferredSize(new Dimension(24, 24));
				icon.setMaximumSize(new Dimension(24, 24));
				icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
				icon.setToolTipText(itemName(id));
				box.add(icon);
			}
			cap(box);
			latestStrip.add(box);
			latestStrip.add(Box.createVerticalStrut(4));
		}
		latestStrip.revalidate();
		latestStrip.repaint();
	}

	/** A rank's staff sprite, tooltipped with the rank it stands for. */
	private JComponent staff(ClogRanksPack.Rank rank)
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
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
			text = "Open the log, then press Sync log here";
			tip = "Open your collection log in-game, then press Sync log — "
				+ "every obtained slot imports in one click.";
		}
		else if (!module.inSync())
		{
			colour = OsrsSkin.TITLE;
			text = "New slots since last sync";
			tip = "Your in-game slot count moved past the last full sync — open the "
				+ "collection log and press Sync log here to catch up.";
		}
		else
		{
			// in sync says nothing at all — no "Synced · ago" line (Luke,
			// 2026-07-27); the nag states above are the whole message
			return null;
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
		// glue BOTH sides — the five cards sit centred, not left (Luke,
		// 2026-07-27)
		tabRow.add(Box.createHorizontalGlue());
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
				name.equals(openTab), () -> openTab(name)));
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

	/** The overview's own body: nothing — the hero, the strip and the tabs
	 *  above it ARE the overview (Luke, 2026-07-27, note removed too). */
	private void overview()
	{
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

	/** A tab's pages as a 2-wide grid of square DLV2 icon tiles (Luke,
	 *  2026-07-27): emblem = the page's own first slot, bold caption on the
	 *  art — orange until the page is complete, then green — and the count
	 *  top-right. Clicking a tile expands the page's results IN-LINE below
	 *  its row; one page at a time, click again to close. */
	private void categoryView()
	{
		PersistedState.ClogTab tab = tabByName(openTab);
		if (tab == null)
		{
			openTab = null;
			overview();
			return;
		}
		// the category header centred over its grid, orange bold — the log's
		// own tab title framing (Luke, 2026-07-27)
		JPanel head = row();
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(tab.name, OsrsSkin.TITLE, OsrsSkin.boldFont()));
		head.add(Box.createHorizontalGlue());
		cap(head);
		content.add(head);
		content.add(Box.createVerticalStrut(V2Tokens.TIGHT));
		List<PersistedState.ClogPage> pages = tab.pages.size() > MAX_PAGES
			? tab.pages.subList(0, MAX_PAGES) : tab.pages;
		boolean phantomRaid = "Raids".equalsIgnoreCase(tab.name)
			&& tab.pages.stream().noneMatch(p -> UPCOMING_RAID.equalsIgnoreCase(p.name));
		int tiles = pages.size() + (phantomRaid ? 1 : 0);
		for (int start = 0; start < tiles; start += PAGE_COLS)
		{
			List<PersistedState.ClogPage> rowPages = new ArrayList<>();
			JPanel row = row();
			// glue BOTH sides — full rows centre in the column and a lone
			// last tile centres too (Luke, 2026-07-27)
			row.add(Box.createHorizontalGlue());
			for (int i = start; i < Math.min(start + PAGE_COLS, tiles); i++)
			{
				if (i > start)
				{
					row.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				if (i < pages.size())
				{
					rowPages.add(pages.get(i));
					row.add(pageTile(pages.get(i)));
				}
				else
				{
					row.add(upcomingRaidTile());
				}
			}
			row.add(Box.createHorizontalGlue());
			cap(row);
			content.add(row);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			// the ONE expanded page's results land under its own row
			for (PersistedState.ClogPage page : rowPages)
			{
				if (page.name.equals(expandedPage))
				{
					pageDetail(page);
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
		if (tab.pages.size() > MAX_PAGES)
		{
			content.add(note("+ " + (tab.pages.size() - MAX_PAGES)
				+ " more — search to narrow the list"));
		}
	}

	private V2Tile upcomingRaidTile()
	{
		V2Tile tile = new V2Tile(theme, null, UPCOMING_RAID, PAGE_TILE, null)
			.card().captionLines(2).captionInside()
			.status(V2Tile.Status.UNAVAILABLE);
		tile.setCursor(Cursor.getDefaultCursor());
		return tile;
	}

	private V2Tile pageTile(PersistedState.ClogPage page)
	{
		Set<Integer> items = pageItems(page);
		int owned = obtainedIn(items);
		boolean complete = owned >= items.size() && !items.isEmpty();
		boolean expanded = page.name.equals(expandedPage);
		java.awt.Image emblem = page.items.length == 0
			? null : sprites.getBox(page.items[0], PAGE_EMBLEM);
		// the corner count wears the category cards' grammar (Luke,
		// 2026-07-27): obtained red at 0 / orange filling / green done,
		// "/total" orange until the page completes green
		Color cornerOwned = complete ? V2Tokens.DONE
			: owned == 0 ? V2Tokens.BLOCKED : V2Tokens.ACTION;
		Color cornerRest = complete ? V2Tokens.DONE : V2Tokens.ACTION;
		V2Tile tile = new V2Tile(theme, emblem, page.name, PAGE_TILE, () ->
			{
				// single expansion: a second click on the open tile closes it
				expandedPage = expanded ? null : page.name;
				rebuildContent();
			})
			.card().captionLines(2).captionInside()
			.captionStatus(complete ? V2Tokens.DONE : V2Tokens.ACTION)
			.corner(String.valueOf(owned), cornerOwned, "/" + items.size(), cornerRest)
			.selected(expanded)
			// the meter strip is the progress readout — no status edges on
			// a card tile, and no hover tooltip on a card (Luke, 2026-07-27)
			.meter(items.isEmpty() ? Double.NaN : (double) owned / items.size());
		return tile;
	}

	private JComponent pageRow(PersistedState.ClogPage page)
	{
		Set<Integer> items = pageItems(page);
		int owned = obtainedIn(items);
		boolean complete = owned >= items.size() && !items.isEmpty();

		// hover is the translucent HIGHLIGHT wash, not an opaque background
		// swap — the row lights over whatever it sits on (X1 2026-08-03)
		boolean[] hover = {false};
		JPanel row = new JPanel()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				if (hover[0])
				{
					g.setColor(V2Tokens.HIGHLIGHT);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				super.paintComponent(g);
			}
		};
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
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
				hover[0] = true;
				row.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover[0] = false;
				row.repaint();
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

		pageDetail(page);
	}

	/** A page's results — obtained count, its captured kill counts, the
	 *  log's own item grid — added to {@link #content}. Shared by the full
	 *  page view (search) and the category grid's in-line expansion. */
	private void pageDetail(PersistedState.ClogPage page)
	{
		Set<Integer> items = pageItems(page);
		int owned = obtainedIn(items);
		// the game's own counter formatting (Luke, 2026-07-27): the label in
		// the log's orange, the count on its red/yellow/green scale
		Color countColour = owned == 0 ? V2Tokens.BLOCKED
			: owned >= items.size() ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		content.add(counterLine("Obtained: ", owned + "/" + items.size(), countColour));

		List<String> kc = state.clogPageCounts(page.name);
		for (String line : kc)
		{
			// "Kree'arra kills: 30" / "Personal best: 1:23.60" — the label
			// orange, the value white, exactly as the game draws them
			int split = line.indexOf(": ");
			if (split < 0)
			{
				content.add(smallLine(line, OsrsSkin.LABEL));
			}
			else
			{
				content.add(counterLine(line.substring(0, split + 2),
					line.substring(split + 2), V2Tokens.STRONG));
			}
		}
		content.add(Box.createVerticalStrut(3));
		content.add(gridOf(new ArrayList<>(items), false));
	}

	/** One counter row in the game's own detail formatting: an orange label,
	 *  a coloured value. */
	private JComponent counterLine(String label, String value, Color valueColour)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(label, OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		row.add(new OsrsLabel(value, valueColour, OsrsSkin.smallFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
		// glue BOTH sides — the grid sits centred like everything else in
		// the tab (Luke, 2026-07-27)
		holder.add(Box.createHorizontalGlue());
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
		wiki.addActionListener(a -> LinkBrowser.browse(com.ironhub.ui.WikiLinks.url(cell.name)));
		menu.add(wiki);
		JMenuItem goal = new JMenuItem(isGoal(cell.itemId)
			? "Remove from Goals" : "Add to Goals");
		goal.addActionListener(a -> toggleGoal(cell.itemId, cell.name));
		menu.add(goal);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	// ── the ranking, kept as its own section at the foot ──────────────

	/**
	 * "Easiest next slots": a pressable Card holding just the bold orange
	 * text — the card art's own hovered state lights it under the pointer
	 * (no chevron; the art carries no separate pressed sprite and §8 never
	 * invents one) — with the ranking on its own WELL below, which is what
	 * a themed V2Table wears (Luke, 2026-07-27).
	 */
	private void suggestions()
	{
		V2Surface card = V2Surface.card(theme);
		card.setAlignmentX(LEFT_ALIGNMENT);
		JPanel head = row();
		OsrsLabel title = new OsrsLabel("Easiest next slots", OsrsSkin.TITLE, OsrsSkin.boldFont());
		head.add(Box.createHorizontalGlue());
		head.add(title);
		head.add(Box.createHorizontalGlue());
		cap(head);
		card.add(head);
		if (suggestionsCollapsed)
		{
			// hover = the SUBTLE wash, never the hovered art — that art
			// means "pressed in" here and shows only while open (Luke,
			// 2026-07-27). The click goes on every layer below, because AWT
			// delivers a press to the DEEPEST component only.
			card.washHoverable();
		}
		else
		{
			// held in the PRESSED (hovered-art) state while its results are
			// showing — lit is its own flag now, so the pointer leaving
			// cannot unlight it, and hovering washes the lit art the same
			// way it washes the plain one (Luke, 2026-07-27)
			card.setLit(true);
			card.washHoverable();
		}
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// the relay makes clicks on the text reach the card (deepest-
		// component dispatch — the root cause behind every "only works
		// beside the text" report; MouseRelay)
		com.ironhub.ui.v2.MouseRelay.install(card);
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				suggestionsCollapsed = !suggestionsCollapsed;
				rebuildContent();
			}
		});
		cap(card);
		content.add(card);
		if (!suggestionsCollapsed)
		{
			content.add(Box.createVerticalStrut(2));
			List<ClogRanker.Ranked> ranked = ranking();
			if (ranked.isEmpty())
			{
				content.add(note("Every rankable slot is obtained."));
			}
			else
			{
				// ten rows, no more (Luke): this is a nudge at the foot of a
				// browser, not the browser itself
				com.ironhub.ui.v2.V2Table table = new com.ironhub.ui.v2.V2Table(theme, 1);
				int limit = Math.min(SUGGESTIONS, ranked.size());
				for (int i = 0; i < limit; i++)
				{
					suggestionTableRow(table, ranked.get(i));
				}
				content.add(table);
			}
		}
	}

	/** One ranked activity as a table row: icon · name · ~time · goal. */
	private void suggestionTableRow(com.ironhub.ui.v2.V2Table table, ClogRanker.Ranked ranked)
	{
		JLabel icon = new JLabel();
		if (ranked.display != null)
		{
			java.awt.Image sprite = sprites.get(ranked.display.itemId, -1, 16);
			if (sprite != null)
			{
				icon.setIcon(new javax.swing.ImageIcon(sprite));
			}
		}
		OsrsLabel name = new OsrsLabel(ranked.activity.name,
			ranked.locked ? OsrsSkin.FAINT : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		String tip = (ranked.display == null ? ranked.activity.name
			: "Next: " + ranked.display.name)
			+ (ranked.locked ? " — locked · needs " + ranked.missing : "")
			+ " · " + ranked.slotsLeft + " of " + ranked.slotsTotal + " slots left";
		name.setToolTipText(tip);
		MouseAdapter skip = new MouseAdapter()
		{
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
		icon.addMouseListener(skip);
		name.addMouseListener(skip);
		OsrsLabel time = new OsrsLabel("~" + Format.hours(ranked.hours),
			OsrsSkin.MUTED, OsrsSkin.smallFont());
		time.setToolTipText("Expected time to this activity's next slot at ironman rates");
		if (ranked.display != null)
		{
			table.row(icon, name, com.ironhub.ui.v2.V2Table.right(time),
				goalGlyph(ranked.display.itemId, ranked.display.name));
		}
		else
		{
			table.row(icon, name, com.ironhub.ui.v2.V2Table.right(time),
				com.ironhub.ui.v2.V2Table.blank());
		}
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

	private JComponent goalGlyph(int itemId, String slotName)
	{
		// the shared letter-glyph atom (unified 2026-08-03)
		boolean goal = isGoal(itemId);
		JComponent glyph = new com.ironhub.ui.v2.V2GlyphButton(goal ? "×" : "+",
			goal ? "Remove " + slotName + " from Goals"
				: "Add " + slotName + " as a goal in Goals",
			() -> toggleGoal(itemId, slotName));
		glyph.putClientProperty(OWN_ACTION, Boolean.TRUE);
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

	/** Test hook: the category grid with one page expanded in-line. */
	void expandForRender(String tab, String page)
	{
		openTab = tab;
		openPage = null;
		expandedPage = page;
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
