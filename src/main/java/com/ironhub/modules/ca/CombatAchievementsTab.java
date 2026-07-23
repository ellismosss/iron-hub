package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
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
	/** Grid geometry, measured against the 225px panel's 217px of content. */
	private static final int TIER_COLUMNS = 2;
	private static final int TIER_WIDTH = 107;
	private static final int TIER_HEIGHT = 40;
	private static final int BOSS_COLUMNS = 3;
	private static final int BOSS_WIDTH = 69;
	private static final int BOSS_HEIGHT = 48;
	private static final int GRID_GAP = 3;
	/** Row ceiling for a page's task list (the Bank tab's grammar). */
	private static final int MAX_TASKS = 50;
	private static final int WRAP = 185;

	private final CombatAchievementsModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = RebuildGate.install(this, this::onStateChanged);

	private final StonePanel hero;
	private final StoneProgressBar heroBar;
	private final StonePanel profile;
	private final StoneChipRow views;
	private final JPanel content = new JPanel();
	private final JPanel browserSlot = new JPanel();
	private final JLabel browserTriangle;
	private final CaTaskBrowser browser;

	/** null = the grid; otherwise the tier or boss whose page is open. */
	private CaTier openTier;
	private String openBoss;
	private boolean browserExpanded;
	private List<Object> lastPrint = List.of();

	CombatAchievementsTab(CombatAchievementsModule module, AccountState state,
		IronHubConfig config, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		heroBar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(hero);
		add(Box.createVerticalStrut(4));

		profile = new StonePanel(theme);
		profile.setLayout(new BoxLayout(profile, BoxLayout.Y_AXIS));
		profile.setAlignmentX(LEFT_ALIGNMENT);
		add(profile);
		add(Box.createVerticalStrut(4));

		views = new StoneChipRow(theme, true, "Difficulty", "Bosses");
		views.onChange(i ->
		{
			openTier = null;
			openBoss = null;
			rebuildContent();
		});
		add(views);
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);

		browserTriangle = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		browserTriangle.setForeground(OsrsSkin.MUTED);
		add(Box.createVerticalStrut(6));
		add(browserHeader());
		browserSlot.setLayout(new BoxLayout(browserSlot, BoxLayout.Y_AXIS));
		browserSlot.setOpaque(false);
		browserSlot.setAlignmentX(LEFT_ALIGNMENT);
		add(browserSlot);
		add(Box.createVerticalGlue());
		browser = new CaTaskBrowser(module, state, config, theme);
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
		print.add(browserExpanded);
		return print;
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
		int floor = reached == null ? 0 : state.getVarbit(reached.thresholdVarbit);
		int ceiling = next == null ? Math.max(points, floor)
			: state.getVarbit(next.thresholdVarbit);

		hero.removeAll();
		JPanel top = row();
		top.add(hilt(reached));
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Combat Task Points", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(ceiling > 0
			? String.format(Locale.ROOT, "%,d / %,d", points, ceiling)
			: String.format(Locale.ROOT, "%,d", points),
			OsrsSkin.VALUE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(hilt(next));
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		heroBar.setFraction(ceiling > floor ? (double) (points - floor) / (ceiling - floor) : 1);
		heroBar.setAlignmentX(LEFT_ALIGNMENT);
		hero.add(heroBar);

		JPanel labels = row();
		labels.add(new OsrsLabel(reached == null ? "No tier yet" : rewardLabel(reached),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		labels.add(Box.createHorizontalGlue());
		labels.add(new OsrsLabel(next == null ? "Every tier complete" : rewardLabel(next),
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
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

	private static String rewardLabel(CaTier tier)
	{
		return tier.display + " · hilt " + (tier.ordinal() + 1);
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

	/** The interface's own seven rows, under the player's name. */
	private void rebuildProfile()
	{
		profile.removeAll();
		String player = state.playerName();
		profile.add(new OsrsLabel("Combat Profile" + (player == null || player.isEmpty()
			? "" : " - " + player), OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
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
		cap(profile);
		profile.revalidate();
		profile.repaint();
	}

	// ── the two views ─────────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		List<CaTask> tasks = module.tasks();
		if (tasks.isEmpty())
		{
			content.add(note("Log in to load your combat tasks."));
		}
		else if (openTier != null)
		{
			tierPage(tasks);
		}
		else if (openBoss != null)
		{
			bossPage(tasks);
		}
		else if (views.getSelected() == 1)
		{
			bossGrid(tasks);
		}
		else
		{
			tierGrid(tasks);
		}
		content.revalidate();
		content.repaint();
	}

	/** The six tiers, two across, each with its fill bar. */
	private void tierGrid(List<CaTask> tasks)
	{
		Map<CaTier, int[]> counts = tierStats(tasks);
		List<JComponent> tiles = new ArrayList<>();
		for (CaTier tier : CaTier.values())
		{
			int[] stat = counts.getOrDefault(tier, new int[2]);
			tiles.add(new CaProgressTile(theme, tierIcon(tier), tier.display,
				stat[0] + "/" + stat[1], stat[0], stat[1], TIER_WIDTH, TIER_HEIGHT,
				tier.display + " · " + stat[0] + "/" + stat[1] + " tasks · "
					+ tier.points + " points each",
				() ->
				{
					openTier = tier;
					rebuildContent();
				}));
		}
		addGrid(tiles, TIER_COLUMNS);
	}

	/** The interface's boss grid: three across, in the game's own order. */
	private void bossGrid(List<CaTask> tasks)
	{
		Map<String, int[]> stats = bossStats(tasks);
		List<CaBoss> bosses = module.bosses();
		List<JComponent> tiles = new ArrayList<>();
		if (bosses.isEmpty())
		{
			// no cache read yet: fall back to the bosses the tasks name, so
			// the grid is never empty when we plainly have tasks
			for (Map.Entry<String, int[]> entry : stats.entrySet())
			{
				tiles.add(bossTile(entry.getKey(), 0, -1, entry.getValue()));
			}
		}
		else
		{
			for (CaBoss boss : bosses)
			{
				int[] stat = stats.getOrDefault(boss.name, new int[2]);
				tiles.add(bossTile(boss.name, boss.level,
					CaProfile.killCount(state, module.profilePack(), boss.index), stat));
			}
		}
		if (tiles.isEmpty())
		{
			content.add(note("No bosses to show yet."));
			return;
		}
		addGrid(tiles, BOSS_COLUMNS);
	}

	private JComponent bossTile(String name, int level, int kills, int[] stat)
	{
		StringBuilder tip = new StringBuilder(name);
		tip.append(" · ").append(stat[0]).append('/').append(stat[1]).append(" tasks");
		if (level > 0)
		{
			tip.append(" · combat level ").append(level);
		}
		if (kills > 0)
		{
			tip.append(" · ").append(CaProfile.count(kills)).append(" kills");
		}
		return new CaProgressTile(theme, null, name,
			level > 0 ? "Level: " + level : "Level: N/A", stat[0], stat[1],
			BOSS_WIDTH, BOSS_HEIGHT, tip.toString(),
			() ->
			{
				openBoss = name;
				rebuildContent();
			});
	}

	private void addGrid(List<JComponent> tiles, int columns)
	{
		for (int i = 0; i < tiles.size(); i += columns)
		{
			JPanel line = row();
			for (int column = 0; column < columns && i + column < tiles.size(); column++)
			{
				if (column > 0)
				{
					line.add(Box.createHorizontalStrut(GRID_GAP));
				}
				line.add(tiles.get(i + column));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(GRID_GAP));
		}
	}

	// ── the pages a tile opens ────────────────────────────────────────

	private void tierPage(List<CaTask> tasks)
	{
		List<CaTask> mine = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (task.tier == openTier)
			{
				mine.add(task);
			}
		}
		content.add(backRow("Difficulty", () -> openTier = null));
		content.add(pageHeader(openTier.display, mine,
			openTier.points + " points per task"));
		addTaskRows(mine);
	}

	private void bossPage(List<CaTask> tasks)
	{
		List<CaTask> mine = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (openBoss.equals(task.boss))
			{
				mine.add(task);
			}
		}
		content.add(backRow("Bosses", () -> openBoss = null));
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
		addTaskRows(mine);
	}

	/** Name, "Tasks Completed: n/N" in the game's colour scale, and a line
	 *  of context beneath. */
	private JComponent pageHeader(String name, List<CaTask> tasks, String sub)
	{
		int done = (int) tasks.stream().filter(t -> t.completed).count();
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		OsrsLabel title = new OsrsLabel(name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		title.setToolTipText(name);
		card.add(title);
		Color colour = tasks.isEmpty() || done == 0 ? OsrsSkin.FAINT
			: done >= tasks.size() ? OsrsSkin.VALUE : OsrsSkin.TITLE;
		card.add(new OsrsLabel("Tasks Completed: " + done + "/" + tasks.size(),
			colour, OsrsSkin.font()).leftAligned());
		if (sub != null && !sub.isEmpty())
		{
			card.add(new OsrsLabel(sub, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		cap(card);
		return card;
	}

	private JComponent backRow(String target, Runnable close)
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
				close.run();
				rebuildContent();
			}
		});
		return row;
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

	private JComponent browserHeader()
	{
		StonePanel plate = new StonePanel(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		plate.add(browserTriangle);
		plate.add(Box.createHorizontalGlue());
		plate.add(new OsrsLabel("All tasks", OsrsSkin.TITLE, OsrsSkin.boldFont()));
		plate.add(Box.createHorizontalGlue());
		plate.add(Box.createHorizontalStrut(10));
		plate.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		plate.setToolTipText("Search, filter and track every combat task");
		plate.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleBrowser();
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

	private void toggleBrowser()
	{
		browserExpanded = !browserExpanded;
		browserTriangle.setIcon(new PaintedIcon(browserExpanded
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
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
