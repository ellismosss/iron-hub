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

	/** Priority left-edge colours (Luke, G7 round 2): slightly-darker
	 *  red/orange/green for High/Medium/Low; normal (unset) shows no edge. */
	private static final Color EDGE_HIGH = new Color(0x9E2B2B);
	private static final Color EDGE_MEDIUM = new Color(0xB5701C);
	private static final Color EDGE_LOW = new Color(0x3E7A34);

	/** Goal/task row icon height — a small, consistent size for every icon
	 *  (system badges AND item sprites), the tidy small variation Luke prefers. */
	private static final int BADGE_H = 18;
	/** The larger icon in the CURRENT TASK hero tile (Luke). */
	private static final int CURRENT_ICON_H = 34;

	/** Clue-scroll sprite per tier, for a clue Route's icon (16). */
	private static final Map<String, Integer> CLUE_TIER_ITEM = Map.of(
		"beginner", 23182, "easy", 2677, "medium", 2801,
		"hard", 2722, "elite", 12073, "master", 19835);

	private final GoalPlannerModule module;
	private final AccountState state;
	private final GoalsPack pack;
	private final GearProgressionPack gearPack;
	private final net.runelite.client.game.ItemManager itemManager; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless
	private final OsrsTheme theme;

	private final JPanel content = new JPanel();
	private final Runnable rebuildGate = RebuildGate.install(this, this::rebuild);
	/** Async item sprites via the cache (2026-07-20 audit: sized(getImage)
	 *  re-scaled per rebuild and snapshotted still-loading sprites). */
	private com.ironhub.ui.components.SpriteCache sprites;
	private final Runnable planListener;

	/** One expanded Route at a time; the completed-archive depth-2 view. */
	private String expandedRoute;
	private boolean showArchive;
	/** Live add-goal candidates (id → name), rendered under the search field. */
	private final Map<String, String> searchResults = new LinkedHashMap<>();
	/** The search field + results panel are LONG-LIVED (created once, re-added
	 *  on rebuild): typing updates only the results panel, so a keystroke never
	 *  destroys the focused field (Luke's de-focus bug). */
	private StoneTextField searchField;
	private final JPanel searchResultsPanel = new JPanel();
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

		// 3 · add goal — above the GOALS header (15). The field + results panel
		// are long-lived so typing doesn't rebuild (and de-focus) the field.
		content.add(strut(4));
		content.add(pad(addGoalField()));
		content.add(searchResultsPanel);
		renderSearchResults();

		// 4 · goals, grouped by category, each category on its own stone slab (10)
		content.add(section("GOALS"));
		JComponent banner = updateBanner();
		if (banner != null)
		{
			content.add(pad(banner));
			content.add(strut(3));
		}
		if (routes.isEmpty())
		{
			content.add(pad(mutedLine("No routes yet — add a goal above.")));
		}
		else
		{
			for (Map.Entry<String, List<GoalsPack.Goal>> e : groupByCategory(routes).entrySet())
			{
				// full-width, matching the main section's header plate (no extra
				// pad inset) so the rows get the most text space (Luke)
				content.add(categorySlab(e.getKey(), e.getValue()));
				content.add(strut(3));
			}
		}

		// 5 · suggestions
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
		for (String category : List.of("Quests", "Gear", "Skills", "Unlocks", "Supplies", "Other"))
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
			return "Skills";
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
			case "boat":
			case "clue":
				return "Unlocks";
			default:
				return "Other";
		}
	}

	private StatBox doneThisMonth()
	{
		StatBox box = new StatBox(theme, "Completed\nthis month:",
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
		// a »» marker points at the current task (Luke, green_right_double)
		Icon chevron = doubleChevron(true);
		if (chevron != null)
		{
			title.add(new JLabel(chevron));
			title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		Icon icon = stepIcon(step, CURRENT_ICON_H); // large in the hero tile (Luke)
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
		boolean farmRuns = timeText(step).endsWith(" runs");
		boolean personal = step.action.kind == com.ironhub.engine.Action.Kind.TRAIN
			&& state.measuredRate(step.action.trainSkill) > 0;
		pace.setToolTipText((farmRuns
			? "At your measured " + compactXp(Math.round(avgFarmingXpPerRun()))
				+ " xp/run — calendar time, minutes of attention per run"
			: personal
			? "At your measured pace (" + compactXp(Math.round(state.measuredRate(step.action.trainSkill))) + "/hr observed)"
			: "At book pace — your own rate measures in as you train")
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
		boolean low = "low".equals(tier) || "someday".equals(tier); // dimmed + sunk
		boolean expanded = id.equals(expandedRoute);
		List<Plan.Step> slice = routeSlice(route);
		boolean single = slice.size() <= 1; // a one-task goal needs no meter/count
		// flat row on the slab (no nested stone border — frees text width);
		// pinned rows fill selectFill, a 1px darker left strip marks priority
		JPanel card = new JPanel();
		card.setOpaque(pinned);
		if (pinned)
		{
			card.setBackground(theme.selectFill);
		}
		Color edge = "high".equals(tier) ? EDGE_HIGH
			: "medium".equals(tier) ? EDGE_MEDIUM : low ? EDGE_LOW : null;
		javax.swing.border.Border inner = new EmptyBorder(1, edge != null ? 3 : 2, 1, 2);
		card.setBorder(edge != null ? new javax.swing.border.CompoundBorder(
			new javax.swing.border.MatteBorder(0, 1, 0, 0, edge), inner) : inner);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel top = row();
		JLabel triangle = new JLabel(new PaintedIcon(
			expanded ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(low ? OsrsSkin.FAINT : OsrsSkin.MUTED);
		top.add(triangle);
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		Icon icon = goalIcon(route);
		if (icon != null)
		{
			top.add(new JLabel(icon));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		Color nameColor = low ? OsrsSkin.FAINT : pinned ? OsrsSkin.TITLE : OsrsSkin.LABEL;
		// skill-level Goals read as the level alone — the icon names the skill (12)
		top.add(new OsrsLabel(routeName(route), nameColor,
			pinned ? OsrsSkin.boldFont() : OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		if (!single && !low)
		{
			top.add(new OsrsLabel(String.valueOf(slice.size()), OsrsSkin.FAINT, OsrsSkin.smallFont()));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		// pin (5) + remove-goal × (9) affordances on every tile
		top.add(pinGlyph(id, pinned));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		top.add(removeGoalGlyph(id));
		card.add(top);
		if (!single && !low)
		{
			card.add(Box.createVerticalStrut(3));
			// notches divide the bar by the number of unique tasks (Luke)
			card.add(new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE,
				GoalPlannerModule.progress(route, state)).segments(slice.size()));
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

	/** A clickable pin icon (title when pinned, faint when not). Only one goal
	 *  OR task is pinned at a time — the state enforces it. */
	private JLabel pinGlyph(String goalId, boolean pinned)
	{
		JLabel pin = new JLabel(doubleChevron(pinned));
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

	/** A clean red-× to remove a Goal — a painted glyph, brighter on hover
	 *  (no ugly boxed border; every Goal tile carries one, tasks do not). */
	private JLabel removeGoalGlyph(String goalId)
	{
		JLabel x = new JLabel(new PaintedIcon(PaintedIcon.Shape.CROSS, 11));
		x.setForeground(UiTokens.STATUS_WARNING.darker());
		x.setToolTipText("Remove this goal");
		x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		x.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				x.setForeground(UiTokens.STATUS_WARNING);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				x.setForeground(UiTokens.STATUS_WARNING.darker());
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				GoalPlannerModule.removeGoal(state, goalId);
				e.consume();
			}
		});
		return x;
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
	 *  estimated time and recommended method — added into the category slab. */
	private void addTasks(GoalsPack.Goal route, javax.swing.JComponent target)
	{
		// a Supplies Route shows the component materials it decomposes to (Luke)
		if ("Supplies".equals(categoryOf(route.getId())) && addSupplyMaterials(route, target))
		{
			return;
		}
		List<Plan.Step> slice = routeSlice(route);
		if (slice.isEmpty())
		{
			// no plan yet, or the only work is the goal's own manual proof
			for (CompiledStep step : GoalPlannerModule.compile(route, state))
			{
				if (!step.met)
				{
					target.add(manualTaskRow(step));
				}
			}
			return;
		}
		int shown = 0;
		boolean current = true;
		for (Plan.Step step : slice)
		{
			target.add(taskRow(step, current, route));
			current = false;
			if (++shown >= 8 && slice.size() > shown)
			{
				target.add(moreLine("+ " + (slice.size() - shown) + " more"));
				break;
			}
		}
	}

	/** One Task from the plan slice: name + time, with the recommended
	 *  method (or drop rate) on a faint sub-line. A task can be pinned as the
	 *  single active thing (2), and its wiki opens on right-click. */
	private JComponent taskRow(Plan.Step step, boolean current, GoalsPack.Goal route)
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
		// Task-tile text is the small font (Goal tiles + CURRENT TASK keep the
		// regular font) — Luke, to fit more in the row
		OsrsLabel name = new OsrsLabel(taskName(step, route), current ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.smallFont()).leftAligned().squeezable();
		line.add(name);
		line.add(Box.createHorizontalGlue());
		line.add(new OsrsLabel(timeText(step), OsrsSkin.MUTED, OsrsSkin.smallFont()));
		line.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		line.add(taskPinGlyph(step.action.id));
		block.add(line);

		String sub = taskSubLine(step, route);
		if (sub != null)
		{
			JPanel s = row();
			s.add(Box.createHorizontalStrut(UiTokens.STATUS_GLYPH_SIZE + UiTokens.PAD_TIGHT));
			s.add(new OsrsLabel(sub, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned().squeezable());
			s.add(Box.createHorizontalGlue());
			block.add(s);
		}
		// right-click any task → open its wiki page
		String url = wikiUrl(step);
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

	/** A task's pin affordance — pins the plan step as the single active thing
	 *  (clears any goal/other-task pin via the state). */
	private JLabel taskPinGlyph(String actionId)
	{
		boolean pinned = state.isTaskPinned(actionId);
		JLabel pin = new JLabel(doubleChevron(pinned));
		pin.setToolTipText(pinned ? "Pinned as active — click to unpin" : "Pin this task as active");
		pin.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		pin.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.setTaskPinned(actionId, !pinned);
				e.consume();
			}
		});
		return pin;
	}

	/** The faint sub-line for a Task: the recommended method + xp/hr (TRAIN),
	 *  the drop odds + source (OBTAIN/KILL), the unlucky spread, or nothing.
	 *  (Supplies Routes render their materials separately, not here.) */
	private String taskSubLine(Plan.Step step, GoalsPack.Goal route)
	{
		// the TRAIN task name IS the method (11), so the sub-line is just the
		// rate — never "via <method>" again
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
		{
			return step.methodRate > 0 ? compactXp(step.methodRate) + " xp/hr" : null;
		}
		// drop rate + source for obtained items (6) — 1/N · Source
		if (step.action.kind == com.ironhub.engine.Action.Kind.OBTAIN
			&& step.action.itemId > 0 && module.ratesSource() != null)
		{
			String drop = module.ratesSource().dropLabel(step.action.itemId);
			if (drop != null)
			{
				return drop + (!Double.isNaN(step.spreadHours) && step.spreadHours > step.hours + 0.05
					? " · up to ~" + compactHours(step.spreadHours) + " unlucky" : "");
			}
		}
		if (!Double.isNaN(step.spreadHours) && step.spreadHours > step.hours + 0.05)
		{
			return "up to ~" + compactHours(step.spreadHours) + " if unlucky";
		}
		return null;
	}

	/** {itemId, qty} a supply Route stocks, from its {@code item:<id>:<qty>}
	 *  step, or null. */
	private int[] supplySpec(GoalsPack.Goal route)
	{
		for (GoalsPack.Step s : route.getSteps())
		{
			String req = s.getRequirement();
			if (req == null || !req.startsWith("item:"))
			{
				continue;
			}
			String[] p = req.split(":");
			if (p.length >= 3)
			{
				try
				{
					return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])};
				}
				catch (NumberFormatException ignored)
				{
					return null;
				}
			}
		}
		return null;
	}

	/** Render a supply Route's component materials — the stocked item
	 *  decomposed to raw materials, but BANK-AWARE (Luke): materials you
	 *  already own stop the walk, so only what you actually need to gather
	 *  shows. Returns false to fall through to the normal plan slice. */
	private boolean addSupplyMaterials(GoalsPack.Goal route, javax.swing.JComponent target)
	{
		int[] spec = supplySpec(route);
		com.ironhub.data.RecipesPack recipes = module.recipesPack();
		if (spec == null || recipes == null)
		{
			return false;
		}
		int itemId = spec[0];
		int qty = spec[1];
		// spend what's in the bank/inventory first, down the recipe tree
		Map<Integer, Integer> mats = recipes.gather(itemId, qty, state::canonicalStock);
		if (mats.isEmpty())
		{
			target.add(materialRow(0, "You own everything to stock " + qty + "."));
			return true;
		}
		target.add(materialRow(0, "Gather:"));
		int shown = 0;
		for (Map.Entry<Integer, Integer> e : mats.entrySet())
		{
			String name = recipes.name(e.getKey());
			if (name == null)
			{
				name = state.itemName(e.getKey());
			}
			target.add(materialRow(e.getKey(), e.getValue() + " × " + name));
			if (++shown >= 12 && mats.size() > shown)
			{
				target.add(moreLine("+ " + (mats.size() - shown) + " more"));
				break;
			}
		}
		return true;
	}

	/** One indented material row: an item sprite (itemId > 0) + faint text. */
	private JComponent materialRow(int itemId, String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP + 6, 1, UiTokens.ROW_GAP));
		if (itemId > 0 && itemManager != null)
		{
			row.add(new JLabel(itemIcon(itemId, BADGE_H)));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A remaining manual checklist step when there's no plan slice. */
	private JComponent manualTaskRow(CompiledStep step)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP + 6, 2, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(step.label, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
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
		for (String tier : List.of("high", "medium", "low"))
		{
			// a native checkbox item marks the current tier — no font glyph;
			// re-picking the current tier resets to normal (18)
			boolean isCurrent = state.getGoalPriority(id).equals(tier);
			javax.swing.JCheckBoxMenuItem mi = new javax.swing.JCheckBoxMenuItem(
				"Priority: " + tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1), isCurrent);
			mi.addActionListener(e -> state.setGoalPriority(id, isCurrent ? "normal" : tier));
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

	/** The best wiki page for a Goal: its quest, its stocked item, or its name. */
	String goalWiki(GoalsPack.Goal route)
	{
		String id = route.getId();
		String page = null;
		if (id.startsWith("custom:quest:") || id.startsWith("quest:"))
		{
			page = route.getName();
		}
		else if (id.startsWith("supply:"))
		{
			// the stocked ITEM's page, not the "Stock N × …" goal name
			page = supplyItemName(route);
		}
		else if (route.icon() != null || id.startsWith("gear:") || id.startsWith("clog:"))
		{
			page = route.getName();
		}
		return page == null ? null : "https://oldschool.runescape.wiki/w/" + page.replace(' ', '_');
	}

	/** The item a supply Route stocks, from its {@code item:<id>:<qty>:<name>}
	 *  step (falls back to stripping the "Stock N × " goal-name prefix). */
	private String supplyItemName(GoalsPack.Goal route)
	{
		for (GoalsPack.Step s : route.getSteps())
		{
			String req = s.getRequirement();
			if (req != null && req.startsWith("item:"))
			{
				String[] p = req.split(":", 4);
				if (p.length >= 4)
				{
					return p[3];
				}
			}
		}
		return route.getName().replaceFirst("^Stock \\d+ × ", "");
	}

	// ── add goal ───────────────────────────────────────────────────────

	private JComponent addGoalField()
	{
		if (searchField == null)
		{
			searchField = new StoneTextField(theme, "Add a goal — search…");
			searchResultsPanel.setLayout(new BoxLayout(searchResultsPanel, BoxLayout.Y_AXIS));
			searchResultsPanel.setOpaque(false);
			searchResultsPanel.setAlignmentX(LEFT_ALIGNMENT);
			searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
			{
				@Override
				public void insertUpdate(javax.swing.event.DocumentEvent e)
				{
					refreshSearch(searchField.getText());
				}

				@Override
				public void removeUpdate(javax.swing.event.DocumentEvent e)
				{
					refreshSearch(searchField.getText());
				}

				@Override
				public void changedUpdate(javax.swing.event.DocumentEvent e)
				{
				}
			});
		}
		return searchField;
	}

	/** Repaint the results panel only — never a full rebuild, so the field
	 *  keeps focus while typing. */
	private void renderSearchResults()
	{
		searchResultsPanel.removeAll();
		for (Map.Entry<String, String> e : searchResults.entrySet())
		{
			searchResultsPanel.add(pad(searchResultRow(e.getKey(), e.getValue())));
		}
		searchResultsPanel.revalidate();
		searchResultsPanel.repaint();
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
		renderSearchResults(); // panel-only update — keeps the field focused
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
			goalId = id; // the seed the estimate attaches to
		}
		else
		{
			state.selectGoal(goalId, true); // gear/pack goals: just select
		}
		module.captureEstimate(goalId); // archive "est" figure, off the EDT
		searchResults.clear();
		if (searchField != null)
		{
			searchField.setText(""); // clears the field + its results after adding
		}
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

	/** The current-task headline: for TRAIN, the method (the skill icon names
	 *  the skill — no skill word, 12); otherwise the action name. */
	private String taskName(Plan.Step step)
	{
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
		{
			return step.methodName != null ? step.methodName : "Train to " + step.action.trainToLevel;
		}
		return step.action.name;
	}

	/** A Task's name inside a Route: TRAIN reads as the method only (11/12);
	 *  the Route's OWN quest reads "Complete the quest" (4); a prerequisite
	 *  quest keeps its name. */
	private String taskName(Plan.Step step, GoalsPack.Goal route)
	{
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN)
		{
			return step.methodName != null ? step.methodName : "to " + step.action.trainToLevel;
		}
		if (step.action.kind == com.ironhub.engine.Action.Kind.QUEST && route != null
			&& step.action.questName != null && step.action.questName.equalsIgnoreCase(route.getName()))
		{
			return "Complete the quest";
		}
		return step.action.name;
	}

	/** The Route-row label: a skill-level Goal reads as its level alone (the
	 *  icon names the skill, 12); everything else its full name. */
	private String routeName(GoalsPack.Goal route)
	{
		int level = goalLevel(route.getId());
		return level > 0 ? String.valueOf(level) : route.getName();
	}

	private static boolean isLevelGoal(String id)
	{
		return id.startsWith("custom:skill:") || id.startsWith("skill:");
	}

	/** The Skill a skill-level Goal targets (custom:skill:agility:70 /
	 *  skill:Agility:70), or null. */
	private static Skill goalSkill(String id)
	{
		if (!isLevelGoal(id))
		{
			return null;
		}
		String[] parts = id.split(":");
		if (parts.length < 3)
		{
			return null;
		}
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(parts[2]))
			{
				return skill;
			}
		}
		return null;
	}

	/** The target level of a skill-level Goal (its trailing number), or 0. */
	private static int goalLevel(String id)
	{
		if (!isLevelGoal(id))
		{
			return 0;
		}
		String[] parts = id.split(":");
		try
		{
			return Integer.parseInt(parts[parts.length - 1]);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private String timeText(Plan.Step step)
	{
		// Farming is calendar work, not grind hours: with measured run
		// records the honest figure is YOUR runs, not the misleading
		// "135h at Tithe Farm" the active-methods ladder produces
		// (2026-07-20 intelligence arc — attention-typed time)
		if (step.action.kind == com.ironhub.engine.Action.Kind.TRAIN
			&& step.action.trainSkill == Skill.FARMING
			&& step.trainXpRemaining > 0)
		{
			double perRun = avgFarmingXpPerRun();
			if (perRun > 0)
			{
				return "~" + (long) Math.ceil(step.trainXpRemaining / perRun) + " runs";
			}
		}
		return Double.isNaN(step.hours) ? "?" : "~" + compactHours(step.hours);
	}

	/**
	 * Mean Farming xp per completed run over the recent record log
	 * (last 20; zero-xp records skipped), or 0 with no history — the
	 * figure the runs-to-level line on the farming tab already earns.
	 */
	double avgFarmingXpPerRun()
	{
		List<com.ironhub.state.PersistedState.FarmRunRecord> log = state.getFarmRunLog();
		double total = 0;
		int counted = 0;
		for (int i = Math.max(0, log.size() - 20); i < log.size(); i++)
		{
			long xp = log.get(i).xpByBucket.values().stream().mapToLong(Integer::longValue).sum();
			if (xp > 0)
			{
				total += xp;
				counted++;
			}
		}
		return counted == 0 ? 0 : total / counted;
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
		return stepIcon(step, BADGE_H);
	}

	private Icon stepIcon(Plan.Step step, int height)
	{
		if (skillIcons != null && step.action.kind == com.ironhub.engine.Action.Kind.TRAIN
			&& step.action.trainSkill != null)
		{
			return sized(skillIcons.getSkillImage(step.action.trainSkill, false), height);
		}
		if (step.action.kind == com.ironhub.engine.Action.Kind.QUEST)
		{
			return sized(OsrsIcons.image(theme, "quests"), height);
		}
		if (step.action.kind == com.ironhub.engine.Action.Kind.OBTAIN && itemManager != null
			&& step.action.itemId > 0)
		{
			return itemIcon(step.action.itemId, height);
		}
		return null;
	}

	/** The green »» double-chevron sprite (marks the current/active thing),
	 *  themed; dimmed to a "ghost" affordance for the un-pinned state.
	 *  Composited once per state — this ran a fresh BufferedImage +
	 *  Graphics2D per route row per rebuild (2026-07-20 audit). */
	private Icon chevronBright;
	private Icon chevronDim;

	private Icon doubleChevron(boolean bright)
	{
		if (bright && chevronBright != null)
		{
			return chevronBright;
		}
		if (!bright && chevronDim != null)
		{
			return chevronDim;
		}
		java.awt.image.BufferedImage img = OsrsIcons.image(theme, "green_right_double");
		if (img == null)
		{
			return null;
		}
		if (bright)
		{
			chevronBright = new ImageIcon(img);
			return chevronBright;
		}
		java.awt.image.BufferedImage dim = new java.awt.image.BufferedImage(
			img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = dim.createGraphics();
		g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.30f));
		g.drawImage(img, 0, 0, null);
		g.dispose();
		chevronDim = new ImageIcon(dim);
		return chevronDim;
	}

	private static final java.awt.image.BufferedImage CA_IMG = bundledImage("/data/icons/combat_achievements.png");
	private static final java.awt.image.BufferedImage DIARY_IMG = bundledImage("/data/icons/achievement_diaries.png");

	private static java.awt.image.BufferedImage bundledImage(String resource)
	{
		java.net.URL url = GoalsHubTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		try
		{
			return javax.imageio.ImageIO.read(url);
		}
		catch (java.io.IOException e)
		{
			return null;
		}
	}

	/** Scale an image to the shared goal-row icon height (1), or null. */
	private static Icon sized(java.awt.Image img)
	{
		return sized(img, BADGE_H);
	}

	/** Async item sprite at the given height, or null until it arrives
	 *  (the cache's gated rebuild picks it up). */
	private Icon itemIcon(int itemId, int height)
	{
		if (sprites == null)
		{
			sprites = new com.ironhub.ui.components.SpriteCache(itemManager, rebuildGate);
		}
		java.awt.Image img = sprites.get(itemId, -1, height);
		return img == null ? null : new ImageIcon(img);
	}

	private static Icon sized(java.awt.Image img, int height)
	{
		return img == null ? null
			: new ImageIcon(img.getScaledInstance(-1, height, java.awt.Image.SCALE_SMOOTH));
	}

	/** A route's icon by kind — every badge scaled to the item-sprite height
	 *  (1): skill-level → skill icon (7), quest / CA / diary badges, clue →
	 *  its tier's scroll (16); everything else the item sprite. */
	private Icon goalIcon(GoalsPack.Goal goal)
	{
		String id = goal.getId();
		if (isLevelGoal(id) && skillIcons != null)
		{
			Skill skill = goalSkill(id);
			if (skill != null)
			{
				return sized(skillIcons.getSkillImage(skill, false));
			}
		}
		if (id.startsWith("ca:"))
		{
			return sized(CA_IMG);
		}
		if (id.startsWith("diary:"))
		{
			return sized(DIARY_IMG);
		}
		if (id.startsWith("quest:") || id.startsWith("custom:quest:"))
		{
			return sized(OsrsIcons.image(theme, "quests"));
		}
		if (id.startsWith("clue:") && itemManager != null)
		{
			int scroll = clueTierItem(goal.getName());
			if (scroll > 0)
			{
				return itemIcon(scroll, BADGE_H);
			}
		}
		if (itemManager != null && goal.icon() != null)
		{
			return itemIcon(goal.icon(), BADGE_H);
		}
		return null;
	}

	/** The clue-scroll sprite id for a clue Route, read from its tier-prefixed
	 *  name ("Hard clue step: …"), or 0 when the tier isn't recognised. */
	private static int clueTierItem(String name)
	{
		if (name == null)
		{
			return 0;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Integer> e : CLUE_TIER_ITEM.entrySet())
		{
			if (lower.startsWith(e.getKey()))
			{
				return e.getValue();
			}
		}
		return 0;
	}

	private static String monthDay(long epochMs)
	{
		java.time.LocalDate d = java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
		return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
	}

	private static String compactHours(double hours)
	{
		return PlannerOverlay.compactHours(hours); // one format — see PlannerOverlay
	}

	private static String compactXp(long xp)
	{
		return PlannerOverlay.compactXp(xp);
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

	/** A whole Goal-category on one stone slab (10): the collapsible header,
	 *  then the category's Route rows (each expandable to its Tasks). */
	private JComponent categorySlab(String name, List<GoalsPack.Goal> routesInCat)
	{
		StonePanel slab = new StonePanel(theme);
		slab.setLayout(new BoxLayout(slab, BoxLayout.Y_AXIS));
		slab.setAlignmentX(LEFT_ALIGNMENT);
		// inner padding inside the engraved edge; the flat rows fill the rest
		slab.setBorder(new javax.swing.border.CompoundBorder(slab.getBorder(),
			new EmptyBorder(3, 4, 4, 4)));
		slab.add(categoryHeader(name, routesInCat.size()));
		if (!collapsedCategories.contains(name))
		{
			for (GoalsPack.Goal route : routesInCat)
			{
				slab.add(strut(3));
				slab.add(routeRow(route));
				if (route.getId().equals(expandedRoute))
				{
					addTasks(route, slab);
				}
			}
		}
		cap(slab);
		return slab;
	}

	/** The collapsible category header — medium (16px) text (8), a triangle,
	 *  and the click that hides/shows the category's routes (6). */
	private JComponent categoryHeader(String text, int count)
	{
		boolean collapsed = collapsedCategories.contains(text);
		JPanel row = row();
		row.setBorder(new EmptyBorder(1, 1, 3, 1));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new PaintedIcon(
			collapsed ? PaintedIcon.Shape.TRIANGLE_RIGHT : PaintedIcon.Shape.TRIANGLE_DOWN, 9));
		triangle.setForeground(OsrsSkin.MUTED);
		row.add(triangle);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font())); // sentence case (Luke)
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
		return row;
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
