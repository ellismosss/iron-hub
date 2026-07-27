package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.LinkBrowser;

/**
 * Combat achievements, in the shape of the game's own interface (Luke,
 * 2026-07-24):
 *
 * <ul>
 * <li>a hero banner counting points between the Ghommal's hilt you have
 *     earned and the one the next tier hands over — the collection log
 *     surface's banner, with the reward ladder swapped in;
 * <li>the interface's "Combat Profile" panel, computed exactly as the game
 *     computes it ({@link CaProfile}): tasks completed, boss and skilling
 *     kill counts, raid completions, and your top three;
 * <li>two views behind tile buttons — Difficulty (the six tiers with their
 *     fill bars) and Bosses (the interface's own grid, three across, in its
 *     own order) — each tile opening a page listing every combat achievement
 *     under it;
 * <li>and the searchable task list ({@link CaTaskBrowser}) folded into a
 *     collapsible section at the foot.
 * </ul>
 *
 * Frameless: the hub host provides the frame.
 */
class CombatAchievementsTab extends JPanel
{
	/** Grid geometry — the clog page grid's square Cards, measured against
	 *  the 217px content column (2x106+4 = 216). */
	private static final int TIER_COLUMNS = 2;
	private static final int TIER_TILE = 106;
	private static final int BOSS_COLUMNS = 2;
	private static final int BOSS_TILE = 106;
	/** The emblem box, matching the clog page cards (Luke, 2026-07-27). */
	private static final int PAGE_EMBLEM = 44;
	/** Boss pages: 10 rows of 2 per page, arrows below (Luke, 2026-07-27). */
	private static final int BOSS_PAGE_ROWS = 10;
	/** The shared filters, under the view chips (Luke, 2026-07-27). */
	static final String[] TYPE_OPTIONS = {"All types", "Stamina", "Perfection",
		"Kill Count", "Mechanical", "Restriction", "Speed"};
	/** The game's own tier swords, the _large variants (Luke, 2026-07-27). */
	private static final String[] TIER_SWORDS = {"bronze_sword", "steel_sword",
		"black_sword", "rune_sword", "dragon_sword", "armadyl_godsword"};
	/** Row ceiling for a page's task list (the Bank tab's grammar). */
	private static final int MAX_TASKS = 50;
	private static final int WRAP = 185;

	private final CombatAchievementsModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = RebuildGate.install(this, this::onStateChanged);

	private final V2Surface hero;
	private final V2ProgressBar heroBar;
	private final V2Surface profile;
	private final V2ChipRow views;
	/** Checked = the finished tasks; unchecked = the to-do list. */
	private final com.ironhub.ui.v2.V2Checkbox completedFilter;
	private final com.ironhub.ui.v2.V2Dropdown typeFilter;
	/** Non-exclusive tier icon toggles (Luke, 2026-07-27: "the original
	 *  tier filter"), all on by default. */
	private final Map<CaTier, Boolean> tierEnabled = new java.util.EnumMap<>(CaTier.class);
	/** The tier toggles' row — hidden on the Difficulty view, whose cards
	 *  already partition by tier. */
	private final JPanel tierFilterRow;
	private final JPanel content = new JPanel();
	private final JPanel browserSlot = new JPanel();
	/** The All-tasks header card — lit while the browser is open. */
	private V2Surface browserCard;
	private final CaTaskBrowser browser;

	/** null = nothing expanded; otherwise the ONE tier or boss whose tasks
	 *  show in-line under its grid row (the clog grammar). */
	private CaTier openTier;
	private String openBoss;
	/** The boss grid's current page (10 rows of 2 per page). */
	private int bossGridPage;
	private boolean browserExpanded;
	/** The Combat Profile card starts folded (Luke, 2026-07-27). */
	private boolean profileExpanded;
	private List<Object> lastPrint = List.of();
	/** Boss-card emblems arrive async from the item cache. */
	private final Runnable spriteListener = RebuildGate.install(this, this::rebuildAll);
	private SpriteCache sprites;

	CombatAchievementsTab(CombatAchievementsModule module, AccountState state,
		IronHubConfig config, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		this.sprites = new SpriteCache(module.itemManager(), spriteListener);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// the points standing is the one live readout on the page — the Card,
		// with the SPRITE bar (the clog reference shape; Luke, 2026-07-27)
		hero = V2Surface.card(theme);
		heroBar = new V2ProgressBar(theme);
		add(hero);
		add(Box.createVerticalStrut(4));

		// the account's own combat profile — an EXPANDABLE Card (Luke,
		// 2026-07-27), toggled by the Goals sections' simple triangle
		profile = V2Surface.card(theme);
		profile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		com.ironhub.ui.v2.MouseRelay.install(profile);
		profile.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				profileExpanded = !profileExpanded;
				rebuildProfile();
			}
		});
		add(profile);
		add(Box.createVerticalStrut(4));

		views = new V2ChipRow(theme, true, "Difficulty", "Bosses");
		add(views);
		add(Box.createVerticalStrut(4));

		// the filters, moved out of All tasks (Luke, 2026-07-27): a
		// "Completed" checkbox (unchecked = the to-do list) beside the type
		// dropdown — whose open list floats OVER the cards — and the
		// non-exclusive tier icon toggles below, on the Bosses view only
		for (CaTier tier : CaTier.values())
		{
			tierEnabled.put(tier, true);
		}
		completedFilter = new com.ironhub.ui.v2.V2Checkbox(theme, "Completed", false,
			this::filtersChanged);
		typeFilter = new com.ironhub.ui.v2.V2Dropdown(theme, TYPE_OPTIONS).width(107);
		typeFilter.onChange(i -> filtersChanged());
		JPanel filterPair = new JPanel();
		filterPair.setLayout(new BoxLayout(filterPair, BoxLayout.X_AXIS));
		filterPair.setOpaque(false);
		filterPair.setAlignmentX(LEFT_ALIGNMENT);
		filterPair.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		filterPair.add(completedFilter);
		filterPair.add(Box.createHorizontalGlue());
		filterPair.add(typeFilter);
		add(filterPair);
		add(Box.createVerticalStrut(4));
		tierFilterRow = new JPanel();
		tierFilterRow.setLayout(new BoxLayout(tierFilterRow, BoxLayout.Y_AXIS));
		tierFilterRow.setOpaque(false);
		tierFilterRow.setAlignmentX(LEFT_ALIGNMENT);
		JPanel tierToggles = new JPanel(new java.awt.GridLayout(1, CaTier.values().length, 2, 0));
		tierToggles.setOpaque(false);
		tierToggles.setAlignmentX(LEFT_ALIGNMENT);
		tierToggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ICON_CELL_SIZE));
		for (CaTier tier : CaTier.values())
		{
			tierToggles.add(tierToggle(tier));
		}
		tierFilterRow.add(tierToggles);
		tierFilterRow.add(Box.createVerticalStrut(4));
		tierFilterRow.setVisible(false);
		add(tierFilterRow);
		views.onChange(i ->
		{
			openTier = null;
			openBoss = null;
			bossGridPage = 0;
			rebuildContent();
		});

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);

		add(Box.createVerticalStrut(6));
		add(browserHeader());
		browserSlot.setLayout(new BoxLayout(browserSlot, BoxLayout.Y_AXIS));
		browserSlot.setOpaque(false);
		browserSlot.setAlignmentX(LEFT_ALIGNMENT);
		add(browserSlot);
		add(Box.createVerticalGlue());
		browser = new CaTaskBrowser(module, state, config, theme);
		browser.filtersFrom(this);
		browser.onShowBoss(boss ->
		{
			views.setSelected(1);
			openTier = null;
			openBoss = boss;
			rebuildContent();
		});

		state.addListener(listener);
		rebuildAll();
	}

	void dispose()
	{
		browser.dispose();
		state.removeListener(listener);
	}

	/** Module callback after the catalog (re)loads on the client thread. */
	void onTasksUpdated()
	{
		browser.onTasksUpdated();
		rebuildAll();
	}

	private void onStateChanged()
	{
		List<Object> print = fingerprint();
		if (!print.equals(lastPrint))
		{
			rebuildAll();
		}
	}

	private List<Object> fingerprint()
	{
		List<Object> print = new ArrayList<>();
		print.add(module.points());
		print.add(module.tasks().size());
		print.add(module.bosses().size());
		print.add(module.tasks().stream().filter(t -> t.completed).count());
		print.add(openTier);
		print.add(openBoss);
		print.add(bossGridPage);
		print.add(completedFilter.state());
		print.add(typeFilter.selected());
		print.add(new ArrayList<>(tierEnabled.values()));
		print.add(browserExpanded);
		print.add(profileExpanded);
		return print;
	}

	/** Wiki tier icon toggle; select fill + bevel = tier shown — the
	 *  browser's original filter, relocated (Luke, 2026-07-27). */
	private JLabel tierToggle(CaTier tier)
	{
		JLabel toggle = new JLabel(CaTaskBrowser.tierIcon(tier));
		toggle.setOpaque(true);
		toggle.setHorizontalAlignment(JLabel.CENTER);
		toggle.setToolTipText(tier.display + " tier (click to show/hide)");
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		Runnable style = () ->
		{
			boolean on = tierEnabled.get(tier);
			toggle.setBackground(on ? theme.selectFill : theme.recess);
			// MatteBorder fills strips — a drawRect border halves on Retina
			toggle.setBorder(new javax.swing.border.MatteBorder(1, 1, 1, 1,
				on ? theme.selectEdge : theme.edgeDark));
		};
		style.run();
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				tierEnabled.put(tier, !tierEnabled.get(tier));
				style.run();
				filtersChanged();
			}
		});
		return toggle;
	}

	/** A shared filter moved — both the grids and the browser re-run. */
	private void filtersChanged()
	{
		rebuildContent();
		if (browser != null)
		{
			browser.refilter();
		}
	}

	private void rebuildAll()
	{
		lastPrint = fingerprint();
		rebuildHero();
		rebuildProfile();
		rebuildContent();
	}

	// ── hero ──────────────────────────────────────────────────────────

	/**
	 * Points between the reward you have and the one you are working toward.
	 * The thresholds are the game's own CA_THRESHOLD varbits, so a tier that
	 * has never been seen reads as unknown rather than as zero.
	 */
	private void rebuildHero()
	{
		int points = module.points();
		CaTier next = CombatAchievementsModule.nextTier(state);
		CaTier reached = previousTier(next);
		int ceiling = next == null ? points : state.getVarbit(next.thresholdVarbit);

		// the clog reference shape (Luke, 2026-07-27): the headline is the
		// WHOLE ladder's points in orange, the tier-band count rides the
		// sprite bar in white, the flanking rewards sit plain and in-line
		int totalPossible = module.tasks().stream().mapToInt(t -> t.tier.points).sum();
		hero.removeAll();
		JPanel top = row();
		top.add(hilt(reached));
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Combat Task Points", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(totalPossible > 0
			? String.format(Locale.ROOT, "%,d / %,d", points, totalPossible)
			: String.format(Locale.ROOT, "%,d", points),
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(hilt(next));
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it — the
		// banded fraction sat near zero just past a threshold and read as
		// an all-grey bar under a "214 / 304" label (Luke, 2026-07-27)
		heroBar.fraction(ceiling > 0 ? Math.min(1, (double) points / ceiling) : 1);
		heroBar.labels("", ceiling > 0
			? String.format(Locale.ROOT, "%,d / %,d", points, ceiling)
			: String.format(Locale.ROOT, "%,d", points), "");
		hero.add(heroBar);

		JPanel labels = row();
		labels.add(hiltLabel(reached, "No tier yet"));
		labels.add(Box.createHorizontalGlue());
		labels.add(hiltLabel(next, "Every tier complete"));
		cap(labels);
		hero.add(labels);
		cap(hero);
		hero.revalidate();
		hero.repaint();
	}

	/** The tier below the one being worked toward — the reward in hand. */
	private static CaTier previousTier(CaTier next)
	{
		if (next == null)
		{
			return CaTier.GRANDMASTER;
		}
		int ordinal = next.ordinal();
		return ordinal == 0 ? null : CaTier.values()[ordinal - 1];
	}

	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI"};

	/** "IV: Hard" — the numeral orange, the name white (the clog rank
	 *  labels' grammar; Luke, 2026-07-27). */
	private JComponent hiltLabel(CaTier tier, String fallback)
	{
		if (tier == null)
		{
			return new OsrsLabel(fallback, OsrsSkin.MUTED, OsrsSkin.smallFont());
		}
		JPanel pair = row();
		pair.add(new OsrsLabel(ROMAN[tier.ordinal()] + ": ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		pair.add(new OsrsLabel(tier.display,
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		pair.setMaximumSize(pair.getPreferredSize());
		return pair;
	}

	/** A tier's Ghommal's hilt, the reward it hands over. */
	private JComponent hilt(CaTier tier)
	{
		JLabel icon = new JLabel();
		if (tier == null)
		{
			icon.setPreferredSize(new Dimension(24, 30));
			icon.setToolTipText("The Easy tier hands over Ghommal's hilt 1");
			return icon;
		}
		Image sprite = OsrsIcons.image(theme, "cahilt/hilt" + (tier.ordinal() + 1));
		if (sprite != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		else
		{
			icon.setPreferredSize(new Dimension(24, 30));
		}
		icon.setToolTipText("Ghommal's hilt " + (tier.ordinal() + 1)
			+ " — the " + tier.display + " tier reward");
		return icon;
	}

	// ── the Combat Profile ────────────────────────────────────────────

	/** The interface's own seven rows on an EXPANDABLE Card — the Goals
	 *  sections' simple triangle, never a chevron (Luke, 2026-07-27). */
	private void rebuildProfile()
	{
		profile.removeAll();
		String player = state.playerName();
		JPanel head = row();
		JLabel triangle = new JLabel(new PaintedIcon(profileExpanded
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		head.add(triangle);
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		head.add(new OsrsLabel("Combat Profile" + (player == null || player.isEmpty()
			? "" : " - " + player), OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		head.add(Box.createHorizontalGlue());
		cap(head);
		profile.add(head);
		if (profileExpanded)
		{
			profile.add(Box.createVerticalStrut(2));
			List<CaProfile.Row> rows = module.profileRows();
			if (rows.isEmpty())
			{
				profile.add(new OsrsLabel("Log in to read your combat stats",
					OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
			}
			for (CaProfile.Row entry : rows)
			{
				JPanel line = row();
				line.add(new OsrsLabel(entry.label, OsrsSkin.MUTED, OsrsSkin.smallFont())
					.leftAligned().squeezable());
				line.add(Box.createHorizontalGlue());
				line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
				line.add(new OsrsLabel(entry.value, OsrsSkin.BAR_TEXT, OsrsSkin.smallFont()));
				cap(line);
				profile.add(line);
			}
		}
		cap(profile);
		profile.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			profile.getPreferredSize().height));
		profile.revalidate();
		profile.repaint();
	}

	// ── the two views ─────────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		// visibility follows the VIEW however it was switched — chip click,
		// the browser's boss jump, or a test seam
		tierFilterRow.setVisible(views.selected() == 1);
		List<CaTask> tasks = module.tasks();
		if (tasks.isEmpty())
		{
			content.add(note("Log in to load your combat tasks."));
		}
		else if (views.selected() == 1)
		{
			// the open boss expands in-line inside the grid
			bossGrid(tasks);
		}
		else
		{
			tierGrid(tasks);
		}
		content.revalidate();
		content.repaint();
	}

	/** The six tiers as 2-wide square Cards (the clog page grid's grammar;
	 *  Luke, 2026-07-27), the open tier's tasks expanding IN-LINE below its
	 *  own row — one at a time, a second click closes. */
	private void tierGrid(List<CaTask> tasks)
	{
		Map<CaTier, int[]> counts = tierStats(tasks);
		// a card with nothing behind the filters does not show (Luke,
		// 2026-07-27)
		List<CaTier> tiers = new ArrayList<>();
		for (CaTier tier : CaTier.values())
		{
			if (tasks.stream().anyMatch(t -> t.tier == tier && filterPasses(t, false)))
			{
				tiers.add(tier);
			}
		}
		if (tiers.isEmpty())
		{
			content.add(note("No combat tasks match the filters."));
			return;
		}
		for (int start = 0; start < tiers.size(); start += TIER_COLUMNS)
		{
			JPanel line = row();
			line.add(Box.createHorizontalGlue());
			for (int col = 0; col < TIER_COLUMNS && start + col < tiers.size(); col++)
			{
				if (col > 0)
				{
					line.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				CaTier tier = tiers.get(start + col);
				line.add(pageTile(tier.display, tierEmblem(tier), TIER_TILE,
					counts.getOrDefault(tier, new int[2]), tier == openTier, () ->
					{
						openTier = tier == openTier ? null : tier;
						openBoss = null;
						rebuildContent();
					}));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			for (int col = 0; col < TIER_COLUMNS && start + col < tiers.size(); col++)
			{
				if (tiers.get(start + col) == openTier)
				{
					tierDetail(tasks);
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
	}

	/** The interface's boss grid, three-across square Cards in the game's
	 *  own order, expanding IN-LINE the same way. */
	private void bossGrid(List<CaTask> tasks)
	{
		Map<String, int[]> stats = bossStats(tasks);
		List<CaBoss> bosses = module.bosses();
		List<String> names = new ArrayList<>();
		if (bosses.isEmpty())
		{
			// no cache read yet: fall back to the bosses the tasks name, so
			// the grid is never empty when we plainly have tasks
			names.addAll(stats.keySet());
		}
		else
		{
			for (CaBoss boss : bosses)
			{
				names.add(boss.name);
			}
		}
		if (names.isEmpty())
		{
			content.add(note("No bosses to show yet."));
			return;
		}
		// a card with nothing behind the filters does not show (Luke,
		// 2026-07-27: no Chambers of Xeric when only Easy is on)
		names.removeIf(name -> tasks.stream()
			.noneMatch(t -> name.equals(t.boss) && filterPasses(t, true)));
		if (names.isEmpty())
		{
			content.add(note("No bosses match the filters."));
			return;
		}
		// paginated: 10 rows of 2 per page, arrows below (Luke, 2026-07-27)
		int perPage = BOSS_PAGE_ROWS * BOSS_COLUMNS;
		int pages = (names.size() + perPage - 1) / perPage;
		bossGridPage = Math.max(0, Math.min(bossGridPage, pages - 1));
		int from = bossGridPage * perPage;
		List<String> shown = names.subList(from, Math.min(from + perPage, names.size()));
		for (int start = 0; start < shown.size(); start += BOSS_COLUMNS)
		{
			JPanel line = row();
			line.add(Box.createHorizontalGlue());
			for (int col = 0; col < BOSS_COLUMNS && start + col < shown.size(); col++)
			{
				if (col > 0)
				{
					line.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				String name = shown.get(start + col);
				line.add(pageTile(name, bossEmblem(name), BOSS_TILE,
					stats.getOrDefault(name, new int[2]), name.equals(openBoss), () ->
					{
						openBoss = name.equals(openBoss) ? null : name;
						openTier = null;
						rebuildContent();
					}));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			for (int col = 0; col < BOSS_COLUMNS && start + col < shown.size(); col++)
			{
				if (shown.get(start + col).equals(openBoss))
				{
					bossDetail(tasks);
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
		if (pages > 1)
		{
			JPanel pager = row();
			pager.add(Box.createHorizontalGlue());
			pager.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
				com.ironhub.ui.v2.V2SpriteButton.ARROW_LEFT, () ->
				{
					if (bossGridPage > 0)
					{
						bossGridPage--;
						rebuildContent();
					}
				}));
			pager.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			pager.add(new OsrsLabel("Page " + (bossGridPage + 1) + "/" + pages,
				OsrsSkin.MUTED, OsrsSkin.smallFont()));
			pager.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			pager.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
				com.ironhub.ui.v2.V2SpriteButton.ARROW_RIGHT, () ->
				{
					if (bossGridPage < pages - 1)
					{
						bossGridPage++;
						rebuildContent();
					}
				}));
			pager.add(Box.createHorizontalGlue());
			cap(pager);
			content.add(pager);
		}
	}

	/** One square Card in the clog page-grid grammar: bold inside caption
	 *  on the orange/green scale, corner count in the shared colours, a
	 *  plain meter strip, no tooltip. */
	private V2Tile pageTile(String caption, Image emblem, int size, int[] stat,
		boolean expanded, Runnable onPress)
	{
		boolean complete = stat[1] > 0 && stat[0] >= stat[1];
		Color cornerOwned = complete ? V2Tokens.DONE
			: stat[0] == 0 ? V2Tokens.BLOCKED : V2Tokens.ACTION;
		Color cornerRest = complete ? V2Tokens.DONE : V2Tokens.ACTION;
		return new V2Tile(theme, emblem, caption, size, onPress)
			.card().captionLines(2).captionInside()
			.captionStatus(complete ? V2Tokens.DONE : V2Tokens.ACTION)
			.corner(String.valueOf(stat[0]), cornerOwned, "/" + stat[1], cornerRest)
			.selected(expanded)
			.meter(stat[1] == 0 ? Double.NaN : (double) stat[0] / stat[1]);
	}

	// ── the results a tile expands in-line (the clog grammar) ─────────

	private void tierDetail(List<CaTask> tasks)
	{
		List<CaTask> mine = new ArrayList<>();
		List<CaTask> shown = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (task.tier == openTier)
			{
				mine.add(task);
				if (filterPasses(task, false))
				{
					shown.add(task);
				}
			}
		}
		// the header counts the TIER, the list obeys the filters
		content.add(pageHeader(openTier.display, mine,
			openTier.points + " points per task"));
		addTaskRows(shown);
	}

	private void bossDetail(List<CaTask> tasks)
	{
		List<CaTask> mine = new ArrayList<>();
		List<CaTask> shown = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (openBoss.equals(task.boss))
			{
				mine.add(task);
				if (filterPasses(task, true))
				{
					shown.add(task);
				}
			}
		}
		CaBoss boss = bossByName(openBoss);
		StringBuilder sub = new StringBuilder();
		if (boss != null && boss.level > 0)
		{
			sub.append("Combat level ").append(boss.level);
		}
		int kills = boss == null ? -1
			: CaProfile.killCount(state, module.profilePack(), boss.index);
		if (kills >= 0)
		{
			if (sub.length() > 0)
			{
				sub.append(" · ");
			}
			sub.append(CaProfile.count(kills)).append(" kills");
		}
		content.add(pageHeader(openBoss, mine, sub.toString()));
		addTaskRows(shown);
	}

	/** Name, "Tasks Completed: n/N" in the game's colour scale, and a line
	 *  of context beneath. */
	private JComponent pageHeader(String name, List<CaTask> tasks, String sub)
	{
		int done = (int) tasks.stream().filter(t -> t.completed).count();
		V2Surface card = V2Surface.card(theme);
		OsrsLabel title = new OsrsLabel(name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		card.add(title);
		// the clog counter grammar (Luke, 2026-07-27): orange label, value
		// red at 0 / counter-yellow partial / green done
		Color colour = tasks.isEmpty() || done == 0 ? V2Tokens.BLOCKED
			: done >= tasks.size() ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		JPanel counts = row();
		counts.add(new OsrsLabel("Tasks Completed: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		counts.add(new OsrsLabel(done + "/" + tasks.size(),
			colour, OsrsSkin.smallFont()).leftAligned());
		counts.add(Box.createHorizontalGlue());
		cap(counts);
		card.add(counts);
		if (sub != null && !sub.isEmpty())
		{
			card.add(new OsrsLabel(sub, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		cap(card);
		return card;
	}

	/** Every achievement under the open tile: tier icon, name, description. */
	private void addTaskRows(List<CaTask> tasks)
	{
		if (tasks.isEmpty())
		{
			content.add(note("No combat tasks here yet."));
			return;
		}
		List<CaTask> sorted = new ArrayList<>(tasks);
		sorted.sort(Comparator.<CaTask>comparingInt(t -> t.tier.ordinal())
			.thenComparing(t -> t.name));
		int limit = Math.min(MAX_TASKS, sorted.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(taskRow(sorted.get(i)));
		}
		if (limit < sorted.size())
		{
			content.add(note("+ " + (sorted.size() - limit)
				+ " more — the task list below searches them all"));
		}
	}

	private JComponent taskRow(CaTask task)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(true);
		card.setBackground(theme.background);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));

		JPanel head = row();
		Image icon = tierIcon(task.tier);
		if (icon != null)
		{
			JLabel holder = new JLabel(new javax.swing.ImageIcon(icon));
			holder.setToolTipText(task.tier.display);
			head.add(holder);
			head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		OsrsLabel name = new OsrsLabel(task.name,
			task.completed ? OsrsSkin.VALUE : OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		name.setToolTipText(task.name + " · " + task.tier.display + " · " + task.type);
		head.add(name);
		head.add(Box.createHorizontalGlue());
		cap(head);
		card.add(head);
		card.add(OsrsLabel.wrapped(task.description, WRAP,
			task.completed ? OsrsSkin.FAINT : OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned());

		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText("Open the wiki page for " + task.name);
		clickAnywhere(card, new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(theme.hoverFill);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(theme.background);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse(task.wikiUrl());
			}
		});
		cap(card);
		return card;
	}

	// ── the task browser, folded away at the foot ─────────────────────

	/** "All tasks" as the clog's Easiest-next-slots shape (Luke,
	 *  2026-07-27): a pressable TEXT-ONLY Card — subtle wash on hover, the
	 *  hovered art pinned while the browser is open, no chevron, no
	 *  tooltip — with the browser mounting below it. */
	private JComponent browserHeader()
	{
		browserCard = V2Surface.card(theme);
		browserCard.setAlignmentX(LEFT_ALIGNMENT);
		JPanel head = row();
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel("All tasks", OsrsSkin.TITLE, OsrsSkin.boldFont()));
		head.add(Box.createHorizontalGlue());
		cap(head);
		browserCard.add(head);
		browserCard.washHoverable();
		browserCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		com.ironhub.ui.v2.MouseRelay.install(browserCard);
		browserCard.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleBrowser();
			}
		});
		cap(browserCard);
		return browserCard;
	}

	private void toggleBrowser()
	{
		browserExpanded = !browserExpanded;
		browserCard.setLit(browserExpanded);
		browserSlot.removeAll();
		if (browserExpanded)
		{
			browserSlot.add(browser);
		}
		browserSlot.revalidate();
		browserSlot.repaint();
		revalidate();
		repaint();
	}

	// ── stats ─────────────────────────────────────────────────────────

	/** Boss → [completed, total], insertion-ordered by boss name. */
	static Map<String, int[]> bossStats(List<CaTask> tasks)
	{
		Map<String, int[]> stats = new LinkedHashMap<>();
		List<CaTask> sorted = new ArrayList<>(tasks);
		sorted.sort(Comparator.comparing(t -> t.boss));
		for (CaTask task : sorted)
		{
			if (task.boss.isEmpty())
			{
				continue;
			}
			int[] counts = stats.computeIfAbsent(task.boss, k -> new int[2]);
			counts[1]++;
			if (task.completed)
			{
				counts[0]++;
			}
		}
		return stats;
	}

	/** Tier → [completed, total] over the loaded catalog. */
	static Map<CaTier, int[]> tierStats(List<CaTask> tasks)
	{
		Map<CaTier, int[]> stats = new java.util.EnumMap<>(CaTier.class);
		for (CaTask task : tasks)
		{
			int[] counts = stats.computeIfAbsent(task.tier, k -> new int[2]);
			counts[1]++;
			if (task.completed)
			{
				counts[0]++;
			}
		}
		return stats;
	}

	private CaBoss bossByName(String name)
	{
		for (CaBoss boss : module.bosses())
		{
			if (boss.name.equals(name))
			{
				return boss;
			}
		}
		return null;
	}

	private static Image tierIcon(CaTier tier)
	{
		javax.swing.ImageIcon icon = CaTaskBrowser.tierIcon(tier);
		return icon == null ? null : icon.getImage();
	}

	/** The game's own _large tier sword, never a resized small one (Luke,
	 *  2026-07-27). */
	private Image tierEmblem(CaTier tier)
	{
		return com.ironhub.ui.v2.V2Sprites.get(theme,
			"icons/combat_achievements/" + TIER_SWORDS[tier.ordinal()] + "_large");
	}

	/**
	 * The shared filters' verdict on one task. The tier filter applies only
	 * where the caller says so — the Difficulty cards already partition by
	 * tier, so their lists skip it (and the tab hides the dropdown there).
	 */
	boolean filterPasses(CaTask task, boolean applyTier)
	{
		// the Completed checkbox: checked = the finished tasks, unchecked =
		// the to-do list (Luke, 2026-07-27)
		boolean completed = completedFilter.state() == com.ironhub.ui.v2.V2Checkbox.State.ON;
		if (task.completed != completed)
		{
			return false;
		}
		String type = TYPE_OPTIONS[typeFilter.selected()];
		if (!"All types".equals(type) && !type.equals(task.type))
		{
			return false;
		}
		return !applyTier || tierEnabled.get(task.tier);
	}

	/** A boss card's emblem: the boss's own collection-log page's first
	 *  slot, when one matches by name — the clog cards' emblem source. */
	private Image bossEmblem(String boss)
	{
		for (com.ironhub.state.PersistedState.ClogTab tab : state.getClogCatalog())
		{
			for (com.ironhub.state.PersistedState.ClogPage page : tab.pages)
			{
				if (page.name.equalsIgnoreCase(boss) && page.items.length > 0)
				{
					return sprites.getBox(page.items[0], PAGE_EMBLEM);
				}
			}
		}
		return null;
	}

	// ── test seams ────────────────────────────────────────────────────

	void showBossesForTest()
	{
		views.setSelected(1);
		openTier = null;
		openBoss = null;
		rebuildContent();
	}

	void drillForTest(String boss)
	{
		views.setSelected(1);
		openBoss = boss;
		openTier = null;
		rebuildContent();
	}

	void openTierForTest(CaTier tier)
	{
		views.setSelected(0);
		openTier = tier;
		openBoss = null;
		rebuildContent();
	}

	void expandBrowserForTest()
	{
		if (!browserExpanded)
		{
			toggleBrowser();
		}
	}

	CaTaskBrowser browserForTest()
	{
		return browser;
	}

	// ── layout helpers ────────────────────────────────────────────────

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private static JComponent note(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, WRAP, OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (java.awt.Component child : container.getComponents())
		{
			child.addMouseListener(click);
			if (child instanceof JComponent)
			{
				for (java.awt.Component inner : ((JComponent) child).getComponents())
				{
					inner.addMouseListener(click);
				}
			}
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
