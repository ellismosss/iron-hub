package com.ironhub.modules.goals;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.engine.Action;
import com.ironhub.engine.Plan;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SegmentedControl;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import net.runelite.api.Skill;
import net.runelite.client.util.LinkBrowser;

/**
 * The goal planner's three views over one plan (design/PLANNER-UX.md,
 * frames 4a–4f): Today (session view — budget chips, NOW card, up next,
 * passive lane), Route (the chaptered full plan with a no-silent-reshuffle
 * update banner and explain-card expands), and Goals (add-goal search with
 * live merge preview + the proven goals list). Reorder by constraint:
 * pin / snooze / ban via right-click or the explain card — never by drag.
 */
class PlannerTab extends JPanel
{
	private static final String[] BUDGETS = {"30m", "1h", "2h+", "AFK"};
	private static final double[] BUDGET_HOURS = {0.55, 1.3, Double.MAX_VALUE, Double.MAX_VALUE};

	private final GoalPlannerModule module;
	private final AccountState state;
	private final GoalsTab goalsTab; // the proven 2d list, embedded as the Goals view

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final SegmentedControl views = new SegmentedControl(true, "Today", "Route", "Goals");
	private final JPanel todayView = column(UiTokens.PANEL_BG);
	private final JPanel routeView = column(UiTokens.PANEL_BG);
	private final JPanel goalsView = column(UiTokens.PANEL_BG);
	private final ChipRow budgetChips = new ChipRow(BUDGETS);

	/** The plan the Route list currently shows (never silently replaced). */
	private Plan displayedPlan;
	/** The freshest plan from the engine (may await an explicit apply). */
	private Plan latestPlan;
	private String expandedStepId;
	private boolean snoozedOpen;
	private GoalsPack.Goal previewCandidate;
	private GoalPlannerModule.MergePreview preview;

	private final Runnable planListener = this::refreshFromModule;

	private void refreshFromModule()
	{
		onPlanUpdated(module.currentPlan());
	}

	PlannerTab(GoalPlannerModule module, AccountState state, GoalsPack pack,
		GearProgressionPack gearPack, net.runelite.client.game.ItemManager itemManager)
	{
		this.module = module;
		this.state = state;
		this.goalsTab = new GoalsTab(state, pack, gearPack, itemManager);

		setLayout(new BorderLayout());
		setBackground(UiTokens.PANEL_BG);

		JPanel header = column(UiTokens.PANEL_BG);
		header.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, 0, UiTokens.PAD));
		views.onChange(i -> showView(i));
		header.add(views);
		header.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		add(header, BorderLayout.NORTH);

		content.setBackground(UiTokens.PANEL_BG);
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

	/** New plan from the engine. Today follows live; Route waits for apply. */
	void onPlanUpdated(Plan plan)
	{
		if (plan == null)
		{
			return;
		}
		latestPlan = plan;
		if (displayedPlan == null)
		{
			displayedPlan = plan;
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
			todayView.add(new SectionLabel("Now"));
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
					todayView.add(new SectionLabel("Up next"));
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
				todayView.add(new SectionLabel("Passive lane"));
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
		JLabel total = new JLabel(plan == null ? "All goals · —" : "All goals · " + totalText(plan));
		total.setForeground(UiTokens.TEXT_PRIMARY);
		total.setFont(total.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		total.setToolTipText("Known-time steps only; \"+n?\" steps have no honest estimate yet");
		row.add(total);
		row.add(Box.createHorizontalGlue());
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

	private JComponent nowCard(Plan.Step step)
	{
		JPanel card = column(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.ACCENT),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel title = row();
		JLabel name = new JLabel(step.action.name);
		name.setForeground(UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setMinimumSize(new Dimension(0, 0));
		title.add(name);
		title.add(Box.createHorizontalGlue());
		title.add(timeLabel(step, UiTokens.ACCENT, true));
		card.add(title);

		JTextArea why = wrapText(step.why, UiTokens.STATUS_AVAILABLE, UiTokens.CARD_BG);
		card.add(why);

		JPanel foot = row();
		JLabel serves = new JLabel("serves " + step.action.neededBy.size()
			+ (step.action.neededBy.size() == 1 ? " goal" : " goals"));
		serves.setForeground(UiTokens.TEXT_MUTED);
		serves.setFont(serves.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		serves.setToolTipText(servedGoalNames(step));
		foot.add(serves);
		foot.add(Box.createHorizontalGlue());
		String wiki = wikiPage(step);
		if (wiki != null)
		{
			foot.add(new com.ironhub.ui.components.IconButton("W", "Open the wiki page",
				() -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ wiki.replace(' ', '_'))));
		}
		card.add(foot);
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				views.setSelected(1);
				showView(1);
				expandedStepId = step.action.id;
				rebuildRoute();
			}
		});
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
		JPanel card = column(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)));
		for (String line : lines)
		{
			JPanel row = row();
			JLabel glyph = new JLabel(new StatusGlyph(Status.OWNED));
			row.add(glyph);
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			JLabel text = new JLabel(line);
			text.setForeground(UiTokens.TEXT_BODY);
			text.setFont(text.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			text.setMinimumSize(new Dimension(0, 0));
			row.add(text);
			row.add(Box.createHorizontalGlue());
			card.add(row);
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
		JPanel row = row();
		row.setBackground(new Color(0x25, 0x25, 0x25));
		row.setOpaque(true);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)));
		JLabel glyph = new JLabel(new StatusGlyph(Status.OWNED));
		row.add(glyph);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		JLabel text = new JLabel(String.format(Locale.ROOT,
			"since last session: plan -%.1fh", anchor - plan.knownHours));
		text.setForeground(UiTokens.TEXT_MUTED);
		text.setFont(text.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		row.add(text);
		row.add(Box.createHorizontalGlue());
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
			routeView.add(horizonRow(plan));
			routeView.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

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
				if (!step.chapter.equals(chapter))
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
		JPanel banner = row();
		banner.setOpaque(true);
		banner.setBackground(new Color(0x2E, 0x2A, 0x20));
		banner.setBorder(new CompoundBorder(new LineBorder(UiTokens.STATUS_AVAILABLE),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)));
		banner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel glyph = new JLabel(new StatusGlyph(Status.WARNING));
		banner.add(glyph);
		banner.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		JLabel text = new JLabel("Plan updated" + bannerDelta());
		text.setForeground(UiTokens.STATUS_AVAILABLE);
		text.setFont(text.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		text.setMinimumSize(new Dimension(0, 0));
		banner.add(text);
		banner.add(Box.createHorizontalGlue());
		JLabel apply = new JLabel("apply");
		apply.setForeground(UiTokens.ACCENT);
		apply.setFont(apply.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		banner.add(apply);
		banner.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				displayedPlan = latestPlan;
				rebuildRoute();
			}
		});
		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
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
		return new SectionLabel(label);
	}

	private JComponent denseRow(Plan.Step step, int position, boolean expandable)
	{
		JPanel row = row();
		row.setOpaque(true);
		row.setBackground(UiTokens.CARD_BG);
		row.setBorder(new CompoundBorder(
			step.pinned
				? new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
					new MatteBorder(0, 2, 0, 0, UiTokens.ACCENT))
				: new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT_DENSE));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT_DENSE));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		if (position > 0)
		{
			JLabel pos = new JLabel(String.valueOf(position));
			pos.setForeground(UiTokens.TEXT_FAINT);
			pos.setFont(pos.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			row.add(pos);
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		JLabel glyph = new JLabel(new StatusGlyph(
			position <= 1 ? Status.AVAILABLE : Status.LOCKED));
		row.add(glyph);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));

		JLabel name = new JLabel(step.action.name
			+ (step.action.neededBy.size() > 1 ? "  ×" + step.action.neededBy.size() : ""));
		name.setForeground(UiTokens.TEXT_BODY);
		name.setFont(name.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		name.setMinimumSize(new Dimension(0, 0));
		name.setToolTipText(step.action.neededBy.size() > 1
			? step.why + " — serves: " + servedGoalNames(step) : step.why);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(timeLabel(step, UiTokens.TEXT_MUTED, false));

		row.addMouseListener(new MouseAdapter()
		{
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
		});
		return row;
	}

	/** The explain card (frame 4b): why + serves + alternatives + constraints. */
	private JComponent explainCard(Plan.Step step, int position)
	{
		JPanel card = column(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_BUTTON),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));

		JPanel title = row();
		JLabel name = new JLabel(position + ". " + step.action.name);
		name.setForeground(UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setMinimumSize(new Dimension(0, 0));
		title.add(name);
		title.add(Box.createHorizontalGlue());
		title.add(timeLabel(step, UiTokens.ACCENT, true));
		card.add(title);

		card.add(wrapText(step.why, UiTokens.TEXT_BODY, UiTokens.CARD_BG));
		if (step.methodName != null)
		{
			JLabel basis = new JLabel("via " + step.methodName
				+ (step.methodStyle != null ? " · " + step.methodStyle : ""));
			basis.setForeground(UiTokens.TEXT_FAINT);
			basis.setFont(basis.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			basis.setAlignmentX(LEFT_ALIGNMENT);
			card.add(basis);
		}

		if (!step.alternatives.isEmpty())
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			card.add(new SectionLabel("Alternatives"));
			for (Plan.Alternative alt : step.alternatives)
			{
				JPanel row = row();
				row.setOpaque(true);
				row.setBackground(new Color(0x25, 0x25, 0x25));
				row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
					new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP)));
				JLabel text = new JLabel(alt.name + " · "
					+ String.format(Locale.ROOT, "%s%.1fh", alt.deltaHours >= 0 ? "+" : "-",
						Math.abs(alt.deltaHours)) + " · " + alt.style);
				text.setForeground(UiTokens.TEXT_BODY);
				text.setFont(text.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
				text.setMinimumSize(new Dimension(0, 0));
				row.add(text);
				row.add(Box.createHorizontalGlue());
				JLabel prefer = new JLabel("prefer");
				prefer.setForeground(UiTokens.ACCENT);
				prefer.setFont(prefer.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
				prefer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				prefer.setToolTipText("Use this method for " + step.action.trainSkill.getName()
					+ " wherever it applies");
				prefer.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						state.setPlannerPreferred(step.action.trainSkill.getName(), alt.methodId);
					}
				});
				row.add(prefer);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
				card.add(row);
				card.add(Box.createVerticalStrut(2));
			}
		}

		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 3, UiTokens.PAD_TIGHT, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.add(flatButton(step.pinned ? "Unpin" : "Pin next",
			() -> state.togglePlannerPin(step.action.id), UiTokens.ACCENT));
		buttons.add(flatButton(step.snoozed ? "Unsnooze" : "Snooze",
			() -> state.togglePlannerSnooze(step.action.id), UiTokens.TEXT_BODY));
		if (step.methodId != null)
		{
			buttons.add(flatButton("Ban method",
				() -> state.togglePlannerBan(step.methodId), UiTokens.STATUS_WARNING));
		}
		else
		{
			buttons.add(Box.createHorizontalStrut(1));
		}
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		card.add(buttons);

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedStepId = null;
				rebuildRoute();
			}
		});
		return card;
	}

	private JPopupMenu stepMenu(Plan.Step step)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem pin = new JMenuItem(step.pinned ? "Unpin" : "Pin next");
		pin.addActionListener(e -> state.togglePlannerPin(step.action.id));
		menu.add(pin);
		JMenuItem snooze = new JMenuItem(step.snoozed ? "Unsnooze" : "Snooze");
		snooze.addActionListener(e -> state.togglePlannerSnooze(step.action.id));
		menu.add(snooze);
		if (step.methodId != null)
		{
			JMenuItem ban = new JMenuItem("Ban " + step.methodName);
			ban.addActionListener(e -> state.togglePlannerBan(step.methodId));
			menu.add(ban);
		}
		String wiki = wikiPage(step);
		if (wiki != null)
		{
			JMenuItem open = new JMenuItem("Open wiki");
			open.addActionListener(e -> LinkBrowser.browse(
				"https://oldschool.runescape.wiki/w/" + wiki.replace(' ', '_')));
			menu.add(open);
		}
		return menu;
	}

	private JComponent snoozedSection(List<Plan.Step> snoozed)
	{
		JPanel section = column(UiTokens.PANEL_BG);
		JLabel header = new JLabel("SNOOZED (" + snoozed.size() + ")");
		header.setForeground(UiTokens.TEXT_FAINT);
		header.setFont(SectionLabel.letterSpaced(
			header.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		header.setIcon(new PaintedIcon(snoozedOpen
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		header.setIconTextGap(UiTokens.ROW_GAP);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				snoozedOpen = !snoozedOpen;
				rebuildRoute();
			}
		});
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

	private final SearchField addSearch = new SearchField("Add a goal — search goals, gear, skills…");
	private final JPanel addResults = column(UiTokens.PANEL_BG);
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
				addResults.add(mutedNote("No matches. Try \"agility 70\" or an item name."));
			}
		}
		addResults.revalidate();
		addResults.repaint();
	}

	private List<GoalsPack.Goal> searchCandidates(String q)
	{
		List<GoalsPack.Goal> out = new ArrayList<>();
		// "skill level" form → a custom skill-target goal
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
						goal.setId("skill:" + skill.getName().toLowerCase(Locale.ROOT) + ":" + level);
						goal.setName(skill.getName() + " " + level);
						GoalsPack.Step step = new GoalsPack.Step();
						step.setLabel(skill.getName() + " to " + level);
						step.setRequirement("skillb:" + skill.getName() + ":" + level);
						goal.setSteps(List.of(step));
						out.add(goal);
					}
				}
			}
		}
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
		return out;
	}

	private JComponent candidateRow(GoalsPack.Goal candidate)
	{
		JPanel container = column(UiTokens.PANEL_BG);
		JPanel row = row();
		row.setOpaque(true);
		row.setBackground(UiTokens.CARD_BG);
		boolean selected = candidate == previewCandidate;
		row.setBorder(new CompoundBorder(
			new LineBorder(selected ? UiTokens.ACCENT : UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel name = new JLabel(candidate.getName());
		name.setForeground(UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setMinimumSize(new Dimension(0, 0));
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				previewCandidate = candidate;
				preview = null;
				refreshPreviewAsync(candidate);
				rebuildAddResultsKeepQuery();
			}
		});
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
		JPanel card = column(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		if (preview == null)
		{
			card.add(mutedNote("computing merge preview…"));
		}
		else
		{
			String added = preview.addedHours < 0.05 ? "almost nothing"
				: "~" + compactHours(preview.addedHours);
			JTextArea text = wrapText("adds " + added + " to the plan · "
				+ preview.shared + " of " + preview.steps + " steps already shared with your goals",
				UiTokens.TEXT_BODY, UiTokens.CARD_BG);
			card.add(text);
		}
		JLabel add = new JLabel("+ Add goal", javax.swing.SwingConstants.CENTER);
		add.setOpaque(true);
		add.setBackground(UiTokens.ACCENT);
		add.setForeground(UiTokens.ACCENT_TEXT_ON);
		add.setFont(add.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		add.setBorder(new EmptyBorder(3, 8, 3, 8));
		add.setAlignmentX(LEFT_ALIGNMENT);
		add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.selectGoal(candidate.getId(), true);
				previewCandidate = null;
				preview = null;
				addSearch.setText("");
				rebuildAddResults("");
			}
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

	private com.ironhub.data.MethodsPack moduleMethods()
	{
		return module.methodsPack();
	}

	private static JPanel column(Color bg)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(bg);
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
		anchor.setBackground(UiTokens.PANEL_BG);
		anchor.add(view, BorderLayout.NORTH);
		return anchor;
	}

	private static JTextArea wrapText(String text, Color fg, Color bg)
	{
		JTextArea area = new JTextArea(text);
		area.setFont(area.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		area.setForeground(fg);
		area.setBackground(bg);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setBorder(new EmptyBorder(2, 0, 2, 0));
		area.setAlignmentX(LEFT_ALIGNMENT);
		return area;
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

	private static JComponent mutedNote(String text)
	{
		JLabel note = new JLabel(text);
		note.setForeground(UiTokens.TEXT_MUTED);
		note.setFont(note.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		note.setAlignmentX(LEFT_ALIGNMENT);
		note.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));
		return note;
	}

	private JLabel flatButton(String label, Runnable onClick, Color fg)
	{
		JLabel button = new JLabel(label, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ICON_BUTTON_BG);
		button.setForeground(fg);
		button.setFont(button.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		button.setBorder(BorderFactory.createLineBorder(UiTokens.BORDER_BUTTON));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}
		});
		return button;
	}

	private JLabel timeLabel(Plan.Step step, Color color, boolean bold)
	{
		JLabel time = new JLabel(timeText(step.hours));
		time.setForeground(Double.isNaN(step.hours) ? UiTokens.TEXT_FAINT : color);
		time.setFont(time.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN,
			UiTokens.FONT_SIZE_SECONDARY));
		if (Double.isNaN(step.hours))
		{
			time.setToolTipText("No honest time estimate for this yet — never invented");
		}
		return time;
	}

	private static String timeText(double hours)
	{
		if (Double.isNaN(hours))
		{
			return "?";
		}
		return "~" + compactHours(hours);
	}

	private static String compactHours(double hours)
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

	private String wikiPage(Plan.Step step)
	{
		if (step.action.kind == Action.Kind.QUEST)
		{
			return step.action.questName;
		}
		if (step.action.kind == Action.Kind.KILL)
		{
			return step.action.kcSource;
		}
		return null;
	}
}
