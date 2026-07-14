package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SegmentedControl;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.LinkBrowser;

/**
 * Combat achievements tab: goal-aware stats card, All/Tracked/Bosses views,
 * search, collapsible filters (tier toggles, status, type, sort), task rows
 * that expand for details with per-task tracking, and a two-column boss
 * grid that drills into each boss's tasks. Feature parity with the Combat
 * Achievements Tracker hub plugin, rendered in the Iron Hub design system.
 */
class CombatAchievementsTab extends JPanel
{
	private static final String[] STATUS_OPTIONS = {"All", "Completed", "Incomplete"};
	private static final String[] TYPE_OPTIONS = {"All types", "Stamina", "Perfection",
		"Kill Count", "Mechanical", "Restriction", "Speed"};
	private static final String[] SORT_OPTIONS = {"Tier", "Name", "Completion", "Community %"};

	private final CombatAchievementsModule module;
	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::onStateChanged);

	// stats card
	private final JLabel pointsLine = new JLabel(" ");
	private final HubProgressBar goalBar = HubProgressBar.bar(0);
	private final JLabel goalLine = new JLabel(" ");
	private final JLabel trackedLine = new JLabel(" ");

	// controls
	private final SegmentedControl views = new SegmentedControl(true, "All", "Goals", "Bosses");
	private final JTextField search = new SearchField("Search tasks…");
	private final JLabel filtersHeader = new JLabel("FILTERS");
	private final JPanel filtersPanel = new JPanel();
	private final Map<CaTier, Boolean> tierEnabled = new EnumMap<>(CaTier.class);
	private final JComboBox<String> statusFilter = new JComboBox<>(STATUS_OPTIONS);
	private final JComboBox<String> typeFilter = new JComboBox<>(TYPE_OPTIONS);
	private final JComboBox<String> sortFilter = new JComboBox<>(SORT_OPTIONS);
	private final IconButton sortDirection;
	private boolean filtersExpanded;
	private boolean sortAscending = true;

	// content
	private final JPanel content = new JPanel();
	private String selectedBoss; // non-null = boss drill-down
	private final Set<Integer> expanded = new HashSet<>();
	private Set<String> lastCaGoals;
	private int lastPoints = -1;

	private static final Map<CaTier, ImageIcon> TIER_ICONS = loadTierIcons();

	CombatAchievementsTab(CombatAchievementsModule module, AccountState state, IronHubConfig config)
	{
		this.module = module;
		this.state = state;
		for (CaTier tier : CaTier.values())
		{
			tierEnabled.put(tier, true);
		}

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(buildStatsCard());
		add(Box.createVerticalStrut(UiTokens.PAD));

		views.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		views.onChange(i ->
		{
			selectedBoss = null;
			rebuildContent();
		});
		add(views);
		add(Box.createVerticalStrut(UiTokens.PAD));

		add(search);
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
		add(Box.createVerticalStrut(UiTokens.PAD));

		sortDirection = new IconButton(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_UP, 10),
			"Flip sort direction", this::flipSortDirection);
		add(buildFiltersSection());
		add(Box.createVerticalStrut(UiTokens.PAD));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
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

	/** Cheap path for AccountState notifications: stats always, rows only
	 * when points or the set of CA goals actually changed. */
	private void onStateChanged()
	{
		refreshStats();
		Set<String> caGoals = selectedCaGoals();
		int points = module.points();
		if (!caGoals.equals(lastCaGoals) || points != lastPoints)
		{
			lastCaGoals = caGoals;
			lastPoints = points;
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

	private JPanel buildStatsCard()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER),
			new EmptyBorder(6, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD)));
		card.add(new SectionLabel("Progress"));
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		pointsLine.setForeground(UiTokens.TEXT_PRIMARY);
		pointsLine.setFont(pointsLine.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		pointsLine.setAlignmentX(LEFT_ALIGNMENT);
		card.add(pointsLine);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(goalBar);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		goalLine.setForeground(UiTokens.STATUS_AVAILABLE);
		goalLine.setFont(goalLine.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		goalLine.setAlignmentX(LEFT_ALIGNMENT);
		goalLine.setToolTipText("Tier goal - set it under Iron Hub settings (Auto advances tier by tier)");
		card.add(goalLine);
		trackedLine.setForeground(UiTokens.TEXT_MUTED);
		trackedLine.setFont(trackedLine.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		trackedLine.setAlignmentX(LEFT_ALIGNMENT);
		card.add(trackedLine);
		return card;
	}

	private void refreshStats()
	{
		List<CaTask> tasks = module.tasks();
		int points = module.points();
		int totalPoints = tasks.stream().mapToInt(t -> t.tier.points).sum();
		long done = tasks.stream().filter(t -> t.completed).count();
		pointsLine.setText(tasks.isEmpty()
			? points + " pts"
			: points + "/" + totalPoints + " pts · " + done + "/" + tasks.size() + " tasks");

		CaTier goal = module.goalTier();
		int threshold = module.goalThreshold();
		if (goal == null)
		{
			goalLine.setText("All tiers complete!");
			goalLine.setForeground(UiTokens.STATUS_OWNED);
			goalBar.setFraction(1);
		}
		else if (points >= threshold && threshold > 0)
		{
			goalLine.setText(goal.display + " complete! (" + points + " pts)");
			goalLine.setForeground(UiTokens.STATUS_OWNED);
			goalBar.setFraction(1);
		}
		else
		{
			goalLine.setText(threshold > 0
				? (threshold - points) + " pts to " + goal.display
				: "Goal: " + goal.display);
			goalLine.setForeground(UiTokens.STATUS_AVAILABLE);
			goalBar.setFraction(threshold == 0 ? 0 : (double) points / threshold);
		}

		if (selectedCaGoals().isEmpty())
		{
			trackedLine.setText("No CA goals yet");
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
			trackedLine.setText("Goals: " + goalDonePts + "/" + goalPts
				+ " pts (" + found + " tasks)");
		}
	}

	// ── filters ───────────────────────────────────────────────────────

	private JPanel buildFiltersSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		filtersHeader.setForeground(UiTokens.TEXT_MUTED);
		filtersHeader.setFont(SectionLabel.letterSpaced(
			filtersHeader.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		filtersHeader.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		filtersHeader.setIconTextGap(UiTokens.ROW_GAP);
		filtersHeader.setAlignmentX(LEFT_ALIGNMENT);
		filtersHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		filtersHeader.setToolTipText("Show or hide the tier, status, type and sort filters");
		filtersHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				filtersExpanded = !filtersExpanded;
				filtersHeader.setIcon(new PaintedIcon(filtersExpanded
					? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
				filtersPanel.setVisible(filtersExpanded);
				revalidate();
				repaint();
			}
		});
		section.add(filtersHeader);

		filtersPanel.setLayout(new BoxLayout(filtersPanel, BoxLayout.Y_AXIS));
		filtersPanel.setOpaque(false);
		filtersPanel.setAlignmentX(LEFT_ALIGNMENT);
		filtersPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
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
		filtersPanel.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		filtersPanel.add(filterRow("Status", combo(statusFilter)));
		filtersPanel.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		filtersPanel.add(filterRow("Type", combo(typeFilter)));
		filtersPanel.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		JPanel sortControls = new JPanel(new BorderLayout(UiTokens.PAD_TIGHT, 0));
		sortControls.setOpaque(false);
		sortControls.add(combo(sortFilter), BorderLayout.CENTER);
		sortControls.add(sortDirection, BorderLayout.EAST);
		filtersPanel.add(filterRow("Sort", sortControls));
		section.add(filtersPanel);
		return section;
	}

	/** Wiki tier icon toggle; accent border = tier shown (interaction). */
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
			toggle.setBackground(on ? UiTokens.ICON_BUTTON_BG : UiTokens.INSET_BG);
			toggle.setBorder(BorderFactory.createLineBorder(
				on ? UiTokens.ACCENT : UiTokens.BORDER_DIM));
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
		box.setFont(box.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		box.addActionListener(e -> rebuildContent());
		return box;
	}

	private JPanel filterRow(String label, Component control)
	{
		JPanel row = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		JLabel name = new JLabel(label);
		name.setForeground(UiTokens.TEXT_MUTED);
		name.setFont(name.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		name.setPreferredSize(new Dimension(48, UiTokens.BUTTON_HEIGHT));
		row.add(name, BorderLayout.WEST);
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
			content.add(emptyLabel("Log in to load your combat tasks."));
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
				content.add(emptyLabel(views.getSelected() == 1
					? "No CA goals match the filters. The + on any row adds that task to the Goal planner."
					: "No tasks match the filters."));
			}
			for (CaTask task : visible)
			{
				content.add(taskRow(task));
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
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

	private JPanel taskRow(CaTask task)
	{
		boolean goal = isGoal(task);
		boolean open = expanded.contains(task.id);

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel titleLine = new JPanel();
		titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
		titleLine.setOpaque(false);
		titleLine.setAlignmentX(LEFT_ALIGNMENT);
		JLabel icon = new JLabel(TIER_ICONS.get(task.tier));
		icon.setToolTipText(task.tier.display + " · " + task.tier.points
			+ (task.tier.points == 1 ? " pt" : " pts"));
		titleLine.add(icon);
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel name = new JLabel(task.name);
		name.setForeground(task.completed ? UiTokens.STATUS_OWNED
			: goal ? UiTokens.STATUS_AVAILABLE : UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setToolTipText(task.name + (goal ? " - in your Goal planner" : ""));
		name.setMinimumSize(new Dimension(0, 0)); // ellipsize before pushing the button out
		titleLine.add(name);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		IconButton track = new IconButton(goal ? "×" : "+",
			goal ? "Remove this task from the Goal planner"
				: "Add this task as a goal in the Goal planner",
			() -> toggleGoal(task));
		titleLine.add(track);
		row.add(titleLine);

		String context = task.boss.isEmpty() ? task.type
			: task.type.isEmpty() ? task.boss : task.boss + " · " + task.type;
		if (!context.isEmpty())
		{
			JLabel contextLine = new JLabel(context);
			contextLine.setForeground(UiTokens.TEXT_MUTED);
			contextLine.setFont(contextLine.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			contextLine.setAlignmentX(LEFT_ALIGNMENT);
			row.add(contextLine);
		}

		if (open)
		{
			JTextArea description = new JTextArea(task.description);
			description.setFont(description.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
			description.setForeground(UiTokens.TEXT_BODY);
			description.setBackground(UiTokens.CARD_BG);
			description.setLineWrap(true);
			description.setWrapStyleWord(true);
			description.setEditable(false);
			description.setFocusable(false);
			description.setBorder(new EmptyBorder(2, 0, 2, 0));
			description.setAlignmentX(LEFT_ALIGNMENT);
			row.add(description);

			String pct = task.communityPct == null ? "unknown"
				: String.format("%.1f%% of players", task.communityPct);
			JLabel details = new JLabel(task.tier.display + " · "
				+ task.tier.points + (task.tier.points == 1 ? " pt" : " pts") + " · " + pct);
			details.setForeground(UiTokens.TEXT_FAINT);
			details.setFont(details.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			details.setAlignmentX(LEFT_ALIGNMENT);
			details.setToolTipText("Community completion rate from the wiki's task table (bundled snapshot)");
			row.add(details);
		}
		else
		{
			JLabel description = new JLabel(task.description);
			description.setForeground(UiTokens.TEXT_BODY);
			description.setFont(description.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
			description.setAlignmentX(LEFT_ALIGNMENT);
			description.setToolTipText(task.description);
			row.add(description);
		}

		MouseAdapter interaction = new MouseAdapter()
		{
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
		row.addMouseListener(interaction);
		for (Component child : row.getComponents())
		{
			if (child != titleLine)
			{
				child.addMouseListener(interaction);
			}
		}

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** The '+' action: the task joins the Goal planner as a "ca:" goal. */
	private void toggleGoal(CaTask task)
	{
		if (isGoal(task))
		{
			state.removeCaGoal(task.id);
		}
		else
		{
			state.addCaGoal(task.id, task.name, task.description, task.tier.display);
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
			content.add(emptyLabel("No bosses match the filters."));
			return;
		}
		long complete = totals.values().stream().filter(c -> c[0] == c[1]).count();
		JLabel summary = new JLabel(complete + "/" + totals.size() + " bosses fully complete");
		summary.setForeground(UiTokens.TEXT_MUTED);
		summary.setFont(summary.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		summary.setBorder(new EmptyBorder(0, 0, UiTokens.PAD_TIGHT, 0));
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
			content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	private JPanel bossCard(String boss, int[] counts)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText(boss + ": " + counts[0] + "/" + counts[1] + " tasks done");

		JLabel name = new JLabel(boss);
		name.setForeground(counts[0] == counts[1] ? UiTokens.STATUS_OWNED : UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setAlignmentX(LEFT_ALIGNMENT);
		card.add(name);
		JLabel count = new JLabel(counts[0] + "/" + counts[1]);
		count.setForeground(UiTokens.TEXT_MUTED);
		count.setFont(count.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		count.setAlignmentX(LEFT_ALIGNMENT);
		card.add(count);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(HubProgressBar.mini((double) counts[0] / Math.max(1, counts[1]), 0));

		card.addMouseListener(new MouseAdapter()
		{
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
		});
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
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		header.add(new IconButton(new PaintedIcon(PaintedIcon.Shape.CHEVRON_LEFT, 12),
			"Back to all bosses", () ->
		{
			selectedBoss = null;
			rebuildContent();
		}));
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel title = new JLabel(selectedBoss);
		title.setForeground(UiTokens.TEXT_PRIMARY);
		title.setFont(title.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		header.add(title);
		content.add(header);
		content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		List<CaTask> visible = filteredSorted(tasks, false, selectedBoss);
		if (visible.isEmpty())
		{
			content.add(emptyLabel("No " + selectedBoss + " tasks match the filters."));
		}
		for (CaTask task : visible)
		{
			content.add(taskRow(task));
			content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	private JLabel emptyLabel(String text)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(UiTokens.TEXT_MUTED);
		label.setFont(label.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		label.setBorder(new EmptyBorder(UiTokens.PAD, 0, UiTokens.PAD, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
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

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
