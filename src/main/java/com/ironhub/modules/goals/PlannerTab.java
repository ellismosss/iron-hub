package com.ironhub.modules.goals;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.engine.Action;
import com.ironhub.engine.Plan;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.api.Skill;
import net.runelite.client.util.LinkBrowser;

/**
 * The goal planner's three views over one plan (design/PLANNER-UX.md,
 * frames 4a–4f) in the OSRS stonework skin: Today (session view — budget
 * chips, NOW card, up next, passive lane), Route (the chaptered full plan
 * with a no-silent-reshuffle update banner and explain-card expands), and
 * Goals (add-goal search with live merge preview + the proven goals list).
 * Reorder by constraint: pin / snooze / ban via right-click or the explain
 * card — never by drag. Route steps are flat colour-coded rows, the game's
 * own quest-list idiom; frameless — the hub provides frame and header plate.
 */
class PlannerTab extends JPanel
{
	private static final String[] BUDGETS = {"30m", "1h", "2h+", "AFK"};
	private static final double[] BUDGET_HOURS = {0.55, 1.3, Double.MAX_VALUE, Double.MAX_VALUE};
	/** Wrap widths: free-standing notes vs text inside a stone card. */
	private static final int NOTE_WIDTH = 195;
	private static final int CARD_TEXT_WIDTH = 180;

	private final GoalPlannerModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final GoalsTab goalsTab; // the proven 2d list, embedded as the Goals view

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final StoneChipRow views;
	private final JPanel todayView = column();
	private final JPanel routeView = column();
	private final JPanel goalsView = column();
	private final StoneChipRow budgetChips;

	/** The plan the Route list currently shows (never silently replaced). */
	private Plan displayedPlan;
	/** Set when the player changes a constraint: their own action applies. */
	private boolean applyNextPlan;
	/** The freshest plan from the engine (may await an explicit apply). */
	private Plan latestPlan;
	private String expandedStepId;
	private boolean snoozedOpen;
	private GoalsPack.Goal previewCandidate;
	private GoalPlannerModule.MergePreview preview;

	/** Visibility-gated: replans land every few seconds during play, and a
	 *  hidden planner must not rebuild three views each time (freeze audit). */
	private final Runnable planListener =
		com.ironhub.ui.components.RebuildGate.install(this, this::refreshFromModule);

	private void refreshFromModule()
	{
		onPlanUpdated(module.currentPlan());
	}

	private final net.runelite.client.game.ItemManager itemManager;
	private final net.runelite.client.game.SkillIconManager skillIconManager;

	PlannerTab(GoalPlannerModule module, AccountState state, GoalsPack pack,
		GearProgressionPack gearPack, net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIconManager, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.goalsTab = new GoalsTab(state, pack, gearPack, itemManager, theme);
		this.views = new StoneChipRow(theme, true, "Today", "Route", "Goals");
		this.budgetChips = new StoneChipRow(theme, false, BUDGETS);
		this.addSearch = new StoneTextField(theme, "Add a goal — search…");
		this.addSearch.setToolTipText("Search goals, gear, or type a skill level like \"agility 70\"");

		setLayout(new BorderLayout());
		setOpaque(true);
		setBackground(theme.background);

		JPanel header = column();
		header.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, 0, UiTokens.PAD));
		views.onChange(i -> showView(i));
		header.add(views);
		header.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		add(header, BorderLayout.NORTH);

		content.setOpaque(false);
		content.add(wrap(todayView), "0");
		content.add(wrap(routeView), "1");
		content.add(wrap(goalsView), "2");
		add(content, BorderLayout.CENTER);

		budgetChips.setSelected(1);
		budgetChips.onChange(i -> rebuildToday());

		module.addPlanListener(planListener);
		rebuildAll();
	}

	void dispose()
	{
		module.removePlanListener(planListener);
		goalsTab.dispose();
	}

	private void showView(int index)
	{
		cards.show(content, String.valueOf(index));
	}

	/** Test seam: switch views without a mouse. */
	void showViewForTest(int index)
	{
		views.setSelected(index);
		showView(index);
	}

	/** Test seam: expand a step's explain card in Route. */
	void expandForTest(String actionId)
	{
		expandedStepId = actionId;
		rebuildRoute();
	}

	/** New plan from the engine. Today follows live; Route waits for apply. */
	void onPlanUpdated(Plan plan)
	{
		if (plan == null)
		{
			return;
		}
		latestPlan = plan;
		if (displayedPlan == null || applyNextPlan)
		{
			displayedPlan = plan;
			applyNextPlan = false;
		}
		rebuildAll();
	}

	private void rebuildAll()
	{
		rebuildToday();
		rebuildRoute();
		rebuildGoals();
	}

	// ── Today (frame 4a) ───────────────────────────────────────────────

	private void rebuildToday()
	{
		todayView.removeAll();
		Plan plan = latestPlan; // Today always follows the freshest plan

		todayView.add(horizonRow(plan));
		todayView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		todayView.add(budgetChips);
		todayView.add(Box.createVerticalStrut(UiTokens.PAD));

		if (plan == null || plan.steps.isEmpty())
		{
			todayView.add(mutedNote(plan == null
				? "Planning…" : "No goals selected — add one under Goals."));
		}
		else
		{
			List<Plan.Step> fitting = fittingSteps(plan);
			todayView.add(section("Now"));
			if (fitting.isEmpty())
			{
				todayView.add(mutedNote("Nothing fits this budget — try a bigger one."));
			}
			else
			{
				todayView.add(nowCard(fitting.get(0)));
				if (fitting.size() > 1)
				{
					todayView.add(Box.createVerticalStrut(UiTokens.PAD));
					todayView.add(section("Up next"));
					for (int i = 1; i < Math.min(4, fitting.size()); i++)
					{
						todayView.add(denseRow(fitting.get(i), -1, false));
						todayView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
					}
				}
			}
			JComponent lane = passiveLane(plan);
			if (lane != null)
			{
				todayView.add(Box.createVerticalStrut(UiTokens.PAD));
				todayView.add(section("Passive lane"));
				todayView.add(lane);
			}
			JComponent session = sinceLastSession(plan);
			if (session != null)
			{
				todayView.add(Box.createVerticalStrut(UiTokens.PAD));
				todayView.add(session);
			}
		}
		todayView.add(Box.createVerticalGlue());
		todayView.revalidate();
		todayView.repaint();
	}

	private JComponent horizonRow(Plan plan)
	{
		JPanel row = row();
		OsrsLabel total = new OsrsLabel(plan == null ? "All goals · —" : "All goals · " + totalText(plan),
			OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned().squeezable();
		total.setToolTipText("Known-time steps only; \"+n?\" steps have no honest estimate yet");
		row.add(total);
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private List<Plan.Step> fittingSteps(Plan plan)
	{
		int budget = budgetChips.getSelected();
		List<Plan.Step> out = new ArrayList<>();
		for (Plan.Step step : plan.steps)
		{
			if (step.snoozed)
			{
				continue;
			}
			boolean fits;
			if (budget == 3) // AFK
			{
				fits = "afk".equals(step.methodStyle) || "semi".equals(step.methodStyle);
			}
			else
			{
				fits = Double.isNaN(step.hours) ? budget == 2 : step.hours <= BUDGET_HOURS[budget];
			}
			if (fits)
			{
				out.add(step);
			}
		}
		// nothing fits a small budget: fall back to the true plan head
		if (out.isEmpty() && budget != 3 && !plan.steps.isEmpty() && plan.head() != null)
		{
			out.add(plan.head());
		}
		return out;
	}

	/** The emphasized card of the Today view: the select-fill band (the same
	 *  "do this now" reading as the dailies run's next stop). */
	private JComponent nowCard(Plan.Step step)
	{
		StonePanel card = new StonePanel(theme);
		card.setBackground(theme.selectFill);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel title = row();
		OsrsLabel name = new OsrsLabel(step.action.name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		title.add(name);
		title.add(Box.createHorizontalGlue());
		title.add(timeLabel(step, OsrsSkin.TITLE, true));
		card.add(title);

		card.add(wrapped(step.why, OsrsSkin.MUTED));

		JPanel foot = row();
		OsrsLabel serves = new OsrsLabel("serves " + step.action.neededBy.size()
			+ (step.action.neededBy.size() == 1 ? " goal" : " goals"),
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned();
		serves.setToolTipText(servedGoalNames(step));
		foot.add(serves);
		foot.add(Box.createHorizontalGlue());
		String nowWiki = wikiUrl(step);
		if (nowWiki != null)
		{
			StoneButton wiki = new StoneButton(theme, theme.selectFill, "Wiki",
				() -> LinkBrowser.browse(nowWiki));
			wiki.setToolTipText("Open the wiki page");
			wiki.setMaximumSize(wiki.getPreferredSize());
			foot.add(wiki);
		}
		card.add(foot);
		MouseAdapter open = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				views.setSelected(1);
				showView(1);
				expandedStepId = step.action.id;
				rebuildRoute();
			}
		};
		clickAnywhere(card, open);
		for (java.awt.Component sub : card.getComponents())
		{
			if (sub instanceof JPanel)
			{
				clickAnywhere((JComponent) sub, open);
			}
		}
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	private JComponent passiveLane(Plan plan)
	{
		// plan-aware lane: pending TRAIN skills whose ladder has daily-style
		// methods keep paying passively — surface them, don't grind them
		List<String> lines = new ArrayList<>();
		for (Plan.Step step : plan.steps)
		{
			if (step.action.kind == Action.Kind.TRAIN)
			{
				Skill skill = step.action.trainSkill;
				com.ironhub.data.MethodsPack.SkillLadder ladder =
					moduleMethods() == null ? null : moduleMethods().ladder(skill);
				if (ladder != null && ladder.methods.stream().anyMatch(m -> "daily".equals(m.style))
					&& lines.size() < 3)
				{
					lines.add(skill.getName() + " " + step.action.trainToLevel
						+ " builds passively — keep runs ticking");
				}
			}
		}
		if (lines.isEmpty())
		{
			return null;
		}
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		for (String line : lines)
		{
			card.add(wrapped(line, OsrsSkin.MUTED));
		}
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	private JComponent sinceLastSession(Plan plan)
	{
		double anchor = module.sessionStartPlanHours();
		if (anchor <= 0 || plan == null || anchor - plan.knownHours < 0.05)
		{
			return null;
		}
		StonePanel row = new StonePanel(theme);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel(String.format(Locale.ROOT,
			"since last session: plan -%.1fh", anchor - plan.knownHours),
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	// ── Route (frame 4c) ───────────────────────────────────────────────

	private void rebuildRoute()
	{
		routeView.removeAll();
		Plan plan = displayedPlan;

		if (latestPlan != null && plan != null
			&& !latestPlan.fingerprint.equals(plan.fingerprint))
		{
			routeView.add(updateBanner());
			routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		if (plan == null)
		{
			routeView.add(mutedNote("Planning…"));
		}
		else if (plan.steps.isEmpty())
		{
			routeView.add(mutedNote("No goals selected — add one under Goals."));
		}
		else
		{
			JPanel head = row();
			OsrsLabel total = new OsrsLabel("All goals · " + totalText(plan),
				OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned().squeezable();
			total.setToolTipText("Steps are listed in the order the planner recommends doing them");
			head.add(total);
			head.add(Box.createHorizontalGlue());
			cap(head);
			routeView.add(head);
			routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

			JPanel layoutRow = row();
			layoutRow.add(Box.createHorizontalGlue());
			StoneChipRow layout = new StoneChipRow(theme, false, "Order", "Chapters");
			layout.setSelected(state.isPlannerRouteChapters() ? 1 : 0);
			layout.setToolTipText("Same recommended order either way — Chapters only adds section headers");
			layout.onChange(i ->
			{
				state.setPlannerRouteChapters(i == 1);
				rebuildRoute();
			});
			layoutRow.add(layout);
			cap(layoutRow);
			routeView.add(layoutRow);
			routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

			boolean chapters = state.isPlannerRouteChapters();
			String chapter = null;
			int position = 0;
			List<Plan.Step> snoozed = new ArrayList<>();
			List<Plan.Step> active = new ArrayList<>();
			for (Plan.Step step : plan.steps)
			{
				if (step.snoozed)
				{
					snoozed.add(step);
				}
				else
				{
					active.add(step);
				}
			}
			for (int index = 0; index < active.size(); index++)
			{
				Plan.Step step = active.get(index);
				position++;
				if (chapters && !step.chapter.equals(chapter))
				{
					chapter = step.chapter;
					routeView.add(chapterHeader(active, index));
					routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				}
				if (step.action.id.equals(expandedStepId))
				{
					routeView.add(explainCard(step, position));
				}
				else
				{
					routeView.add(denseRow(step, position, true));
				}
				routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			if (!snoozed.isEmpty())
			{
				routeView.add(snoozedSection(snoozed));
			}
			if (!plan.degraded.isEmpty())
			{
				routeView.add(mutedNote("plan degraded: " + plan.degraded.size()
					+ " pack issue(s) — see logs"));
			}
		}
		routeView.add(Box.createVerticalGlue());
		routeView.revalidate();
		routeView.repaint();
	}

	private JComponent updateBanner()
	{
		StonePanel banner = new StonePanel(theme);
		banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
		banner.setAlignmentX(LEFT_ALIGNMENT);
		banner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		banner.add(new OsrsLabel("Plan updated" + bannerDelta(),
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		banner.add(Box.createHorizontalGlue());
		banner.add(new OsrsLabel("apply", OsrsSkin.TITLE, OsrsSkin.boldFont()));
		banner.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				displayedPlan = latestPlan;
				rebuildRoute();
			}
		});
		cap(banner);
		return banner;
	}

	private String bannerDelta()
	{
		if (latestPlan == null || displayedPlan == null)
		{
			return "";
		}
		double delta = latestPlan.knownHours - displayedPlan.knownHours;
		if (Math.abs(delta) < 0.05)
		{
			return " (steps reordered)";
		}
		return String.format(Locale.ROOT, ": %s%.1fh", delta < 0 ? "-" : "+", Math.abs(delta));
	}

	/** Header for the contiguous run of same-chapter steps starting here. */
	private JComponent chapterHeader(List<Plan.Step> active, int start)
	{
		String chapter = active.get(start).chapter;
		double hours = 0;
		boolean unknown = false;
		for (int i = start; i < active.size() && active.get(i).chapter.equals(chapter); i++)
		{
			if (Double.isNaN(active.get(i).hours))
			{
				unknown = true;
			}
			else
			{
				hours += active.get(i).hours;
			}
		}
		String label = chapter.toUpperCase(Locale.ROOT)
			+ (hours >= 0.05 ? " · ~" + compactHours(hours) : "") + (unknown ? " +?" : "");
		return section(label);
	}

	/** A route step as a flat colour-coded row — the game's quest-list idiom:
	 *  no boxes, the head in title orange, position and time in the margins. */
	private JComponent denseRow(Plan.Step step, int position, boolean expandable)
	{
		JPanel row = row();
		row.setOpaque(true);
		row.setBackground(theme.background);
		EmptyBorder inner = new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP);
		row.setBorder(step.pinned
			? new CompoundBorder(new MatteBorder(0, 2, 0, 0, OsrsSkin.TITLE.darker()), inner)
			: inner);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		if (position > 0)
		{
			row.add(new OsrsLabel(String.valueOf(position), OsrsSkin.FAINT, OsrsSkin.font()));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}

		javax.swing.Icon rowIcon = stepIcon(step);
		String shortTitle = step.action.kind == Action.Kind.TRAIN && rowIcon != null
			? "to " + step.action.trainToLevel + gatesSuffix(step)
			: step.action.name;
		if (rowIcon != null)
		{
			row.add(new JLabel(rowIcon));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		// text always paints through OsrsLabel — a raw JLabel positions the
		// pixel font by its (wrong) FontMetrics and clips glyph bottoms
		OsrsLabel name = new OsrsLabel(shortTitle
			+ (step.action.neededBy.size() > 1 ? "  ×" + step.action.neededBy.size() : ""),
			position == 1 ? OsrsSkin.TITLE : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		String hover = "<html><b>" + step.action.name + "</b><br>" + step.why
			+ (step.action.neededBy.size() > 1
				? "<br>serves: " + servedGoalNames(step) : "") + "</html>";
		name.setToolTipText(hover);
		row.setToolTipText(hover);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(timeLabel(step, OsrsSkin.MUTED, false));

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
				if (SwingUtilities.isRightMouseButton(e))
				{
					stepMenu(step).show(row, e.getX(), e.getY());
					return;
				}
				if (expandable)
				{
					expandedStepId = step.action.id.equals(expandedStepId)
						? null : step.action.id;
					rebuildRoute();
				}
				else
				{
					views.setSelected(1);
					showView(1);
					expandedStepId = step.action.id;
					rebuildRoute();
				}
			}
		};
		clickAnywhere(row, click);
		cap(row);
		return row;
	}

	/** Attach a click to a container AND its passive children — labels with
	 * tooltips register their own listeners and would swallow clicks. Skips
	 * anything with its own action. */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof com.ironhub.ui.components.IconButton
				|| child instanceof StoneButton)
			{
				continue; // keeps its own action
			}
			child.addMouseListener(click);
		}
	}

	/** The explain card (frame 4b): why + serves + alternatives + constraints. */
	private JComponent explainCard(Plan.Step step, int position)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel title = row();
		javax.swing.Icon icon = stepIcon(step);
		String shortTitle = step.action.kind == Action.Kind.TRAIN && icon != null
			? "to " + step.action.trainToLevel : step.action.name;
		if (icon != null)
		{
			title.add(new JLabel(icon));
			title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		OsrsLabel name = new OsrsLabel(position + ". " + shortTitle,
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable();
		name.setToolTipText(step.action.name);
		title.add(name);
		title.add(Box.createHorizontalGlue());
		title.add(timeLabel(step, OsrsSkin.TITLE, true));
		card.add(title);

		card.add(wrapped(step.why, OsrsSkin.MUTED));
		if (step.methodName != null)
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			card.add(section("Suggested method"));
			card.add(new OsrsLabel(step.methodName, OsrsSkin.LABEL, OsrsSkin.boldFont())
				.leftAligned().squeezable());
			String rateLine = (step.methodRate > 0 ? compactXp(step.methodRate) + " xp/hr" : "rate unknown")
				+ (step.methodStyle != null ? " · " + step.methodStyle : "");
			card.add(new OsrsLabel(rateLine, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
			if (step.trainXpRemaining > 0)
			{
				OsrsLabel span = new OsrsLabel(step.trainFromLevel + " to " + step.action.trainToLevel
					+ " · " + compactXp(step.trainXpRemaining) + " xp to go",
					OsrsSkin.FAINT, OsrsSkin.font()).leftAligned();
				span.setToolTipText("From your level when this step starts — earlier plan steps"
					+ " (quest xp, banked xp) are already credited");
				card.add(span);
			}
		}

		if (!step.resources.isEmpty())
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD));
			JComponent resHeader = section("Resources");
			resHeader.setToolTipText("Counts what your bank, inventory and equipment"
				+ " already hold (last bank snapshot)");
			card.add(resHeader);
			for (Plan.Resource resource : step.resources)
			{
				JPanel need = row();
				if (itemManager != null && resource.itemId > 0)
				{
					JLabel sprite = new JLabel();
					net.runelite.client.util.AsyncBufferedImage image =
						itemManager.getImage(resource.itemId);
					Runnable apply = () -> sprite.setIcon(new javax.swing.ImageIcon(
						image.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH)));
					apply.run();
					image.onLoaded(apply);
					need.add(sprite);
					need.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
				}
				need.add(new OsrsLabel(resource.name + " ×" + formatCount(resource.needed),
					OsrsSkin.MUTED, OsrsSkin.boldFont()).leftAligned().squeezable());
				need.add(Box.createHorizontalGlue());
				cap(need);
				card.add(need);
				boolean covered = resource.missing == 0;
				card.add(new OsrsLabel(covered
					? formatCount(resource.banked) + " banked — covered"
					: formatCount(resource.banked) + " banked · "
						+ formatCount(resource.missing) + " short",
					covered ? OsrsSkin.VALUE : OsrsSkin.TITLE, OsrsSkin.font()).leftAligned());
				card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}

		if (!step.alternatives.isEmpty())
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD));
			card.add(section("Alternatives"));
			for (Plan.Alternative alt : step.alternatives)
			{
				JPanel row = row();
				row.setOpaque(true);
				row.setBackground(theme.recess);
				row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
				OsrsLabel text = new OsrsLabel(alt.name + " · " + compactXp(alt.rate) + "/hr · "
					+ String.format(Locale.ROOT, "%s%.1fh", alt.deltaHours >= 0 ? "+" : "-",
						Math.abs(alt.deltaHours)) + " · " + alt.style,
					OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable();
				String hover = alternativeHover(alt);
				text.setToolTipText(hover);
				row.setToolTipText(hover);
				row.add(text);
				row.add(Box.createHorizontalGlue());
				OsrsLabel prefer = new OsrsLabel("prefer", OsrsSkin.TITLE, OsrsSkin.font());
				prefer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				prefer.setToolTipText("Use this method for " + step.action.trainSkill.getName()
					+ " wherever it applies");
				prefer.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						constraintAction(() -> state.setPlannerPreferred(
							step.action.trainSkill.getName(), alt.methodId));
					}
				});
				row.add(prefer);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
				card.add(row);
				card.add(Box.createVerticalStrut(2));
			}
		}

		java.util.List<String> reqStrings = null;
		if (step.action.kind == Action.Kind.OBTAIN)
		{
			GearProgressionPack.Item gearItem = moduleGearItem(step.action.itemId);
			reqStrings = gearItem == null ? null : gearItem.getRequirements();
		}
		else if (step.action.kind == Action.Kind.MANUAL && step.action.unlockKey != null
			&& step.action.unlockKey.startsWith("gearmark_"))
		{
			GearProgressionPack.Item gearItem = moduleGearItemByMark(step.action.unlockKey);
			reqStrings = gearItem == null ? null : gearItem.getRequirements();
		}
		else if (step.action.kind == Action.Kind.QUEST)
		{
			com.ironhub.data.QuestsPack.QuestEntry entry =
				module.questEntry(step.action.questName);
			reqStrings = entry == null ? null : entry.reqs;
		}
		else if (step.action.id.startsWith("diarytier:"))
		{
			String[] parts = step.action.id.split(":");
			reqStrings = module.diaryTierReqs(parts[1], parts[2]);
		}
		else if (step.action.kind == Action.Kind.MANUAL && step.action.unlockKey != null
			&& step.action.unlockKey.startsWith("diarytask_"))
		{
			reqStrings = module.diaryTaskReqs(
				step.action.unlockKey.substring("diarytask_".length()));
		}
		if (reqStrings != null && !reqStrings.isEmpty())
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD));
			card.add(section("Requirements"));
			for (String raw : reqStrings)
			{
				card.add(requirementLine(raw));
			}
		}

		card.add(Box.createVerticalStrut(UiTokens.PAD));
		// short labels: a third of the card can't fit "Pin next" in the game
		// font — the tooltips (and the right-click menu) carry the long form
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 3, UiTokens.PAD_TIGHT, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.add(cardButton(step.pinned ? "Unpin" : "Pin",
			step.pinned ? "Release this step" : "Pin this step next",
			() -> constraintAction(() -> state.togglePlannerPin(step.action.id)), OsrsSkin.TITLE));
		buttons.add(cardButton(step.snoozed ? "Wake" : "Snooze",
			step.snoozed ? "Unsnooze — back into the route" : "Snooze out of the route",
			() -> constraintAction(() -> state.togglePlannerSnooze(step.action.id)), OsrsSkin.MUTED));
		if (step.methodId != null)
		{
			buttons.add(cardButton("Ban", "Ban " + step.methodName + " everywhere",
				() -> constraintAction(() -> state.togglePlannerBan(step.methodId)),
				UiTokens.STATUS_WARNING));
		}
		else if (step.action.kind == Action.Kind.MANUAL && step.action.unlockKey != null)
		{
			buttons.add(cardButton("Done", "Mark this manual step as done",
				() -> state.setUnlocked(step.action.unlockKey, true), OsrsSkin.VALUE));
		}
		else
		{
			buttons.add(Box.createHorizontalStrut(1));
		}
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
		card.add(buttons);

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter collapse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedStepId = null;
				rebuildRoute();
			}
		};
		card.addMouseListener(collapse);
		clickAnywhere(title, collapse);
		return card;
	}

	private JPopupMenu stepMenu(Plan.Step step)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem pin = new JMenuItem(step.pinned ? "Unpin" : "Pin next");
		pin.addActionListener(e -> constraintAction(() -> state.togglePlannerPin(step.action.id)));
		menu.add(pin);
		JMenuItem snooze = new JMenuItem(step.snoozed ? "Unsnooze" : "Snooze");
		snooze.addActionListener(e -> constraintAction(() -> state.togglePlannerSnooze(step.action.id)));
		menu.add(snooze);
		if (step.methodId != null)
		{
			JMenuItem ban = new JMenuItem("Ban " + step.methodName);
			ban.addActionListener(e -> constraintAction(() -> state.togglePlannerBan(step.methodId)));
			menu.add(ban);
		}
		if (step.action.kind == Action.Kind.MANUAL && step.action.unlockKey != null)
		{
			JMenuItem done = new JMenuItem("Mark as done");
			done.addActionListener(e -> state.setUnlocked(step.action.unlockKey, true));
			menu.add(done);
		}
		String menuWiki = wikiUrl(step);
		if (menuWiki != null)
		{
			JMenuItem open = new JMenuItem("Open wiki");
			open.addActionListener(e -> LinkBrowser.browse(menuWiki));
			menu.add(open);
		}
		return menu;
	}

	private JComponent snoozedSection(List<Plan.Step> snoozed)
	{
		JPanel section = column();
		JPanel header = row();
		JLabel triangle = new JLabel(new PaintedIcon(snoozedOpen
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.FAINT);
		header.add(triangle);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(new OsrsLabel("SNOOZED (" + snoozed.size() + ")",
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		header.add(Box.createHorizontalGlue());
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		cap(header);
		MouseAdapter press = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				snoozedOpen = !snoozedOpen;
				rebuildRoute();
			}
		};
		clickAnywhere(header, press);
		section.add(header);
		if (snoozedOpen)
		{
			section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (Plan.Step step : snoozed)
			{
				section.add(denseRow(step, -1, true));
				section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		return section;
	}

	// ── Goals (frame 4e/4f: add-goal search + merge preview + 2d list) ──

	private final StoneTextField addSearch;
	private final JPanel addResults = column();
	private boolean goalsBuilt;

	private void rebuildGoals()
	{
		if (!goalsBuilt)
		{
			goalsBuilt = true;
			goalsView.add(addSearch);
			goalsView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			addResults.setAlignmentX(LEFT_ALIGNMENT);
			goalsView.add(addResults);
			goalsView.add(goalsTab);
			addSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
			{
				@Override
				public void insertUpdate(javax.swing.event.DocumentEvent e)
				{
					rebuildAddResults(addSearch.getText());
				}

				@Override
				public void removeUpdate(javax.swing.event.DocumentEvent e)
				{
					rebuildAddResults(addSearch.getText());
				}

				@Override
				public void changedUpdate(javax.swing.event.DocumentEvent e)
				{
				}
			});
		}
		rebuildAddResults(addSearch.getText());
	}

	private void rebuildAddResults(String query)
	{
		addResults.removeAll();
		previewCandidate = null;
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (q.length() >= 2)
		{
			List<GoalsPack.Goal> candidates = searchCandidates(q);
			for (GoalsPack.Goal candidate : candidates)
			{
				addResults.add(candidateRow(candidate));
				addResults.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			if (candidates.isEmpty())
			{
				String hint = skillHint(q);
				addResults.add(mutedNote(hint != null ? hint
					: "No matches. Try \"agility 70\", an item, or a goal name."));
			}
		}
		addResults.revalidate();
		addResults.repaint();
	}

	private List<GoalsPack.Goal> searchCandidates(String q)
	{
		List<GoalsPack.Goal> out = new ArrayList<>();
		// "skill level" form → a custom skill-target goal (real level: the
		// player asked for the level itself, boosts don't satisfy it)
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("([a-z]+)\\s+(\\d{1,2})").matcher(q);
		if (m.matches())
		{
			for (Skill skill : Skill.values())
			{
				if (skill.getName().toLowerCase(Locale.ROOT).startsWith(m.group(1)))
				{
					int level = Integer.parseInt(m.group(2));
					if (level >= 2 && level <= 99)
					{
						GoalsPack.Goal goal = new GoalsPack.Goal();
						goal.setId("custom:skill:" + skill.getName().toLowerCase(Locale.ROOT)
							+ ":" + level);
						goal.setName(skill.getName() + " " + level);
						GoalsPack.Step step = new GoalsPack.Step();
						step.setLabel(skill.getName() + " to " + level);
						step.setRequirement("skill:" + skill.getName() + ":" + level);
						goal.setSteps(List.of(step));
						goal.setAchieved(List.of("skill:" + skill.getName() + ":" + level));
						out.add(goal);
					}
				}
			}
		}
		// pack goals + already-tracked synthetics not yet selected
		for (GoalsPack.Goal goal : GoalPlannerModule.allGoals(modulePack(), moduleGear(), state))
		{
			if (out.size() >= 6)
			{
				break;
			}
			if (!state.getSelectedGoals().contains(goal.getId())
				&& goal.getName().toLowerCase(Locale.ROOT).contains(q))
			{
				out.add(goal);
			}
		}
		// every gear-chart item is searchable, selected or not
		for (com.ironhub.data.GearProgressionPack.Phase phase : moduleGear().getPhases())
		{
			for (com.ironhub.data.GearProgressionPack.Group group : phase.getGroups())
			{
				for (com.ironhub.data.GearProgressionPack.Item item : group.getItems())
				{
					if (out.size() >= 8)
					{
						return out;
					}
					if (!state.getSelectedGoals().contains(item.goalId())
						&& item.getName().toLowerCase(Locale.ROOT).contains(q)
						&& out.stream().noneMatch(g -> g.getId().equals(item.goalId())))
					{
						out.add(GoalPlannerModule.toGoal(item));
					}
				}
			}
		}
		return out;
	}

	/** A bare skill name typed alone → nudge toward the "skill level" form. */
	private String skillHint(String q)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().toLowerCase(Locale.ROOT).startsWith(q))
			{
				return "Add a level, e.g. \"" + skill.getName().toLowerCase(Locale.ROOT)
					+ " 70\"";
			}
		}
		return null;
	}

	private JComponent candidateRow(GoalsPack.Goal candidate)
	{
		JPanel container = column();
		JPanel row = row();
		row.setOpaque(true);
		boolean selected = candidate == previewCandidate;
		row.setBackground(selected ? theme.selectFill : theme.background);
		row.setBorder(new EmptyBorder(3, UiTokens.ROW_GAP, 3, UiTokens.ROW_GAP));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.add(new OsrsLabel(candidate.getName(),
			selected ? OsrsSkin.TITLE : OsrsSkin.LABEL, OsrsSkin.font())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!selected)
				{
					row.setBackground(theme.hoverFill);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!selected)
				{
					row.setBackground(theme.background);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				previewCandidate = candidate;
				preview = null;
				refreshPreviewAsync(candidate);
				rebuildAddResultsKeepQuery();
			}
		});
		cap(row);
		container.add(row);

		if (selected)
		{
			container.add(Box.createVerticalStrut(2));
			container.add(previewCard(candidate));
		}
		return container;
	}

	private void refreshPreviewAsync(GoalsPack.Goal candidate)
	{
		new Thread(() ->
		{
			GoalPlannerModule.MergePreview result = module.previewMerge(candidate);
			SwingUtilities.invokeLater(() ->
			{
				if (previewCandidate == candidate)
				{
					preview = result;
					rebuildAddResultsKeepQuery();
				}
			});
		}, "iron-hub-merge-preview").start();
	}

	private void rebuildAddResultsKeepQuery()
	{
		String q = addSearch.getText();
		// rebuild rows in place, preserving the preview candidate
		addResults.removeAll();
		if (q != null && q.trim().length() >= 2)
		{
			for (GoalsPack.Goal candidate : searchCandidates(q.trim().toLowerCase(Locale.ROOT)))
			{
				boolean isPreview = previewCandidate != null
					&& candidate.getId().equals(previewCandidate.getId());
				if (isPreview)
				{
					previewCandidate = candidate;
				}
				addResults.add(candidateRow(isPreview ? previewCandidate : candidate));
				addResults.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		addResults.revalidate();
		addResults.repaint();
	}

	private JComponent previewCard(GoalsPack.Goal candidate)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		if (preview == null)
		{
			card.add(wrapped("computing merge preview…", OsrsSkin.FAINT));
		}
		else
		{
			String added = preview.addedHours < 0.05 ? "almost nothing"
				: "~" + compactHours(preview.addedHours);
			card.add(wrapped("adds " + added + " to the plan · "
				+ preview.shared + " of " + preview.steps + " steps already shared with your goals",
				OsrsSkin.MUTED));
		}
		StoneButton add = new StoneButton(theme, theme.boxFill, "+ Add goal", () ->
		{
			if (candidate.getId().startsWith("custom:"))
			{
				state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
					candidate.getId(), candidate.getName(),
					candidate.getSteps().get(0).getRequirement()));
			}
			else
			{
				state.selectGoal(candidate.getId(), true);
			}
			previewCandidate = null;
			preview = null;
			addSearch.setText("");
			rebuildAddResults("");
		});
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(add);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	// ── shared helpers ─────────────────────────────────────────────────

	private GoalsPack modulePack()
	{
		return goalsTab.pack();
	}

	private GearProgressionPack moduleGear()
	{
		return goalsTab.gearPack();
	}

	private GearProgressionPack.Item moduleGearItem(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		for (GearProgressionPack.Phase phase : moduleGear().getPhases())
		{
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				for (GearProgressionPack.Item item : group.getItems())
				{
					if (item.getItemId() != null
						&& net.runelite.client.game.ItemVariationMapping.map(item.getItemId())
							== net.runelite.client.game.ItemVariationMapping.map(itemId))
					{
						return item;
					}
				}
			}
		}
		return null;
	}

	private GearProgressionPack.Item moduleGearItemByMark(String markKey)
	{
		for (GearProgressionPack.Phase phase : moduleGear().getPhases())
		{
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				for (GearProgressionPack.Item item : group.getItems())
				{
					if (item.markKey().equals(markKey))
					{
						return item;
					}
				}
			}
		}
		return null;
	}

	private com.ironhub.data.MethodsPack moduleMethods()
	{
		return module.methodsPack();
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		return panel;
	}

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent wrap(JPanel view)
	{
		view.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));
		JPanel anchor = new JPanel(new BorderLayout());
		anchor.setOpaque(false);
		anchor.add(view, BorderLayout.NORTH);
		return anchor;
	}

	/** Wrapping body text for cards — the skin's wrapped OsrsLabel. */
	private JComponent wrapped(String text, Color color)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, 0, 2, 0));
		holder.add(OsrsLabel.wrapped(text, CARD_TEXT_WIDTH, color, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Section header in the skin grammar. */
	private JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(4, 0, 3, 0));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private static javax.swing.Icon bundledIcon(String resource)
	{
		java.net.URL url = PlannerTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		javax.swing.ImageIcon icon = new javax.swing.ImageIcon(url);
		return new javax.swing.ImageIcon(
			icon.getImage().getScaledInstance(-1, 15, java.awt.Image.SCALE_SMOOTH));
	}

	private static final javax.swing.Icon CA_ICON =
		bundledIcon("/data/icons/combat_achievements.png");
	private static final javax.swing.Icon DIARY_ICON =
		bundledIcon("/data/icons/achievement_diaries.png");
	private static final javax.swing.Icon QUEST_ICON =
		bundledIcon("/data/icons/quest_point.png");

	/** The CA/diary badge for a goal id, or null. */
	private static javax.swing.Icon goalBadge(String goalId)
	{
		if (goalId.startsWith("ca:"))
		{
			return CA_ICON;
		}
		if (goalId.startsWith("diary:"))
		{
			return DIARY_ICON;
		}
		return null;
	}

	/** Icon for a route row: skill icon for training, item sprite for
	 * obtain steps, the serving goal's sprite for kills/manual steps. */
	private javax.swing.Icon stepIcon(Plan.Step step)
	{
		if (step.action.kind == Action.Kind.TRAIN && skillIconManager != null)
		{
			java.awt.image.BufferedImage image =
				skillIconManager.getSkillImage(step.action.trainSkill, true);
			return new javax.swing.ImageIcon(
				image.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH));
		}
		if (step.action.kind == Action.Kind.QUEST)
		{
			return QUEST_ICON;
		}
		if (itemManager == null)
		{
			return null;
		}
		Integer itemId = null;
		if (step.action.kind == Action.Kind.OBTAIN && step.action.itemId > 0)
		{
			GearProgressionPack.Item gearItem = moduleGearItem(step.action.itemId);
			if (gearItem != null && gearItem.getIconFile() != null)
			{
				return bundledIcon("/data/icons/" + gearItem.getIconFile());
			}
			itemId = step.action.itemId;
		}
		else if (step.action.kind == Action.Kind.KILL || step.action.kind == Action.Kind.MANUAL)
		{
			if (step.action.unlockKey != null && step.action.unlockKey.startsWith("gearmark_"))
			{
				GearProgressionPack.Item gearItem =
					moduleGearItemByMark(step.action.unlockKey);
				if (gearItem != null && gearItem.getIconFile() != null)
				{
					return bundledIcon("/data/icons/" + gearItem.getIconFile());
				}
				if (gearItem != null)
				{
					itemId = gearItem.icon();
				}
			}
			if (step.action.id.startsWith("diarytier:")
				|| step.action.unlockKey != null && step.action.unlockKey.startsWith("diarytask_"))
			{
				return DIARY_ICON;
			}
			if (step.action.unlockKey != null && step.action.unlockKey.startsWith("catask_"))
			{
				return CA_ICON;
			}
			Plan plan = latestPlan != null ? latestPlan : displayedPlan;
			if (plan != null)
			{
				for (String goalId : step.action.neededBy)
				{
					itemId = plan.goalIcons.get(goalId);
					if (itemId != null)
					{
						break;
					}
					javax.swing.Icon badge = goalBadge(goalId);
					if (badge != null)
					{
						return badge;
					}
				}
			}
		}
		if (itemId == null)
		{
			return null;
		}
		net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
		return new javax.swing.ImageIcon(
			image.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH));
	}

	/** " · gates <first goal>" when the row has room to say so. */
	private String gatesSuffix(Plan.Step step)
	{
		Plan plan = latestPlan != null ? latestPlan : displayedPlan;
		if (plan == null || step.action.neededBy.isEmpty())
		{
			return "";
		}
		String first = step.action.neededBy.iterator().next();
		return " · " + plan.goalNames.getOrDefault(first, first);
	}

	/** Display names for the goals a step serves (ids never render). */
	private String servedGoalNames(Plan.Step step)
	{
		Plan plan = latestPlan != null ? latestPlan : displayedPlan;
		List<String> names = new ArrayList<>();
		for (String id : step.action.neededBy)
		{
			names.add(plan != null ? plan.goalNames.getOrDefault(id, id) : id);
		}
		return String.join(", ", names);
	}

	/** Hover card for an alternative: the full method picture, resources
	 * included, so switching is an informed choice before "prefer". */
	private String alternativeHover(Plan.Alternative alt)
	{
		StringBuilder html = new StringBuilder("<html><b>").append(alt.name).append("</b>");
		html.append("<br>").append(compactXp(alt.rate)).append(" xp/hr · ").append(alt.style);
		html.append("<br>").append(timeText(alt.hours)).append(" total (")
			.append(alt.deltaHours >= 0 ? "+" : "-")
			.append(String.format(Locale.ROOT, "%.1fh", Math.abs(alt.deltaHours)))
			.append(" vs suggested)");
		for (Plan.Resource resource : alt.resources)
		{
			html.append("<br>").append(resource.name).append(" ×")
				.append(formatCount(resource.needed)).append(" — ")
				.append(resource.missing == 0
					? formatCount(resource.banked) + " banked, covered"
					: formatCount(resource.banked) + " banked, "
						+ formatCount(resource.missing) + " short");
		}
		html.append("<br><i>click prefer to use this method</i></html>");
		return html.toString();
	}

	/** One requirement line: skill icon + level for skill leaves, text
	 * otherwise; green when met, muted when not. */
	private JComponent requirementLine(String raw)
	{
		com.ironhub.requirements.Requirement parsed =
			com.ironhub.requirements.Requirements.parse(raw);
		boolean met = !com.ironhub.requirements.Requirements.isManual(parsed)
			&& parsed.isMet(state);
		String text = "· " + parsed.describe();
		javax.swing.Icon icon = null;
		String[] parts = raw.split(":");
		if ((parts[0].equalsIgnoreCase("skill") || parts[0].equalsIgnoreCase("skillb"))
			&& parts.length >= 3 && skillIconManager != null)
		{
			for (Skill skill : Skill.values())
			{
				if (skill.getName().equalsIgnoreCase(parts[1]))
				{
					icon = new javax.swing.ImageIcon(skillIconManager
						.getSkillImage(skill, true)
						.getScaledInstance(-1, 15, java.awt.Image.SCALE_SMOOTH));
					text = parts[2];
					break;
				}
			}
		}
		JPanel line = row();
		if (icon != null)
		{
			line.add(new JLabel(icon));
			line.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		OsrsLabel label = new OsrsLabel(text, met ? OsrsSkin.VALUE : OsrsSkin.MUTED,
			OsrsSkin.font()).leftAligned().squeezable();
		String tooltip = parsed.describe() + (met ? " — met" : " — not yet");
		label.setToolTipText(tooltip);
		line.setToolTipText(tooltip);
		line.add(label);
		line.add(Box.createHorizontalGlue());
		cap(line);
		return line;
	}

	/** Run a player constraint change: their own action applies the next
	 * plan without the update banner (which guards external changes). */
	private void constraintAction(Runnable action)
	{
		applyNextPlan = true;
		action.run();
	}

	private JComponent mutedNote(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));
		holder.add(OsrsLabel.wrapped(text, NOTE_WIDTH, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Explain-card constraint button: a stone button with a status label. */
	private JComponent cardButton(String label, String tooltip, Runnable onClick, Color fg)
	{
		StoneButton button = new StoneButton(theme, theme.boxFill, label, onClick);
		button.labelColor(fg);
		button.setToolTipText(tooltip);
		return button;
	}

	private OsrsLabel timeLabel(Plan.Step step, Color color, boolean bold)
	{
		OsrsLabel time = new OsrsLabel(timeText(step.hours),
			Double.isNaN(step.hours) ? OsrsSkin.FAINT : color,
			bold ? OsrsSkin.boldFont() : OsrsSkin.font());
		if (Double.isNaN(step.hours))
		{
			time.setToolTipText("No honest time estimate for this yet — never invented");
		}
		return time;
	}

	static String timeText(double hours)
	{
		if (Double.isNaN(hours))
		{
			return "?";
		}
		return "~" + compactHours(hours);
	}

	/** 912 → "912", 12400 → "12,400". */
	static String formatCount(long count)
	{
		return String.format(Locale.ROOT, "%,d", count);
	}

	/** 75000 → "75k", 1200000 → "1.2m". */
	static String compactXp(long xp)
	{
		if (xp >= 1_000_000)
		{
			return String.format(Locale.ROOT, "%.1fm", xp / 1_000_000.0);
		}
		if (xp >= 1_000)
		{
			return Math.round(xp / 1000.0) + "k";
		}
		return String.valueOf(xp);
	}

	static String compactHours(double hours)
	{
		if (hours < 0.95)
		{
			return Math.max(5, Math.round(hours * 60 / 5) * 5) + "m";
		}
		return String.format(Locale.ROOT, "%.1fh", hours);
	}

	private String totalText(Plan plan)
	{
		String known = compactHours(plan.knownHours);
		return "~" + known + (plan.unknownCount > 0 ? " +" + plan.unknownCount + "?" : "");
	}

	private static void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	private String wikiPage(Plan.Step step)
	{
		switch (step.action.kind)
		{
			case QUEST:
				return step.action.questName;
			case KILL:
				return step.action.kcSource;
			case OBTAIN:
			{
				GearProgressionPack.Item item = moduleGearItem(step.action.itemId);
				return item != null ? item.wikiPage() : null;
			}
			case MANUAL:
				if (step.action.id.startsWith("diarytier:"))
				{
					return step.action.id.split(":")[1] + " Diary";
				}
				if (step.action.unlockKey != null && step.action.unlockKey.startsWith("gearmark_"))
				{
					GearProgressionPack.Item item =
						moduleGearItemByMark(step.action.unlockKey);
					return item != null ? item.wikiPage() : null;
				}
				return null;
			case TRAIN:
			default:
				return null;
		}
	}

	/** Wiki URL for a step: pages for content, the method's source for training. */
	private String wikiUrl(Plan.Step step)
	{
		if (step.action.kind == Action.Kind.TRAIN)
		{
			com.ironhub.data.MethodsPack.SkillLadder ladder = moduleMethods() == null
				? null : moduleMethods().ladder(step.action.trainSkill);
			if (ladder != null && step.methodId != null)
			{
				for (com.ironhub.data.MethodsPack.Method method : ladder.methods)
				{
					if (method.id.equals(step.methodId))
					{
						return "https://" + method.source;
					}
				}
			}
			return null;
		}
		String page = wikiPage(step);
		return page == null ? null
			: "https://oldschool.runescape.wiki/w/" + page.replace(' ', '_');
	}
}
