package com.ironhub.modules.goals;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.engine.Plan;
import com.ironhub.modules.goals.GoalPlannerModule.CompiledStep;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.util.LinkBrowser;

/**
 * The Goals hub (Goals v2 G7, design/GOALS-V2.md §6): ONE cohesive surface
 * replacing the planner's three views. Reads top to bottom — hero stats,
 * CURRENT TASK, GOALS (every Route as a category-grouped expandable row of
 * Tasks), SUGGESTIONS. Benefits lead the copy; time is one quiet stat. The
 * completed archive lives behind the Done-this-month block (depth 2).
 *
 * <p>Frameless: the host hub page provides the stone frame + header plate.
 */
class GoalsHubTab extends JPanel
{
	private static final Color BANNER_AMBER = new Color(0xE0A23C);

	private final GoalPlannerModule module;
	private final AccountState state;
	private final GoalsPack pack;
	private final GearProgressionPack gearPack;
	private final net.runelite.client.game.ItemManager itemManager; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless
	private final OsrsTheme theme;

	private final JPanel content = new JPanel();
	private final Runnable rebuildGate = RebuildGate.install(this, this::rebuild);
	private final Runnable planListener;

	/** One expanded Route at a time; the completed-archive depth-2 view. */
	private String expandedRoute;
	private boolean showArchive;
	/** Live add-goal candidates (id → name), rendered under the search field. */
	private final Map<String, String> searchResults = new LinkedHashMap<>();
	/** Collapsed Goal categories (Quests, Gear, …). */
	private final java.util.Set<String> collapsedCategories = new java.util.HashSet<>();
	/** Route follows the freshest plan; the update banner guards reorders. */
	private volatile Plan latestPlan;
	private Plan displayedPlan;

	GoalsHubTab(GoalPlannerModule module, AccountState state, GoalsPack pack,
		GearProgressionPack gearPack, net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIcons, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.pack = pack;
		this.gearPack = gearPack;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		// a fresh plan updates the head then rebuilds through the same gate
		planListener = () ->
		{
			latestPlan = module.currentPlan();
			rebuildGate.run();
		};
		module.addPlanListener(planListener);
		state.addListener(rebuildGate);
		latestPlan = module.currentPlan();
		displayedPlan = latestPlan;
		rebuild();
	}

	void dispose()
	{
		state.removeListener(rebuildGate);
		module.removePlanListener(planListener);
	}

	void onPlanUpdated(Plan plan)
	{
		latestPlan = plan;
		SwingUtilities.invokeLater(this::rebuild);
	}

	/** Test seams. */
	void expandRoute(String goalId)
	{
		expandedRoute = goalId;
		rebuild();
	}

	void openArchive()
	{
		showArchive = true;
		rebuild();
	}

	private void rebuild()
	{
		content.removeAll();
		if (showArchive)
		{
			buildArchive();
		}
		else
		{
			buildMain();
		}
		content.revalidate();
		content.repaint();
	}

	// ── the four sections ──────────────────────────────────────────────

	private void buildMain()
	{
		List<GoalsPack.Goal> routes = routes();

		// 1 · hero
		content.add(pad(pair(
			new StatBox(theme, "Active\ngoals:", OsrsIcons.stat(theme, "quests"),
				String.valueOf(routes.size())),
			doneThisMonth())));
		content.add(strut(2));
		content.add(pad(centered(new OsrsLabel(metaLine(), OsrsSkin.FAINT, OsrsSkin.smallFont()))));

		// 2 · current task
		content.add(section("CURRENT TASK"));
		Plan plan = latestPlan;
		Plan.Step head = plan == null ? null : plan.head();
		content.add(pad(head == null ? emptyTask() : currentTask(head)));

		// 3 · goals, grouped by category
		content.add(section("GOALS"));
		JComponent banner = updateBanner();
		if (banner != null)
		{
			content.add(pad(banner));
			content.add(strut(3));
		}
		if (routes.isEmpty())
		{
			content.add(pad(mutedLine("No routes yet — add a goal below.")));
		}
		else
		{
			Map<String, List<GoalsPack.Goal>> byCategory = groupByCategory(routes);
			for (Map.Entry<String, List<GoalsPack.Goal>> e : byCategory.entrySet())
			{
				content.add(category(e.getKey(), e.getValue().size()));
				if (collapsedCategories.contains(e.getKey()))
				{
					continue; // collapsed — its routes stay hidden
				}
				for (GoalsPack.Goal route : e.getValue())
				{
					content.add(pad(routeRow(route)));
					if (route.getId().equals(expandedRoute))
					{
						addTasks(route);
					}
					content.add(strut(2));
				}
			}
		}
		content.add(strut(2));
		content.add(pad(addGoalField()));
		for (Map.Entry<String, String> e : searchResults.entrySet())
		{
			content.add(pad(searchResultRow(e.getKey(), e.getValue())));
		}

		// 4 · suggestions
		List<Suggester.Suggestion> suggestions = module.suggestions();
		if (!suggestions.isEmpty())
		{
			content.add(section("SUGGESTIONS"));
			for (Suggester.Suggestion s : suggestions)
			{
				content.add(pad(suggestionCard(s)));
				content.add(strut(2));
			}
		}

		content.add(strut(6));
	}

	/** Routes = selected, not-yet-achieved goals, someday last. */
	private List<GoalsPack.Goal> routes()
	{
		List<GoalsPack.Goal> out = new ArrayList<>();
		for (GoalsPack.Goal goal : GoalPlannerModule.allGoals(pack, gearPack, state))
		{
			if (state.getSelectedGoals().contains(goal.getId())
				&& !GoalPlannerModule.isAchieved(goal, state))
			{
				out.add(goal);
			}
		}
		return out;
	}

	private Map<String, List<GoalsPack.Goal>> groupByCategory(List<GoalsPack.Goal> routes)
	{
		Map<String, List<GoalsPack.Goal>> out = new LinkedHashMap<>();
		for (String category : List.of("Quests", "Gear", "Level unlocks", "Unlocks", "Supplies", "Other"))
		{
			for (GoalsPack.Goal route : routes)
			{
				if (categoryOf(route.getId()).equals(category))
				{
					out.computeIfAbsent(category, k -> new ArrayList<>()).add(route);
				}
			}
		}
		return out;
	}

	private static String categoryOf(String goalId)
	{
		if (goalId.startsWith("custom:quest:") || goalId.startsWith("quest:"))
		{
			return "Quests";
		}
		if (goalId.startsWith("custom:skill:") || goalId.startsWith("skill:"))
		{
			return "Level unlocks";
		}
		int colon = goalId.indexOf(':');
		String family = colon > 0 ? goalId.substring(0, colon) : "pack";
		switch (family)
		{
			case "gear":
			case "clog":
			case "pack":
				return "Gear";
			case "supply":
				return "Supplies";
			case "ca":
			case "diary":
			case "qol":
			case "poh":
			case "clue":
				return "Unlocks";
			default:
				return "Other";
		}
	}

	private StatBox doneThisMonth()
	{
		StatBox box = new StatBox(theme, "Done this\nmonth:",
			OsrsIcons.stat(theme, "achievements"), String.valueOf(doneThisMonthCount()));
		box.setToolTipText("View completed goals");
		box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		box.addMouseListener(new MouseAdapter()
		{
			// a hover lift signals the click-through
			@Override
			public void mouseEntered(MouseEvent e)
			{
				box.setBackground(theme.hoverFill);
				box.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				box.setBackground(theme.boxFill);
				box.repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				showArchive = true;
				rebuild();
			}
		});
		return box;
	}

	private int doneThisMonthCount()
	{
		java.time.YearMonth now = java.time.YearMonth.now(ZoneOffset.UTC);
		int n = 0;
		for (PersistedState.GoalRecord r : state.getGoalRecords())
		{
			if (r.completedAt > 0 && java.time.YearMonth.from(
				java.time.Instant.ofEpochMilli(r.completedAt).atZone(ZoneOffset.UTC)).equals(now))
			{
				n++;
			}
		}
		return n;
	}

	private String metaLine()
	{
		String meta = module.methodsPack() == null ? "" : module.methodsPack().metaLabel();
		Plan plan = latestPlan;
		String hours = plan == null ? "" : " · ~" + compactHours(plan.knownHours)
			+ (plan.unknownCount > 0 ? " +?" : "") + " total";
		return meta + hours;
	}

	// ── current task ───────────────────────────────────────────────────

	private JComponent currentTask(Plan.Step step)
	{
		StonePanel card = new StonePanel(theme);
		card.setBackground(theme.selectFill);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel title = row();
		Icon icon = stepIcon(step);
		if (icon != null)
		{
			title.add(new JLabel(icon));
			title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		title.add(new OsrsLabel(taskName(step), OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		title.add(Box.createHorizontalGlue());
		card.add(title);

		// a real progress bar for TRAIN steps; live xp vs the level band
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
		{
			Skill skill = step.action.trainSkill;
			int from = step.trainFromLevel > 0 ? step.trainFromLevel : state.getRealLevel(skill);
			int to = step.action.trainToLevel;
			long fromXp = Experience.getXpForLevel(Math.max(1, Math.min(99, from)));
			long toXp = Experience.getXpForLevel(Math.max(2, Math.min(99, to)));
			long now = state.getXp(skill);
			double frac = toXp <= fromXp ? 0 : Math.max(0, Math.min(1, (now - fromXp) / (double) (toXp - fromXp)));
			card.add(Box.createVerticalStrut(3));
			card.add(new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, frac)
				.labels("Lvl " + from, Math.round(frac * 100) + "%", "Lvl " + to));
		}

		// number-left first, time last (the benefits-first rule)
		JPanel stats = row();
		String left = step.trainXpRemaining > 0 ? compactXp(step.trainXpRemaining) + " xp left"
			: timeText(step).equals("?") ? "" : "in progress";
		stats.add(new OsrsLabel(left, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
		stats.add(Box.createHorizontalGlue());
		OsrsLabel pace = new OsrsLabel(timeText(step), OsrsSkin.FAINT, OsrsSkin.smallFont());
		pace.setToolTipText("At your measured pace"
			+ (Double.isNaN(step.spreadHours) ? "" : " · up to ~" + compactHours(step.spreadHours) + " if unlucky"));
		stats.add(pace);
		card.add(Box.createVerticalStrut(2));
		card.add(stats);

		if (step.why != null && !step.why.isEmpty())
		{
			card.add(Box.createVerticalStrut(3));
			card.add(OsrsLabel.wrapped("Why: " + step.why, 186, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		}

		JPanel foot = row();
		foot.add(new OsrsLabel(servingLine(step), OsrsSkin.FAINT, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		foot.add(Box.createHorizontalGlue());
		String wiki = wikiUrl(step);
		if (wiki != null)
		{
			StoneButton w = new StoneButton(theme, theme.selectFill, "Wiki", () -> LinkBrowser.browse(wiki));
			w.setMaximumSize(w.getPreferredSize());
			foot.add(w);
		}
		// a red-X box to drop the goal this task belongs to
		foot.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		foot.add(removeGoalBox(step));
		card.add(foot);

		// right-click: push this task down the plan, or open its wiki
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					JPopupMenu menu = new JPopupMenu();
					menu.add(item("Not right now", () -> state.togglePlannerSnooze(step.action.id)));
					if (wiki != null)
					{
						menu.add(item("Open wiki", () -> LinkBrowser.browse(wiki)));
					}
					menu.show(card, e.getX(), e.getY());
				}
			}
		});
		cap(card);
		return card;
	}

	/** A small red-X box: removes the goal(s) this task is the current step
	 *  of (the player rejecting the task means dropping the goal). */
	private JComponent removeGoalBox(Plan.Step step)
	{
		JLabel x = new JLabel("×");
		OsrsSkin.crisp(x);
		x.setFont(OsrsSkin.font());
		x.setForeground(UiTokens.STATUS_WARNING);
		x.setBorder(new javax.swing.border.CompoundBorder(
			new javax.swing.border.LineBorder(OsrsSkin.FAINT, 1),
			new EmptyBorder(0, 3, 0, 3)));
		List<String> goals = new ArrayList<>();
		for (String g : step.action.neededBy)
		{
			if (state.getSelectedGoals().contains(g))
			{
				goals.add(g);
			}
		}
		x.setToolTipText(goals.size() > 1
			? "Remove the " + goals.size() + " goals that need this"
			: "Remove this goal");
		x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		x.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				for (String g : goals)
				{
					GoalPlannerModule.removeGoal(state, g);
				}
			}
		});
		return x;
	}

	private JComponent emptyTask()
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.X_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.add(new OsrsLabel("All caught up — add a goal to get a plan.",
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		card.add(Box.createHorizontalGlue());
		cap(card);
		return card;
	}

	// ── goals: routes + tasks ──────────────────────────────────────────

	private JComponent routeRow(GoalsPack.Goal route)
	{
		String id = route.getId();
		boolean pinned = state.isGoalPinned(id);
		String tier = state.getGoalPriority(id);
		boolean someday = "someday".equals(tier);
		boolean expanded = id.equals(expandedRoute);
		List<Plan.Step> slice = routeSlice(route);
		boolean single = slice.size() <= 1; // a one-task goal needs no meter/count
		StonePanel card = new StonePanel(theme);
		if (pinned)
		{
			card.setBackground(theme.selectFill);
		}
		// a coloured left edge marks the priority tier (17)
		Color edge = "high".equals(tier) ? OsrsSkin.TITLE : someday ? OsrsSkin.FAINT : null;
		if (edge != null)
		{
			card.setBorder(new javax.swing.border.CompoundBorder(
				new javax.swing.border.MatteBorder(0, 3, 0, 0, edge), card.getBorder()));
		}
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel top = row();
		JLabel triangle = new JLabel(new PaintedIcon(
			expanded ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(someday ? OsrsSkin.FAINT : OsrsSkin.MUTED);
		top.add(triangle);
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		Icon icon = goalIcon(route);
		if (icon != null)
		{
			top.add(new JLabel(icon));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		Color nameColor = someday ? OsrsSkin.FAINT : pinned ? OsrsSkin.TITLE : OsrsSkin.LABEL;
		top.add(new OsrsLabel(route.getName(), nameColor,
			pinned ? OsrsSkin.boldFont() : OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		if (!single && !someday)
		{
			long left = slice.size();
			top.add(new OsrsLabel(left + (left == 1 ? " task" : " tasks"),
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		// a dedicated pin affordance on every tile (13)
		top.add(pinGlyph(id, pinned));
		card.add(top);
		if (!single && !someday)
		{
			card.add(Box.createVerticalStrut(3));
			card.add(new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE, GoalPlannerModule.progress(route, state)));
		}

		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					routeMenu(route).show(card, e.getX(), e.getY());
					return;
				}
				expandedRoute = expanded ? null : id;
				rebuild();
			}
		};
		card.addMouseListener(click);
		for (java.awt.Component sub : card.getComponents())
		{
			sub.addMouseListener(click);
		}
		cap(card);
		return card;
	}

	/** A clickable pin icon (title when pinned, faint when not). */
	private JLabel pinGlyph(String goalId, boolean pinned)
	{
		JLabel pin = new JLabel(new PaintedIcon(PaintedIcon.Shape.FLAG, 10));
		pin.setForeground(pinned ? OsrsSkin.TITLE : OsrsSkin.FAINT);
		pin.setToolTipText(pinned ? "Pinned as active — click to unpin" : "Pin as active");
		pin.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		pin.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.setGoalPinned(goalId, !pinned);
				e.consume();
			}
		});
		return pin;
	}

	/** This route's slice of the merged plan (the real, costed Tasks with
	 *  time + method) — in plan order (next-first). Falls back to the goal's
	 *  own checklist when no plan exists yet. */
	private List<Plan.Step> routeSlice(GoalsPack.Goal route)
	{
		List<Plan.Step> out = new ArrayList<>();
		Plan plan = latestPlan;
		if (plan != null)
		{
			for (Plan.Step step : plan.steps)
			{
				if (step.action.neededBy.contains(route.getId()))
				{
					out.add(step);
				}
			}
		}
		return out;
	}

	/** The expanded Route's Tasks — its plan slice, next-first, each with the
	 *  estimated time and recommended method (7/9/11/12). */
	private void addTasks(GoalsPack.Goal route)
	{
		List<Plan.Step> slice = routeSlice(route);
		if (slice.isEmpty())
		{
			// no plan yet, or the only work is the goal's own manual proof
			for (CompiledStep step : GoalPlannerModule.compile(route, state))
			{
				if (!step.met)
				{
					content.add(pad(manualTaskRow(step)));
				}
			}
			return;
		}
		int shown = 0;
		boolean current = true;
		for (Plan.Step step : slice)
		{
			content.add(pad(taskRow(step, current)));
			current = false;
			if (++shown >= 8 && slice.size() > shown)
			{
				content.add(pad(moreLine("+ " + (slice.size() - shown) + " more tasks")));
				break;
			}
		}
	}

	/** One Task from the plan slice: name + time, with the recommended
	 *  method (or drop/spread) on a faint sub-line. */
	private JComponent taskRow(Plan.Step step, boolean current)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(LEFT_ALIGNMENT);
		block.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP + 6, 2, UiTokens.ROW_GAP));

		JPanel line = row();
		Icon icon = stepIcon(step);
		if (icon != null)
		{
			line.add(new JLabel(icon));
			line.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		OsrsLabel name = new OsrsLabel(taskName(step), current ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.font()).leftAligned().squeezable();
		String url = wikiUrl(step);
		if (url != null)
		{
			name.setToolTipText("Right-click for the wiki");
		}
		line.add(name);
		line.add(Box.createHorizontalGlue());
		line.add(new OsrsLabel(timeText(step), OsrsSkin.MUTED, OsrsSkin.smallFont()));
		block.add(line);

		String sub = taskSubLine(step);
		if (sub != null)
		{
			JPanel s = row();
			s.add(Box.createHorizontalStrut(UiTokens.STATUS_GLYPH_SIZE + UiTokens.PAD_TIGHT));
			s.add(new OsrsLabel(sub, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned().squeezable());
			s.add(Box.createHorizontalGlue());
			block.add(s);
		}
		// right-click any task → open its wiki page
		if (url != null)
		{
			block.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (SwingUtilities.isRightMouseButton(e))
					{
						JPopupMenu menu = new JPopupMenu();
						menu.add(item("Open wiki", () -> LinkBrowser.browse(url)));
						menu.show(block, e.getX(), e.getY());
					}
				}
			});
		}
		cap(block);
		return block;
	}

	/** The faint sub-line for a Task: the recommended method (TRAIN), the
	 *  drop odds/spread (OBTAIN), or nothing. */
	private String taskSubLine(Plan.Step step)
	{
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN && step.methodName != null)
		{
			return "via " + step.methodName
				+ (step.methodRate > 0 ? " · " + compactXp(step.methodRate) + " xp/hr" : "");
		}
		if (!Double.isNaN(step.spreadHours) && step.spreadHours > step.hours + 0.05)
		{
			return "up to ~" + compactHours(step.spreadHours) + " if unlucky";
		}
		return null;
	}

	/** A remaining manual checklist step when there's no plan slice. */
	private JComponent manualTaskRow(CompiledStep step)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP + 6, 2, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(step.label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		if (step.manual)
		{
			JLabel tick = new JLabel("tick");
			OsrsSkin.crisp(tick);
			tick.setFont(OsrsSkin.smallFont());
			tick.setForeground(OsrsSkin.FAINT);
			tick.setToolTipText("Mark done");
			tick.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			tick.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.setUnlocked(step.unlockKey, true);
				}
			});
			row.add(tick);
		}
		cap(row);
		return row;
	}

	private JPopupMenu routeMenu(GoalsPack.Goal route)
	{
		JPopupMenu menu = new JPopupMenu();
		String id = route.getId();
		boolean pinned = state.isGoalPinned(id);
		menu.add(item(pinned ? "Unpin" : "Pin as active", () -> state.setGoalPinned(id, !pinned)));
		menu.addSeparator();
		for (String tier : List.of("high", "normal", "someday"))
		{
			// a native checkbox item marks the current tier — no font glyph
			javax.swing.JCheckBoxMenuItem mi = new javax.swing.JCheckBoxMenuItem(
				"Priority: " + tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1),
				state.getGoalPriority(id).equals(tier));
			mi.addActionListener(e -> state.setGoalPriority(id, tier));
			menu.add(mi);
		}
		menu.addSeparator();
		String wiki = goalWiki(route);
		if (wiki != null)
		{
			menu.add(item("Open wiki", () -> LinkBrowser.browse(wiki)));
		}
		menu.add(item("Remove goal", () -> GoalPlannerModule.removeGoal(state, id)));
		return menu;
	}

	/** The best wiki page for a Goal: its quest, or its item/name. */
	private String goalWiki(GoalsPack.Goal route)
	{
		String id = route.getId();
		String page = null;
		if (id.startsWith("custom:quest:") || id.startsWith("quest:"))
		{
			page = route.getName();
		}
		else if (route.icon() != null || id.startsWith("gear:") || id.startsWith("clog:")
			|| id.startsWith("supply:"))
		{
			page = route.getName();
		}
		return page == null ? null : "https://oldschool.runescape.wiki/w/" + page.replace(' ', '_');
	}

	// ── add goal ───────────────────────────────────────────────────────

	private JComponent addGoalField()
	{
		StoneTextField field = new StoneTextField(theme, "Add a goal — search…");
		field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				refreshSearch(field.getText());
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				refreshSearch(field.getText());
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
			}
		});
		return field;
	}

	/** Live add-goal candidates: a "skill N" parse, then pack/gear matches. */
	private void refreshSearch(String query)
	{
		// the results render inline beneath the field on the next rebuild
		searchResults.clear();
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (q.length() >= 2)
		{
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z]+)\\s+(\\d{1,2})").matcher(q);
			if (m.matches())
			{
				for (Skill skill : Skill.values())
				{
					if (skill.getName().toLowerCase(Locale.ROOT).startsWith(m.group(1)))
					{
						int level = Integer.parseInt(m.group(2));
						if (level >= 2 && level <= 99)
						{
							String id = "custom:skill:" + skill.getName().toLowerCase(Locale.ROOT) + ":" + level;
							if (!state.getSelectedGoals().contains(id))
							{
								searchResults.put(id, skill.getName() + " " + level);
							}
						}
					}
				}
			}
			for (GearProgressionPack.Phase phase : gearPack.getPhases())
			{
				for (GearProgressionPack.Group group : phase.getGroups())
				{
					for (GearProgressionPack.Item item : group.getItems())
					{
						if (item.getName().toLowerCase(Locale.ROOT).contains(q)
							&& !state.getSelectedGoals().contains(item.goalId()) && searchResults.size() < 8)
						{
							searchResults.put(item.goalId(), item.getName());
						}
					}
				}
			}
			for (GoalsPack.Goal goal : pack.getGoals())
			{
				if (goal.getName().toLowerCase(Locale.ROOT).contains(q)
					&& !state.getSelectedGoals().contains(goal.getId()) && searchResults.size() < 8)
				{
					searchResults.put(goal.getId(), goal.getName());
				}
			}
			// quests: name → a custom:quest goal (the engine expands the chain)
			if (module.questsPack() != null)
			{
				for (com.ironhub.data.QuestsPack.QuestEntry qe : module.questsPack().quests)
				{
					String gid = "custom:quest:" + qe.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
					if (qe.name.toLowerCase(Locale.ROOT).contains(q)
						&& !state.getSelectedGoals().contains(gid) && searchResults.size() < 8)
					{
						searchResults.put("quest::" + qe.name, qe.name);
					}
				}
			}
		}
		rebuild();
	}

	/** A live add-goal candidate row: name + a "+ Add" affordance. */
	private JComponent searchResultRow(String goalId, String name)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP + 4, 2, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		StoneButton add = new StoneButton(theme, theme.boxFill, "+ Add", () -> addSearchGoal(goalId, name));
		add.setMaximumSize(add.getPreferredSize());
		row.add(add);
		cap(row);
		return row;
	}

	private void addSearchGoal(String goalId, String name)
	{
		if (goalId.startsWith("custom:skill:"))
		{
			int idx = goalId.lastIndexOf(':');
			String skill = goalId.substring("custom:skill:".length(), idx);
			String level = goalId.substring(idx + 1);
			String display = skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1);
			state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(goalId, name,
				"skill:" + display + ":" + level));
		}
		else if (goalId.startsWith("quest::"))
		{
			String quest = goalId.substring("quest::".length());
			String id = "custom:quest:" + quest.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
			state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(id, quest, "quest:" + quest));
		}
		else
		{
			state.selectGoal(goalId, true); // gear/pack goals: just select
		}
		searchResults.clear();
	}

	// ── suggestions ────────────────────────────────────────────────────

	private JComponent suggestionCard(Suggester.Suggestion s)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		top.add(new OsrsLabel(s.name, OsrsSkin.LABEL, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		StoneButton add = new StoneButton(theme, theme.boxFill,
			"merge".equals(s.kind) ? "Merge" : "+ Route", () -> acceptSuggestion(s));
		add.setMaximumSize(add.getPreferredSize());
		top.add(add);
		card.add(top);
		card.add(new OsrsLabel(s.benefit, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
		cap(card);
		return card;
	}

	private void acceptSuggestion(Suggester.Suggestion s)
	{
		if ("merge".equals(s.kind))
		{
			// a merge is presentation only — both goals already route as one;
			// pin them together so they read as a combined route
			for (String id : s.mergeGoalIds)
			{
				state.setGoalPinned(id, true);
			}
			return;
		}
		// an effect stepping-stone: track its unlock as a custom goal
		if (s.goalId != null && s.goalId.startsWith("suggest:effect:"))
		{
			state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
				"custom:" + s.goalId.substring("suggest:".length()), s.name, effectReq(s)));
		}
	}

	/** The effect's activation requirement, from the pack. */
	private String effectReq(Suggester.Suggestion s)
	{
		String id = s.goalId.substring("suggest:effect:".length());
		if (module.effectsPack() != null)
		{
			for (com.ironhub.data.EffectsPack.Effect e : module.effectsPack().effects)
			{
				if (e.id.equals(id))
				{
					return e.active;
				}
			}
		}
		return "unlock:" + id; // never reached in practice
	}

	// ── archive (depth 2) ──────────────────────────────────────────────

	private void buildArchive()
	{
		JPanel header = row();
		JLabel back = new JLabel(new PaintedIcon(PaintedIcon.Shape.CHEVRON_LEFT, 12));
		back.setForeground(OsrsSkin.MUTED);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.setToolTipText("Back");
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showArchive = false;
				rebuild();
			}
		});
		header.add(back);
		header.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		header.add(new OsrsLabel("Completed", OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		header.add(Box.createHorizontalGlue());
		cap(header);
		content.add(pad(header));
		content.add(strut(3));

		List<PersistedState.GoalRecord> records = state.getGoalRecords();
		if (records.isEmpty())
		{
			content.add(pad(mutedLine("Nothing completed yet.")));
			return;
		}
		content.add(section("THIS MONTH · " + doneThisMonthCount()));
		// newest first
		List<PersistedState.GoalRecord> sorted = new ArrayList<>(records);
		sorted.sort((a, b) -> Long.compare(b.completedAt, a.completedAt));
		for (PersistedState.GoalRecord r : sorted)
		{
			content.add(pad(archiveRow(r)));
		}
		content.add(strut(6));
	}

	private JComponent archiveRow(PersistedState.GoalRecord r)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(LEFT_ALIGNMENT);
		block.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));

		JPanel top = row();
		top.add(new JLabel(Status.OWNED.glyph()));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		top.add(new OsrsLabel(r.name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(r.completedAt == 0 ? "detected" : monthDay(r.completedAt),
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		block.add(top);

		if (r.completedAt != 0 && (r.estimatedHours > 0 || r.hoursAtCompletion > 0))
		{
			String est = r.estimatedHours > 0 ? "est " + compactHours(r.estimatedHours) : "est —";
			JPanel line = row();
			line.add(Box.createHorizontalStrut(UiTokens.STATUS_GLYPH_SIZE + UiTokens.PAD_TIGHT));
			line.add(new OsrsLabel(est + " · took " + compactHours(r.hoursAtCompletion),
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			line.add(Box.createHorizontalGlue());
			block.add(line);
		}
		cap(block);
		return block;
	}

	// ── banner ─────────────────────────────────────────────────────────

	private JComponent updateBanner()
	{
		Plan latest = latestPlan;
		if (latest == null || displayedPlan == null
			|| latest.fingerprint.equals(displayedPlan.fingerprint))
		{
			return null;
		}
		double delta = latest.knownHours - displayedPlan.knownHours;
		StonePanel banner = new StonePanel(theme);
		banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
		banner.setAlignmentX(LEFT_ALIGNMENT);
		String text = Math.abs(delta) < 0.05 ? "Routes updated" : "Routes updated: "
			+ (delta < 0 ? "-" : "+") + compactHours(Math.abs(delta));
		banner.add(new OsrsLabel(text, BANNER_AMBER, OsrsSkin.font()).leftAligned().squeezable());
		banner.add(Box.createHorizontalGlue());
		JLabel apply = new JLabel("apply");
		OsrsSkin.crisp(apply);
		apply.setFont(OsrsSkin.font());
		apply.setForeground(OsrsSkin.TITLE);
		apply.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		apply.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				displayedPlan = latestPlan;
				rebuild();
			}
		});
		banner.add(apply);
		cap(banner);
		return banner;
	}

	// ── step display helpers (mirrors PlannerTab) ──────────────────────

	private String taskName(Plan.Step step)
	{
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN && step.action.trainSkill != null)
		{
			return step.action.trainSkill.getName() + " to " + step.action.trainToLevel;
		}
		return step.action.name;
	}

	private String timeText(Plan.Step step)
	{
		return Double.isNaN(step.hours) ? "?" : "~" + compactHours(step.hours);
	}

	private String servingLine(Plan.Step step)
	{
		List<String> names = new ArrayList<>();
		for (String goalId : step.action.neededBy)
		{
			String n = latestPlan == null ? null : latestPlan.goalNames.get(goalId);
			if (n != null && !names.contains(n))
			{
				names.add(n);
			}
		}
		if (names.isEmpty())
		{
			return "part of your plan";
		}
		return "part of: " + String.join(" · ", names.subList(0, Math.min(2, names.size())));
	}

	private String wikiUrl(Plan.Step step)
	{
		if (step.action.kind == com.ironhub.engine.Action.Kind.QUEST && step.action.questName != null)
		{
			return "https://oldschool.runescape.wiki/w/" + step.action.questName.replace(' ', '_');
		}
		return null;
	}

	private Icon stepIcon(Plan.Step step)
	{
		if (skillIcons != null && step.action.kind == com.ironhub.engine.Action.Kind.TRAIN
			&& step.action.trainSkill != null)
		{
			return new ImageIcon(skillIcons.getSkillImage(step.action.trainSkill, true));
		}
		if (step.action.kind == com.ironhub.engine.Action.Kind.QUEST)
		{
			return OsrsIcons.stat(theme, "quests");
		}
		return null;
	}

	private static final Icon CA_ICON = bundledIcon("/data/icons/combat_achievements.png");
	private static final Icon DIARY_ICON = bundledIcon("/data/icons/achievement_diaries.png");

	private static Icon bundledIcon(String resource)
	{
		java.net.URL url = GoalsHubTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		ImageIcon icon = new ImageIcon(url);
		return new ImageIcon(icon.getImage().getScaledInstance(-1, 15, java.awt.Image.SCALE_SMOOTH));
	}

	/** A route's icon by kind: quest / CA / diary / clue goals wear their
	 *  system's badge; everything else the item sprite. */
	private Icon goalIcon(GoalsPack.Goal goal)
	{
		String id = goal.getId();
		if (id.startsWith("ca:") && CA_ICON != null)
		{
			return CA_ICON;
		}
		if (id.startsWith("diary:") && DIARY_ICON != null)
		{
			return DIARY_ICON;
		}
		if (id.startsWith("quest:") || id.startsWith("custom:quest:"))
		{
			return OsrsIcons.stat(theme, "quests");
		}
		if (id.startsWith("clue:"))
		{
			return OsrsIcons.stat(theme, "collections_logged"); // trails feed the log
		}
		if (itemManager != null && goal.icon() != null)
		{
			return new ImageIcon(itemManager.getImage(goal.icon()));
		}
		return null;
	}

	private static String monthDay(long epochMs)
	{
		java.time.LocalDate d = java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
		return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
	}

	private static String compactHours(double hours)
	{
		if (Double.isNaN(hours))
		{
			return "?";
		}
		if (hours < 1)
		{
			return Math.max(1, Math.round(hours * 60)) + "m";
		}
		return Math.round(hours * 10) / 10.0 + "h";
	}

	private static String compactXp(long xp)
	{
		if (xp >= 1_000_000)
		{
			return Math.round(xp / 100_000.0) / 10.0 + "M";
		}
		if (xp >= 1000)
		{
			return Math.round(xp / 1000.0) + "k";
		}
		return String.valueOf(xp);
	}

	// ── layout atoms (the mockup grammar) ──────────────────────────────

	private JMenuItem item(String label, Runnable onClick)
	{
		JMenuItem mi = new JMenuItem(label);
		mi.addActionListener(e -> onClick.run());
		return mi;
	}

	private JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(8, 8, 3, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A collapsible Goal-category header (6): a triangle + name; the click
	 *  hides/shows the category's routes. */
	private JComponent category(String text, int count)
	{
		boolean collapsed = collapsedCategories.contains(text);
		JPanel row = row();
		row.setBorder(new EmptyBorder(3, 8, 2, 8));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new PaintedIcon(
			collapsed ? PaintedIcon.Shape.TRIANGLE_RIGHT : PaintedIcon.Shape.TRIANGLE_DOWN, 8));
		triangle.setForeground(OsrsSkin.FAINT);
		row.add(triangle);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		row.add(new OsrsLabel(text.toUpperCase(Locale.ROOT), OsrsSkin.FAINT, OsrsSkin.smallFont()));
		row.add(Box.createHorizontalGlue());
		if (collapsed)
		{
			row.add(new OsrsLabel(String.valueOf(count), OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (collapsed)
				{
					collapsedCategories.remove(text);
				}
				else
				{
					collapsedCategories.add(text);
				}
				rebuild();
			}
		};
		row.addMouseListener(click);
		for (java.awt.Component sub : row.getComponents())
		{
			sub.addMouseListener(click);
		}
		cap(row);
		return pad(row);
	}

	private JComponent moreLine(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP + 24, 2, 0));
		row.add(new OsrsLabel(text, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent mutedLine(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, 0));
		row.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent pair(StatBox left, StatBox right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		cap(row);
		return row;
	}

	private JComponent centered(JComponent inner)
	{
		JPanel holder = row();
		holder.add(Box.createHorizontalGlue());
		holder.add(inner);
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new java.awt.BorderLayout());
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

	private void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
