package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.LinkBrowser;

/**
 * Combat achievements tab in the OSRS stonework skin: goal-aware stats card,
 * All/Goals/Bosses views, search, collapsible filters (tier toggles, status,
 * type, sort), flat hoverable task rows that expand into stone cards with
 * per-task tracking, and a two-column boss grid that drills into each boss's
 * tasks. Feature parity with the Combat Achievements Tracker hub plugin —
 * only the clothing changed. Frameless: the hub host provides the frame.
 */
class CombatAchievementsTab extends JPanel
{
	private static final String[] STATUS_OPTIONS = {"All", "Completed", "Incomplete"};
	private static final String[] TYPE_OPTIONS = {"All types", "Stamina", "Perfection",
		"Kill Count", "Mechanical", "Restriction", "Speed"};
	private static final String[] SORT_OPTIONS = {"Tier", "Name", "Completion", "Community %"};
	/** Wrap widths: free-standing notes vs text inside a stone card. */
	private static final int NOTE_WIDTH = 195;
	private static final int CARD_TEXT_WIDTH = 180;
	/** Marks a child with its own action so a row click never steals it. */
	private static final String OWN_ACTION = "ironhub.ca.ownAction";

	private final CombatAchievementsModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::onStateChanged);

	// stats card (OsrsLabel text is immutable — refreshStats refills the card)
	private final StonePanel statsCard;
	private final StoneProgressBar goalBar;

	// controls
	private final StoneChipRow views;
	private final StoneTextField search;
	private final JLabel filtersTriangle = triangle();
	private final JPanel filtersPanel = new JPanel();
	private final Map<CaTier, Boolean> tierEnabled = new EnumMap<>(CaTier.class);
	private final JComboBox<String> statusFilter = new JComboBox<>(STATUS_OPTIONS);
	private final JComboBox<String> typeFilter = new JComboBox<>(TYPE_OPTIONS);
	private final JComboBox<String> sortFilter = new JComboBox<>(SORT_OPTIONS);
	private final JLabel sortDirection;
	private boolean filtersExpanded;
	private boolean sortAscending = true;

	// content
	private final JPanel content = new JPanel();
	private String selectedBoss; // non-null = boss drill-down
	private final Set<Integer> expanded = new HashSet<>();
	private Set<String> lastCaGoals;
	private int lastPoints = -1;

	private static final Map<CaTier, ImageIcon> TIER_ICONS = loadTierIcons();

	CombatAchievementsTab(CombatAchievementsModule module, AccountState state,
		IronHubConfig config, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		for (CaTier tier : CaTier.values())
		{
			tierEnabled.put(tier, true);
		}

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(section("Progress"));
		statsCard = new StonePanel(theme);
		statsCard.setLayout(new BoxLayout(statsCard, BoxLayout.Y_AXIS));
		statsCard.setAlignmentX(LEFT_ALIGNMENT);
		goalBar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		goalBar.setAlignmentX(LEFT_ALIGNMENT);
		add(pad(statsCard));
		add(strut(6));

		views = new StoneChipRow(theme, true, "All", "Goals", "Bosses");
		views.onChange(i ->
		{
			selectedBoss = null;
			rebuildContent();
		});
		add(pad(views));
		add(strut(4));

		search = new StoneTextField(theme, "Search tasks…");
		add(pad(search));
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				rebuildContent();
			}

			public void removeUpdate(DocumentEvent e)
			{
				rebuildContent();
			}

			public void changedUpdate(DocumentEvent e)
			{
				rebuildContent();
			}
		});
		add(strut(4));

		sortDirection = glyph(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_UP, 10),
			"Flip sort direction", this::flipSortDirection);
		add(buildFiltersSection());
		add(strut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		content.setBorder(new EmptyBorder(0, 4, 0, 4));
		add(content);
		add(Box.createVerticalGlue());

		lastCaGoals = selectedCaGoals();
		state.addListener(listener);
		refreshStats();
		rebuildContent();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Module callback after the catalog (re)loads on the client thread. */
	void onTasksUpdated()
	{
		refreshStats();
		rebuildContent();
	}

	/** Test seam: switch to the Bosses grid without a mouse. */
	void showBossesForTest()
	{
		views.setSelected(2);
		selectedBoss = null;
		rebuildContent();
	}

	/** Test seam: expand a task's card. */
	void expandForTest(int taskId)
	{
		expanded.add(taskId);
		rebuildContent();
	}

	/** Test seam: open the filters section. */
	void expandFiltersForTest()
	{
		filtersExpanded = true;
		filtersTriangle.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		filtersPanel.setVisible(true);
	}

	/** Test seam: drill into a boss's tasks. */
	void drillForTest(String boss)
	{
		views.setSelected(2);
		selectedBoss = boss;
		rebuildContent();
	}

	/** Cheap path for AccountState notifications: stats always, rows only
	 * when points or the set of CA goals actually changed. */
	private void onStateChanged()
	{
		// only rebuild when something CA-relevant moved — refreshStats alone
		// is three full ~637-task passes, and it ran on EVERY coalesced
		// state change (2026-07-20 audit)
		Set<String> caGoals = selectedCaGoals();
		int points = module.points();
		if (!caGoals.equals(lastCaGoals) || points != lastPoints)
		{
			lastCaGoals = caGoals;
			lastPoints = points;
			refreshStats();
			rebuildContent();
		}
	}

	/** The "ca:" goal ids currently selected in the goal planner. */
	private Set<String> selectedCaGoals()
	{
		Set<String> ids = new HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("ca:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	private boolean isGoal(CaTask task)
	{
		return state.getSelectedGoals().contains("ca:" + task.id);
	}

	// ── stats card ────────────────────────────────────────────────────

	private void refreshStats()
	{
		List<CaTask> tasks = module.tasks();
		int points = module.points();
		int totalPoints = tasks.stream().mapToInt(t -> t.tier.points).sum();
		long done = tasks.stream().filter(t -> t.completed).count();
		String pointsText = tasks.isEmpty()
			? points + " pts"
			: points + "/" + totalPoints + " pts · " + done + "/" + tasks.size() + " tasks";

		CaTier goal = module.goalTier();
		int threshold = module.goalThreshold();
		String goalText;
		Color goalColor;
		if (goal == null)
		{
			goalText = "All tiers complete!";
			goalColor = OsrsSkin.VALUE;
			goalBar.setFraction(1);
		}
		else if (points >= threshold && threshold > 0)
		{
			goalText = goal.display + " complete! (" + points + " pts)";
			goalColor = OsrsSkin.VALUE;
			goalBar.setFraction(1);
		}
		else
		{
			goalText = threshold > 0
				? (threshold - points) + " pts to " + goal.display
				: "Goal: " + goal.display;
			goalColor = OsrsSkin.TITLE;
			goalBar.setFraction(threshold == 0 ? 0 : (double) points / threshold);
		}

		String trackedText;
		if (selectedCaGoals().isEmpty())
		{
			trackedText = "No CA goals yet";
		}
		else
		{
			int goalPts = 0;
			int goalDonePts = 0;
			int found = 0;
			for (CaTask task : tasks)
			{
				if (isGoal(task))
				{
					found++;
					goalPts += task.tier.points;
					if (task.completed)
					{
						goalDonePts += task.tier.points;
					}
				}
			}
			trackedText = "Goals: " + goalDonePts + "/" + goalPts + " pts (" + found + " tasks)";
		}

		statsCard.removeAll();
		statsCard.add(new OsrsLabel(pointsText, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		statsCard.add(strut(3));
		statsCard.add(goalBar);
		statsCard.add(strut(3));
		OsrsLabel goalLine = new OsrsLabel(goalText, goalColor, OsrsSkin.font())
			.leftAligned().squeezable();
		goalLine.setToolTipText("Tier goal - set it under Iron Hub settings (Auto advances tier by tier)");
		statsCard.add(goalLine);
		statsCard.add(new OsrsLabel(trackedText, OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable());
		cap(statsCard);
		statsCard.revalidate();
		statsCard.repaint();
	}

	// ── filters ───────────────────────────────────────────────────────

	private JPanel buildFiltersSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		section.add(collapsibleHeader(filtersTriangle, "Filters",
			"Show or hide the tier, status, type and sort filters", () ->
		{
			filtersExpanded = !filtersExpanded;
			filtersTriangle.setIcon(new PaintedIcon(filtersExpanded
				? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
			filtersPanel.setVisible(filtersExpanded);
			revalidate();
			repaint();
		}));

		filtersPanel.setLayout(new BoxLayout(filtersPanel, BoxLayout.Y_AXIS));
		filtersPanel.setOpaque(false);
		filtersPanel.setAlignmentX(LEFT_ALIGNMENT);
		filtersPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 4, 0, 4));
		filtersPanel.setVisible(false);

		JPanel tierRow = new JPanel(new GridLayout(1, CaTier.values().length, 2, 0));
		tierRow.setOpaque(false);
		tierRow.setAlignmentX(LEFT_ALIGNMENT);
		tierRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ICON_CELL_SIZE));
		for (CaTier tier : CaTier.values())
		{
			tierRow.add(tierToggle(tier));
		}
		filtersPanel.add(tierRow);
		filtersPanel.add(strut(UiTokens.PAD_TIGHT));

		filtersPanel.add(filterRow("Status", combo(statusFilter)));
		filtersPanel.add(strut(UiTokens.PAD_TIGHT));
		filtersPanel.add(filterRow("Type", combo(typeFilter)));
		filtersPanel.add(strut(UiTokens.PAD_TIGHT));
		JPanel sortControls = new JPanel(new BorderLayout(UiTokens.PAD_TIGHT, 0));
		sortControls.setOpaque(false);
		sortControls.add(combo(sortFilter), BorderLayout.CENTER);
		sortControls.add(sortDirection, BorderLayout.EAST);
		filtersPanel.add(filterRow("Sort", sortControls));
		section.add(filtersPanel);
		return section;
	}

	/** Wiki tier icon toggle; select fill + bevel = tier shown. */
	private JLabel tierToggle(CaTier tier)
	{
		JLabel toggle = new JLabel(TIER_ICONS.get(tier));
		toggle.setOpaque(true);
		toggle.setHorizontalAlignment(JLabel.CENTER);
		toggle.setToolTipText(tier.display + " tier (click to show/hide)");
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		Runnable style = () ->
		{
			boolean on = tierEnabled.get(tier);
			toggle.setBackground(on ? theme.selectFill : theme.recess);
			// MatteBorder fills strips — a drawRect border halves on Retina
			toggle.setBorder(new MatteBorder(1, 1, 1, 1, on ? theme.selectEdge : theme.edgeDark));
		};
		style.run();
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				tierEnabled.put(tier, !tierEnabled.get(tier));
				style.run();
				rebuildContent();
			}
		});
		return toggle;
	}

	private JComboBox<String> combo(JComboBox<String> box)
	{
		StoneComboBoxUI.skin(box, theme);
		box.addActionListener(e -> rebuildContent());
		return box;
	}

	private JPanel filterRow(String label, Component control)
	{
		JPanel row = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		JPanel nameHolder = new JPanel(new BorderLayout());
		nameHolder.setOpaque(false);
		nameHolder.setPreferredSize(new Dimension(48, 22));
		nameHolder.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned(),
			BorderLayout.CENTER);
		row.add(nameHolder, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void flipSortDirection()
	{
		sortAscending = !sortAscending;
		sortDirection.setIcon(new PaintedIcon(sortAscending
			? PaintedIcon.Shape.TRIANGLE_UP : PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		rebuildContent();
	}

	// ── content ───────────────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		List<CaTask> tasks = module.tasks();
		if (tasks.isEmpty())
		{
			content.add(note("Log in to load your combat tasks."));
		}
		else if (views.getSelected() == 2)
		{
			if (selectedBoss == null)
			{
				buildBossGrid(tasks);
			}
			else
			{
				buildBossDrilldown(tasks);
			}
		}
		else
		{
			List<CaTask> visible = filteredSorted(tasks, views.getSelected() == 1, selectedBoss);
			if (visible.isEmpty())
			{
				content.add(note(views.getSelected() == 1
					? "No CA goals match the filters. The + on any row adds that task to the Goal planner."
					: "No tasks match the filters."));
			}
			addTaskRows(visible);
		}
		content.revalidate();
		content.repaint();
	}

	List<CaTask> filteredSorted(List<CaTask> tasks, boolean goalsOnly, String boss)
	{
		String term = search.getText().trim();
		String status = (String) statusFilter.getSelectedItem();
		String type = (String) typeFilter.getSelectedItem();
		List<CaTask> visible = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (goalsOnly && !isGoal(task))
			{
				continue;
			}
			if (boss != null && !boss.equals(task.boss))
			{
				continue;
			}
			if (!tierEnabled.get(task.tier) || !task.matches(term))
			{
				continue;
			}
			if ("Completed".equals(status) && !task.completed
				|| "Incomplete".equals(status) && task.completed)
			{
				continue;
			}
			if (!"All types".equals(type) && !type.equals(task.type))
			{
				continue;
			}
			visible.add(task);
		}
		visible.sort(comparator());
		return visible;
	}

	private Comparator<CaTask> comparator()
	{
		String sort = (String) sortFilter.getSelectedItem();
		Comparator<CaTask> byTierThenName = Comparator
			.<CaTask>comparingInt(t -> t.tier.ordinal())
			.thenComparing(t -> t.name);
		Comparator<CaTask> order;
		switch (sort == null ? "Tier" : sort)
		{
			case "Name":
				order = Comparator.comparing(t -> t.name);
				break;
			case "Completion":
				order = Comparator.<CaTask, Boolean>comparing(t -> t.completed)
					.thenComparing(byTierThenName);
				break;
			case "Community %":
				// most-completed-by-the-community first when "ascending":
				// that ordering is the useful "easiest next" reading
				order = Comparator.comparingDouble(
					(CaTask t) -> t.communityPct == null ? -1 : t.communityPct).reversed();
				break;
			default:
				order = byTierThenName;
		}
		return sortAscending ? order : order.reversed();
	}

	// ── task rows ─────────────────────────────────────────────────────

	/** Collapsed = flat hoverable row; expanded = stone card. Row click
	 *  toggles; the +/× tracking affordance keeps its own action. */
	private JPanel taskRow(CaTask task)
	{
		boolean goal = isGoal(task);
		boolean open = expanded.contains(task.id);

		JPanel row;
		if (open)
		{
			row = new StonePanel(theme);
		}
		else
		{
			row = new JPanel();
			row.setOpaque(true);
			row.setBackground(theme.background);
			row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		}
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		row.add(titleLine(task, goal));

		String context = task.boss.isEmpty() ? task.type
			: task.type.isEmpty() ? task.boss : task.boss + " · " + task.type;
		if (!context.isEmpty())
		{
			row.add(new OsrsLabel(context, OsrsSkin.FAINT, OsrsSkin.font())
				.leftAligned().squeezable());
		}

		if (open)
		{
			OsrsLabel description = OsrsLabel.wrapped(task.description, CARD_TEXT_WIDTH,
				OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
			row.add(description);

			String pct = task.communityPct == null ? "unknown"
				: String.format("%.1f%% of players", task.communityPct);
			OsrsLabel details = new OsrsLabel(task.tier.display + " · "
				+ task.tier.points + (task.tier.points == 1 ? " pt" : " pts") + " · " + pct,
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned().squeezable();
			details.setToolTipText("Community completion rate from the wiki's task table (bundled snapshot)");
			row.add(details);
		}
		else
		{
			OsrsLabel description = new OsrsLabel(task.description, OsrsSkin.MUTED, OsrsSkin.font())
				.leftAligned().squeezable();
			description.setToolTipText(task.description);
			row.add(description);
		}

		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!open)
				{
					row.setBackground(theme.hoverFill);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!open)
				{
					row.setBackground(theme.background);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					taskMenu(task, e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					if (!expanded.remove(task.id))
					{
						expanded.add(task.id);
					}
					rebuildContent();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					taskMenu(task, e);
				}
			}
		};
		clickAnywhere(row, interaction);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** Tier icon + name + the dedicated +/× planner affordance. */
	private JPanel titleLine(CaTask task, boolean goal)
	{
		JPanel titleLine = new JPanel();
		titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
		titleLine.setOpaque(false);
		titleLine.setAlignmentX(LEFT_ALIGNMENT);
		JLabel icon = new JLabel(TIER_ICONS.get(task.tier));
		icon.setToolTipText(task.tier.display + " · " + task.tier.points
			+ (task.tier.points == 1 ? " pt" : " pts"));
		titleLine.add(icon);
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel name = new OsrsLabel(task.name,
			task.completed ? OsrsSkin.VALUE : goal ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.boldFont()).leftAligned().squeezable();
		name.setToolTipText(task.name + (goal ? " - in your Goal planner" : ""));
		titleLine.add(name);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		OsrsLabel track = new OsrsLabel(goal ? "×" : "+", OsrsSkin.FAINT, OsrsSkin.boldFont());
		track.setToolTipText(goal ? "Remove this task from the Goal planner"
			: "Add this task as a goal in the Goal planner");
		track.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		track.putClientProperty(OWN_ACTION, Boolean.TRUE);
		track.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				track.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				track.setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleGoal(task);
			}
		});
		titleLine.add(track);
		return titleLine;
	}

	/** The '+' action: the task joins the Goal planner as a "ca:" goal. */
	private void toggleGoal(CaTask task)
	{
		if (isGoal(task))
		{
			state.removeGoalSeed("ca:" + task.id);
		}
		else
		{
			state.addGoalSeed(com.ironhub.state.GoalSeeds.ca(task.id, task.name, task.description, task.tier.display));
			if (task.completed)
			{
				// already done in-game: prove the goal immediately
				state.setUnlocked("catask_" + task.id, true);
			}
		}
	}

	private void taskMenu(CaTask task, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(a -> LinkBrowser.browse(task.wikiUrl()));
		menu.add(wiki);
		JMenuItem trackItem = new JMenuItem(isGoal(task)
			? "Remove from Goal planner" : "Add as goal in Goal planner");
		trackItem.addActionListener(a -> toggleGoal(task));
		menu.add(trackItem);
		if (!task.boss.isEmpty())
		{
			JMenuItem bossItem = new JMenuItem("Show all " + task.boss + " tasks");
			bossItem.addActionListener(a ->
			{
				views.setSelected(2);
				selectedBoss = task.boss;
				rebuildContent();
			});
			menu.add(bossItem);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	// ── boss views ────────────────────────────────────────────────────

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

	private void buildBossGrid(List<CaTask> tasks)
	{
		// the boss grid respects search + tier/status/type filters: a boss
		// shows while any of its tasks would show in the All view
		List<CaTask> visible = filteredSorted(tasks, false, null);
		Map<String, int[]> stats = bossStats(visible);
		Map<String, int[]> totals = bossStats(tasks);
		if (stats.isEmpty())
		{
			content.add(note("No bosses match the filters."));
			return;
		}
		long complete = totals.values().stream().filter(c -> c[0] == c[1]).count();
		JPanel summary = new JPanel();
		summary.setLayout(new BoxLayout(summary, BoxLayout.X_AXIS));
		summary.setOpaque(false);
		summary.setAlignmentX(LEFT_ALIGNMENT);
		summary.setBorder(new EmptyBorder(0, 0, UiTokens.PAD_TIGHT, 0));
		summary.add(new OsrsLabel(complete + "/" + totals.size() + " bosses fully complete",
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		summary.add(Box.createHorizontalGlue());
		cap(summary);
		content.add(summary);

		List<String> bosses = new ArrayList<>(stats.keySet());
		for (int i = 0; i < bosses.size(); i += 2)
		{
			JPanel gridRow = new JPanel(new GridLayout(1, 2, UiTokens.PAD_TIGHT, 0));
			gridRow.setOpaque(false);
			gridRow.setAlignmentX(LEFT_ALIGNMENT);
			gridRow.add(bossCard(bosses.get(i), totals.get(bosses.get(i))));
			if (i + 1 < bosses.size())
			{
				gridRow.add(bossCard(bosses.get(i + 1), totals.get(bosses.get(i + 1))));
			}
			else
			{
				JPanel spacer = new JPanel();
				spacer.setOpaque(false);
				gridRow.add(spacer);
			}
			gridRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
				gridRow.getPreferredSize().height));
			content.add(gridRow);
			content.add(strut(UiTokens.PAD_TIGHT));
		}
	}

	/** A boss as a stone tile: engraved box, hover fill, click drills in. */
	private JPanel bossCard(String boss, int[] counts)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText(boss + ": " + counts[0] + "/" + counts[1] + " tasks done");

		OsrsLabel name = new OsrsLabel(boss,
			counts[0] == counts[1] ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		card.add(name);
		card.add(new OsrsLabel(counts[0] + "/" + counts[1], OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned());
		card.add(strut(UiTokens.PAD_TIGHT));
		StoneMeter meter = new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE,
			(double) counts[0] / Math.max(1, counts[1]));
		meter.setAlignmentX(LEFT_ALIGNMENT);
		card.add(meter);

		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(theme.hoverFill);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(theme.boxFill);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					bossMenu(boss, e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					selectedBoss = boss;
					rebuildContent();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					bossMenu(boss, e);
				}
			}
		};
		clickAnywhere(card, interaction);
		return card;
	}

	private void bossMenu(String boss, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(a -> LinkBrowser.browse(
			"https://oldschool.runescape.wiki/w/" + boss.replace(" ", "_").replace("'", "%27")));
		menu.add(wiki);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void buildBossDrilldown(List<CaTask> tasks)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		StoneButton back = new StoneButton(theme, theme.background, "Back", () ->
		{
			selectedBoss = null;
			rebuildContent();
		});
		back.setToolTipText("Back to all bosses");
		back.setMaximumSize(back.getPreferredSize());
		header.add(back);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(new OsrsLabel(selectedBoss, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		header.add(Box.createHorizontalGlue());
		cap(header);
		content.add(header);
		content.add(strut(UiTokens.PAD_TIGHT));

		List<CaTask> visible = filteredSorted(tasks, false, selectedBoss);
		if (visible.isEmpty())
		{
			content.add(note("No " + selectedBoss + " tasks match the filters."));
		}
		addTaskRows(visible);
	}

	/** Hard row ceiling (the Bank tab's grammar, Luke 2026-07-17): the full
	 *  catalog is ~500+ tasks and rendering it per rebuild was a measured
	 *  freeze contributor — cap and say so, never render hundreds of rows. */
	private static final int MAX_ROWS = 50;

	private void addTaskRows(List<CaTask> visible)
	{
		int limit = Math.min(MAX_ROWS, visible.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(taskRow(visible.get(i)));
			content.add(strut(UiTokens.PAD_TIGHT));
		}
		if (limit < visible.size())
		{
			content.add(note("+ " + (visible.size() - limit)
				+ " more — refine your search or filters"));
		}
	}

	private static Map<CaTier, ImageIcon> loadTierIcons()
	{
		Map<CaTier, ImageIcon> icons = new EnumMap<>(CaTier.class);
		for (CaTier tier : CaTier.values())
		{
			try (java.io.InputStream in =
				CombatAchievementsTab.class.getResourceAsStream(tier.iconResource()))
			{
				if (in != null)
				{
					icons.put(tier, new ImageIcon(ImageIO.read(in)
						.getScaledInstance(-1, 16, Image.SCALE_SMOOTH)));
				}
			}
			catch (java.io.IOException ignored)
			{
			}
		}
		return icons;
	}

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

	/** Attach a click to a container AND its passive children — labels with
	 * tooltips register their own listeners and would swallow clicks. Skips
	 * anything marked as carrying its own action. */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (Component child : container.getComponents())
		{
			if (child instanceof JComponent
				&& Boolean.TRUE.equals(((JComponent) child).getClientProperty(OWN_ACTION)))
			{
				continue;
			}
			if (child instanceof JPanel)
			{
				clickAnywhere((JComponent) child, click);
			}
			else
			{
				child.addMouseListener(click);
			}
		}
	}

	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private static JLabel triangle()
	{
		JLabel label = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		label.setForeground(OsrsSkin.MUTED);
		return label;
	}

	/** Collapsible section header: triangle + skin label, whole row toggles. */
	private JComponent collapsibleHeader(JLabel triangleLabel, String title, String tooltip,
		Runnable onToggle)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(8, 4, 3, 4));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		OsrsLabel label = new OsrsLabel(title, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		header.add(triangleLabel);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(label);
		header.add(Box.createHorizontalGlue());
		cap(header);
		MouseAdapter press = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onToggle.run();
			}
		};
		header.addMouseListener(press);
		// a tooltip registers the label's own mouse listeners, which would
		// swallow the row's — so the children carry the press listener too
		header.setToolTipText(tooltip);
		label.setToolTipText(tooltip);
		label.addMouseListener(press);
		triangleLabel.addMouseListener(press);
		return header;
	}

	/** A small hoverable glyph control — faint until hovered. */
	private static JLabel glyph(javax.swing.Icon icon, String tooltip, Runnable onPress)
	{
		JLabel label = new JLabel(icon);
		label.setForeground(OsrsSkin.FAINT);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onPress.run();
			}
		});
		return label;
	}

	/** Wrapped muted note — never html (the pixel font disagrees with it). */
	private JComponent note(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(6, 0, 6, 0));
		holder.add(OsrsLabel.wrapped(text, NOTE_WIDTH, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 4, 0, 4));
		holder.add(inner);
		cap(holder);
		return holder;
	}

	private JComponent strut(int height)
	{
		return (JComponent) Box.createVerticalStrut(height);
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
