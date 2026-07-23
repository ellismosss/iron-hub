package com.ironhub.modules.goals;

import com.ironhub.IronHubConfig;
import com.ironhub.data.MethodsPack;
import com.ironhub.engine.Action;
import com.ironhub.engine.Plan;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * The plan-following canvas overlay (frame 3c, grown over the live engine):
 * pinned to the screen, it mirrors the plan head so the player rarely needs
 * the sidebar — step name + time-to-target, a smooth sub-pixel progress bar
 * with a 2dp percent label, live session stats while xp is coming in
 * (gained, measured xp/hr, actions left), the suggested method — or the
 * player's own, when their drops say they picked a different one — missing
 * resources, the on-deck step, and the goal it all serves. Flashes the
 * finished step green once when the engine detects completion. Display-only;
 * right-click carries Snooze (and Mark done for manual steps). RuneLite
 * handles dragging/pinning and persists the position.
 *
 * Progress anchors on LIVE xp per step id, never on the plan's remaining
 * figure: banking moves banked-xp credit and can transiently reorder the
 * head, and neither is allowed to reset the bar.
 */
class PlannerOverlay extends OverlayPanel
{
	private static final int WIDTH = 190;       // within the 250x200 budget
	private static final long FLASH_MS = 1_500;
	private static final String MENU_TARGET = "Iron Hub step";
	/** Measured pace this far off the proposed rate = "Your method". */
	private static final double PACE_DIVERGENCE = 0.30;

	private final GoalPlannerModule module;
	private final AccountState state;
	private final IronHubConfig config;
	private final net.runelite.client.game.SkillIconManager skillIcons; // null in headless tests
	private final XpGauge gauge = new XpGauge();
	// cached skill icons (the manager decodes on each call otherwise)
	private final Map<Skill, java.awt.image.BufferedImage> skillIconCache = new HashMap<>();

	// render-thread bookkeeping (all reads/writes on the client thread)
	private Action lastHead;
	/** Step id → live xp when first seen; survives transient head swaps. */
	private final Map<String, Long> stepAnchors = new HashMap<>();
	private long flashUntilMs;
	private String completedName;
	private boolean markDoneShown;
	// eased bar so movement feels smooth rather than stepped
	private double displayFraction;
	private String displayFractionStepId;
	// methodLine match memo (2026-07-20 audit) — see methodLine
	private List<Object> methodLineKey;
	private String methodLineLabel;

	PlannerOverlay(GoalPlannerModule module, AccountState state, IronHubConfig config,
		net.runelite.client.game.SkillIconManager skillIcons)
	{
		this.module = module;
		this.state = state;
		this.config = config;
		this.skillIcons = skillIcons;
		setPosition(OverlayPosition.TOP_LEFT);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Snooze step", MENU_TARGET, e -> snoozeHead());
	}

	private java.awt.image.BufferedImage skillIcon(Skill skill)
	{
		if (skillIcons == null || skill == null)
		{
			return null;
		}
		return skillIconCache.computeIfAbsent(skill, s -> skillIcons.getSkillImage(s, true));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.plannerOverlay())
		{
			return null;
		}
		Plan plan = module.currentPlan();
		Plan.Step head = plan == null ? null : plan.head();
		long now = System.currentTimeMillis();
		trackHead(plan, head, now);
		syncMenu(head);
		boolean flashing = flashUntilMs >= now && completedName != null;
		if (head == null && !flashing)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		// the standard RuneLite overlay background (XP Tracker, built-ins) —
		// PanelComponent defaults to it, so don't override (Luke, 2026-07-24)
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));

		if (flashing)
		{
			line("Done · " + completedName, UiTokens.CANVAS_OWNED, null, null);
		}
		if (head == null)
		{
			return super.render(graphics);
		}

		switch (head.action.kind)
		{
			case TRAIN:
				renderTrain(head, now);
				break;
			case KILL:
				renderKill(head);
				break;
			default:
				line(head.action.name, Color.WHITE,
					timeText(head.hours), UiTokens.OVERLAY_VALUE);
				String detail = obtainDetail(head);
				if (detail != null)
				{
					line(detail, UiTokens.CANVAS_LOCKED, null, null);
				}
				if (head.action.unlockKey != null)
				{
					line("right-click to mark done", UiTokens.CANVAS_LOCKED, null, null);
				}
				break;
		}

		int shown = 0;
		for (Plan.Resource resource : head.resources)
		{
			if (resource.missing > 0 && shown++ < 2)
			{
				line(resource.name, UiTokens.CANVAS_WARNING,
					formatCount(resource.missing) + " more", UiTokens.CANVAS_WARNING);
			}
		}

		// "Step N of T" when the head's goal has more than one stage (replaces
		// the old "Next · …" row, Luke 2026-07-24)
		String stage = stageLine(plan, head);
		if (stage != null)
		{
			line(stage, UiTokens.CANVAS_LOCKED, null, null);
		}
		String goal = goalLine(plan, head);
		if (goal != null)
		{
			// Goal in white (Luke); drop it when it merely repeats the
			// obtain detail / why already shown
			line(goal, Color.WHITE, null, null);
		}
		return super.render(graphics);
	}

	private void renderTrain(Plan.Step head, long now)
	{
		Skill skill = head.action.trainSkill;
		long liveXp = state.getXp(skill);
		long targetXp = Experience.getXpForLevel(head.action.trainToLevel);
		// the raw gap: banked materials save gathering, not training time
		long xpLeft = Math.max(0, targetXp - liveXp);
		gauge.observe(skill, liveXp, now);

		double measured = gauge.xpPerHour();
		// the skill icon carries the identity (Luke, 2026-07-24) — the head
		// line is [icon] method-or-target … time(white)
		iconLine(skillIcon(skill), head.action.name, Color.WHITE,
			timeText(ttlHours(head, xpLeft, measured)), UiTokens.OVERLAY_VALUE);

		long anchor = anchorFor(head.action.id, liveXp);
		bar(head.action.id, stepFraction(anchor, liveXp, targetXp));

		line("Current Lvl: " + state.getRealLevel(skill), UiTokens.CANVAS_LOCKED,
			compactXp(xpLeft) + " xp left", UiTokens.OVERLAY_VALUE);

		if (gauge.gained() > 0)
		{
			line("+" + formatCount(gauge.gained()) + " xp", UiTokens.CANVAS_OWNED,
				Double.isNaN(measured) ? null
					: compactXp(Math.round(measured)) + "/hr", UiTokens.OVERLAY_VALUE);
		}

		methodLine(head, skill, xpLeft, measured);
	}

	private void renderKill(Plan.Step head)
	{
		int kc = state.getKillCount(head.action.kcSource);
		line(head.action.name, Color.WHITE,
			timeText(head.hours), UiTokens.OVERLAY_VALUE);
		bar(head.action.id, head.action.kcTarget > 0 ? kc / (double) head.action.kcTarget : 0);
		line("KC", UiTokens.CANVAS_LOCKED,
			kc + " / " + head.action.kcTarget, UiTokens.OVERLAY_VALUE);
	}

	/**
	 * The obtain detail line for a non-train/kill head. For an item, the
	 * knowledge-base where-from — HONOURING a chosen method, so a curated
	 * "250 Tithe Farm points (or 750 slayer points)" collapses to just the
	 * player's pick (Luke, 2026-07-24). Otherwise the step's own "why",
	 * minus the obvious "Completes automatically…" boilerplate and any
	 * "Serves X" that just repeats the Goal line.
	 */
	private String obtainDetail(Plan.Step head)
	{
		if (head.action.kind == Action.Kind.OBTAIN && head.action.itemId > 0
			&& module.itemSources() != null)
		{
			String pref = state.getItemSourcePref(head.action.itemId);
			String line = module.itemSources().sourceLine(head.action.itemId, state, pref);
			if (line != null)
			{
				return line;
			}
		}
		String why = head.why;
		if (why == null || why.isEmpty() || why.startsWith("Completes automatically"))
		{
			return null;
		}
		// the "Goal · X" line already says what this serves, so strip a
		// trailing "Serves X" clause; if that was the whole note, show
		// nothing (Luke, 2026-07-24: don't duplicate Serves and Goal)
		why = why.replaceAll("(?i)\\.?\\s*Serves .*$", "").trim();
		return why.isEmpty() ? null : why;
	}

	/**
	 * The method line adapts to reality: a curated method whose per-action
	 * xp the drops match wins, else the wiki skill-calculator action the
	 * median drop fingerprints ("Maple longbow (u)"), else — when the
	 * measured pace clearly diverges from the proposal — an honest
	 * "Your method" rather than an invented name. The right side prefers
	 * a live actions-left count (XP Tracker rolling-mean math) over the
	 * pack rate.
	 */
	private void methodLine(Plan.Step head, Skill skill, long xpLeft, double measured)
	{
		long median = gauge.medianDrop();
		// the fingerprint match walks the methods pack + xp-actions catalog;
		// its inputs only change on an xp drop or a level, not per rendered
		// frame (2026-07-20 audit) — memoize the match on (skill, median,
		// level). The cheap pace-divergence fallback stays live since
		// `measured` decays continuously.
		int level = state.getRealLevel(skill);
		List<Object> key = List.of(String.valueOf(skill), median, level);
		if (!key.equals(methodLineKey))
		{
			methodLineKey = key;
			MethodsPack.Method matched = matchMethod(module.methodsPack(), skill, median);
			methodLineLabel = matched != null ? matched.name
				: matchAction(module.xpActions(), skill, median, level);
		}
		String label = methodLineLabel;
		if (label == null)
		{
			label = head.methodName;
			if (median > 0 && !Double.isNaN(measured) && head.methodRate > 0
				&& Math.abs(measured - head.methodRate) / head.methodRate > PACE_DIVERGENCE)
			{
				label = "Your method";
			}
		}
		if (label == null)
		{
			return;
		}

		long actions = gauge.actionsRemaining(xpLeft);
		String right = actions > 0
			? "~" + formatCount(actions) + " actions"
			: head.methodRate > 0 ? compactXp(head.methodRate) + "/hr" : null;
		line(label, UiTokens.CANVAS_LOCKED, right, UiTokens.CANVAS_LOCKED);
	}

	/** Ease the bar toward its target so movement feels smooth, and label
	 * it with the 2dp percent. Jumps (no crawl) when the step changes. */
	private void bar(String stepId, double fraction)
	{
		fraction = Math.min(1, Math.max(0, fraction));
		if (!stepId.equals(displayFractionStepId))
		{
			displayFractionStepId = stepId;
			displayFraction = fraction;
		}
		else
		{
			displayFraction += (fraction - displayFraction) * 0.2;
			if (Math.abs(fraction - displayFraction) < 0.0005)
			{
				displayFraction = fraction;
			}
		}
		panelComponent.getChildren().add(new LabeledBar(displayFraction,
			String.format(Locale.ROOT, "%.2f%%", fraction * 100)));
	}

	/** First live xp seen for a step this session — the bar's stable floor. */
	long anchorFor(String stepId, long liveXp)
	{
		if (stepAnchors.size() > 64)
		{
			stepAnchors.clear();
		}
		return stepAnchors.computeIfAbsent(stepId, id -> liveXp);
	}

	/**
	 * Detect head changes. A vanished old head flashes green only when the
	 * account really satisfies it — a head also vanishes when its goal is
	 * removed, and that must never read as "Done".
	 */
	private void trackHead(Plan plan, Plan.Step head, long now)
	{
		String headId = head == null ? null : head.action.id;
		if (java.util.Objects.equals(headId, lastHead == null ? null : lastHead.id))
		{
			return;
		}
		if (lastHead != null
			&& (plan == null || plan.steps.stream().noneMatch(s -> s.action.id.equals(lastHead.id)))
			&& satisfied(lastHead))
		{
			completedName = lastHead.name;
			flashUntilMs = now + FLASH_MS;
			stepAnchors.remove(lastHead.id);
		}
		lastHead = head == null ? null : head.action;
	}

	/** Does the live account satisfy this action's outcome? */
	private boolean satisfied(Action action)
	{
		switch (action.kind)
		{
			case TRAIN:
				return state.getRealLevel(action.trainSkill) >= action.trainToLevel;
			case QUEST:
				return com.ironhub.requirements.Requirements.parse(
					(action.startOnly ? "queststarted:" : "quest:") + action.questName).isMet(state);
			case KILL:
				return state.getKillCount(action.kcSource) >= action.kcTarget;
			case OBTAIN:
				return action.itemId > 0 && state.canonicalStock(action.itemId) > 0;
			case MANUAL:
				return action.unlockKey != null && state.isUnlocked(action.unlockKey);
			default:
				return false;
		}
	}

	/** "Mark done" only appears while the head is a manual step. */
	private void syncMenu(Plan.Step head)
	{
		boolean manual = head != null && head.action.kind == Action.Kind.MANUAL
			&& head.action.unlockKey != null;
		if (manual != markDoneShown)
		{
			if (manual)
			{
				addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Mark done", MENU_TARGET, e -> markDoneHead());
			}
			else
			{
				removeMenuEntry(MenuAction.RUNELITE_OVERLAY, "Mark done", MENU_TARGET);
			}
			markDoneShown = manual;
		}
	}

	void snoozeHead()
	{
		Plan plan = module.currentPlan();
		Plan.Step head = plan == null ? null : plan.head();
		if (head != null)
		{
			state.togglePlannerSnooze(head.action.id);
		}
	}

	void markDoneHead()
	{
		Plan plan = module.currentPlan();
		Plan.Step head = plan == null ? null : plan.head();
		if (head != null && head.action.unlockKey != null)
		{
			state.setUnlocked(head.action.unlockKey, true);
		}
	}

	/** Time to the step's target level over the RAW xp gap (banked
	 * materials don't make training faster): the player's measured pace
	 * wins while xp is flowing; else the proposed rate; NaN stays an
	 * honest "?". */
	static double ttlHours(Plan.Step head, long xpLeft, double measuredXpPerHour)
	{
		if (!Double.isNaN(measuredXpPerHour) && measuredXpPerHour > 0)
		{
			return xpLeft / measuredXpPerHour;
		}
		if (head.methodRate > 0)
		{
			return xpLeft / (double) head.methodRate;
		}
		return head.hours;
	}

	/** Progress from the session anchor toward the target xp, clamped. */
	static double stepFraction(long anchorXp, long liveXp, long targetXp)
	{
		if (targetXp <= anchorXp)
		{
			return 1;
		}
		return Math.min(1, Math.max(0, (liveXp - anchorXp) / (double) (targetXp - anchorXp)));
	}

	/**
	 * Name the method the drops identify: a median drop that is a clean
	 * small multiple of a method's per-action xp (pack xpEach is per
	 * material; one drop can consume several). Null = no honest match.
	 */
	static MethodsPack.Method matchMethod(MethodsPack pack, Skill skill, long medianDrop)
	{
		if (pack == null || skill == null || medianDrop <= 0)
		{
			return null;
		}
		MethodsPack.SkillLadder ladder = pack.ladder(skill);
		if (ladder == null)
		{
			return null;
		}
		for (MethodsPack.Method method : ladder.methods)
		{
			if (method.xpEach <= 0)
			{
				continue;
			}
			for (int multiple = 1; multiple <= 8; multiple++)
			{
				double expected = method.xpEach * multiple;
				if (Math.abs(medianDrop - expected) / expected <= 0.01)
				{
					return method;
				}
			}
		}
		return null;
	}

	/**
	 * Name the wiki skill-calculator action the median drop fingerprints,
	 * respecting the player's level. Several actions can share one xp
	 * value (stringing vs unstrung longbows) — tied candidates that share
	 * a base name show the base ("Maple longbow"); otherwise no honest
	 * single name exists and this returns null.
	 */
	static String matchAction(com.ironhub.data.XpActionsPack pack, Skill skill,
		long medianDrop, int playerLevel)
	{
		if (pack == null || skill == null || medianDrop <= 0)
		{
			return null;
		}
		com.ironhub.data.XpActionsPack.SkillActions ladder = pack.ladder(skill);
		if (ladder == null)
		{
			return null;
		}
		double bestDiff = Double.MAX_VALUE;
		java.util.List<com.ironhub.data.XpActionsPack.XpAction> best = new java.util.ArrayList<>();
		for (com.ironhub.data.XpActionsPack.XpAction action : ladder.actions)
		{
			if (action.xp <= 0 || action.level > playerLevel)
			{
				continue;
			}
			double diff = Math.abs(medianDrop - action.xp) / action.xp;
			if (diff > 0.01)
			{
				continue;
			}
			if (diff < bestDiff - 1e-9)
			{
				bestDiff = diff;
				best.clear();
			}
			if (Math.abs(diff - bestDiff) <= 1e-9)
			{
				best.add(action);
			}
		}
		if (best.isEmpty())
		{
			return null;
		}
		if (best.size() == 1)
		{
			return best.get(0).name;
		}
		String base = baseName(best.get(0).name);
		for (com.ironhub.data.XpActionsPack.XpAction action : best)
		{
			if (!baseName(action.name).equals(base))
			{
				return null; // genuinely ambiguous — never guess
			}
		}
		return base;
	}

	private static String baseName(String name)
	{
		int paren = name.indexOf(" (");
		return paren > 0 ? name.substring(0, paren) : name;
	}

	private String goalLine(Plan plan, Plan.Step head)
	{
		for (String goalId : head.action.neededBy)
		{
			String name = plan.goalNames.get(goalId);
			if (name != null)
			{
				int more = head.action.neededBy.size() - 1;
				return "Goal · " + name + (more > 0 ? " +" + more : "");
			}
		}
		return null;
	}

	/** "Step N of T" for the head's primary goal, when that goal has more
	 *  than one stage in the plan; null for a single-stage goal (Luke,
	 *  2026-07-24 — replaces the "Next · …" row). */
	private String stageLine(Plan plan, Plan.Step head)
	{
		String goalId = null;
		for (String id : head.action.neededBy)
		{
			if (plan.goalNames.get(id) != null)
			{
				goalId = id;
				break;
			}
		}
		if (goalId == null)
		{
			return null;
		}
		int total = 0;
		int index = 0;
		for (Plan.Step step : plan.steps)
		{
			if (step.snoozed || !step.action.neededBy.contains(goalId))
			{
				continue;
			}
			total++;
			if (step.action.id.equals(head.action.id))
			{
				index = total;
			}
		}
		return total > 1 && index > 0 ? "Step " + index + " of " + total : null;
	}

	private void line(String left, Color leftColor, String right, Color rightColor)
	{
		LineComponent.LineComponentBuilder builder =
			LineComponent.builder().left(left).leftColor(leftColor);
		if (right != null)
		{
			builder.right(right).rightColor(rightColor);
		}
		panelComponent.getChildren().add(builder.build());
	}

	/** A row led by a small icon (the skill, for a TRAIN head — Luke wants
	 *  skill icons, 2026-07-24), then left text and a right-aligned value.
	 *  Falls back to a plain line when there is no icon (headless tests). */
	private void iconLine(java.awt.image.BufferedImage icon, String left, Color leftColor,
		String right, Color rightColor)
	{
		if (icon == null)
		{
			line(left, leftColor, right, rightColor);
			return;
		}
		panelComponent.getChildren().add(new IconLine(icon, left, leftColor, right, rightColor));
	}

	/**
	 * Live xp session for the head's skill, mirroring RuneLite's XP
	 * Tracker math (XpStateSingle, verified upstream): the clock accrues
	 * wall-time from the first drop, so xp/hr decays honestly while idle;
	 * a 60s minimum elapsed keeps the first minute sane; after 10 idle
	 * minutes the per-hour window resets (total gained survives); actions
	 * remaining use their rolling mean of the last 10 action xps. The
	 * median drop (window 20) is ours, for method fingerprinting.
	 */
	static final class XpGauge
	{
		/** XP Tracker's 60s floor: no 2-billion-xp/hr first minutes. */
		static final long MIN_ELAPSED_MS = 60_000;
		/** Idle this long → the per-hour window resets on the next frame. */
		static final long RATE_RESET_MS = 10 * 60_000;
		private static final int ACTION_WINDOW = 10;
		private static final int DROP_WINDOW = 20;

		private Skill skill;
		private long lastXp = -1;
		private long totalGained;
		private long gainedSinceReset;
		private long skillTimeMs;
		private long lastFrameMs;
		private long lastGainMs;
		private final int[] actionExps = new int[ACTION_WINDOW];
		private int actionExpIndex;
		private boolean actionsHistoryInitialized;
		private final ArrayDeque<Long> drops = new ArrayDeque<>();

		void observe(Skill current, long xp, long now)
		{
			if (current != skill)
			{
				reset(current);
			}
			if (lastXp < 0 || xp < lastXp) // first sight, or profile swap
			{
				lastXp = xp;
				lastFrameMs = now;
				return;
			}
			// the clock runs (per frame) while the rate window is live
			if (gainedSinceReset > 0)
			{
				skillTimeMs += Math.max(0, now - lastFrameMs);
				if (xp == lastXp && now - lastGainMs >= RATE_RESET_MS)
				{
					gainedSinceReset = 0;
					skillTimeMs = 0;
				}
			}
			lastFrameMs = now;
			if (xp == lastXp)
			{
				return;
			}
			long delta = xp - lastXp;
			totalGained += delta;
			gainedSinceReset += delta;
			if (actionsHistoryInitialized)
			{
				actionExps[actionExpIndex] = (int) delta;
			}
			else
			{
				java.util.Arrays.fill(actionExps, (int) delta);
				actionsHistoryInitialized = true;
			}
			actionExpIndex = (actionExpIndex + 1) % actionExps.length;
			drops.addLast(delta);
			if (drops.size() > DROP_WINDOW)
			{
				drops.removeFirst();
			}
			lastGainMs = now;
			lastXp = xp;
		}

		private void reset(Skill current)
		{
			skill = current;
			lastXp = -1;
			totalGained = 0;
			gainedSinceReset = 0;
			skillTimeMs = 0;
			lastFrameMs = 0;
			lastGainMs = 0;
			actionExpIndex = 0;
			actionsHistoryInitialized = false;
			drops.clear();
		}

		/** Total xp gained on this skill this session (0 = none yet). */
		long gained()
		{
			return totalGained;
		}

		/** Live measured pace, decaying while idle; NaN with no window. */
		double xpPerHour()
		{
			if (gainedSinceReset <= 0)
			{
				return Double.NaN;
			}
			return gainedSinceReset * 3_600_000.0 / Math.max(MIN_ELAPSED_MS, skillTimeMs);
		}

		/** Actions to cover the gap — XP Tracker's rolling-mean formula. */
		long actionsRemaining(long xpRemaining)
		{
			if (!actionsHistoryInitialized || xpRemaining <= 0)
			{
				return -1;
			}
			long totalActionXp = 0;
			for (int actionXp : actionExps)
			{
				totalActionXp += actionXp;
			}
			if (totalActionXp <= 0)
			{
				return -1;
			}
			long scaled = xpRemaining * actionExps.length;
			return scaled / totalActionXp + (scaled % totalActionXp > 0 ? 1 : 0);
		}

		/** Median xp per drop over the recent window (0 = no drops). */
		long medianDrop()
		{
			if (drops.isEmpty())
			{
				return 0;
			}
			long[] sorted = drops.stream().mapToLong(Long::longValue).sorted().toArray();
			return sorted[sorted.length / 2];
		}
	}

	/** A line led by a 16px icon: icon, shadowed left text, right-aligned
	 *  value — the RuneLite LineComponent look with a skill icon in front. */
	private static final class IconLine implements LayoutableRenderableEntity
	{
		private static final int ICON = 16;
		private static final int GAP = 4;

		private final java.awt.image.BufferedImage icon;
		private final String left;
		private final Color leftColor;
		private final String right;
		private final Color rightColor;
		private final Rectangle bounds = new Rectangle();
		private Point location = new Point();
		private int width = WIDTH - 8;

		IconLine(java.awt.image.BufferedImage icon, String left, Color leftColor,
			String right, Color rightColor)
		{
			this.icon = icon;
			this.left = left == null ? "" : left;
			this.leftColor = leftColor;
			this.right = right;
			this.rightColor = rightColor;
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			FontMetrics metrics = graphics.getFontMetrics();
			int lineH = Math.max(ICON, metrics.getHeight());
			int baseline = location.y + (lineH - metrics.getHeight()) / 2 + metrics.getAscent();
			graphics.drawImage(icon, location.x, location.y + (lineH - ICON) / 2, ICON, ICON, null);

			int textX = location.x + ICON + GAP;
			// truncate the left text so a right value always fits
			int rightW = right == null ? 0 : metrics.stringWidth(right) + GAP;
			String shown = fit(metrics, left, width - (ICON + GAP) - rightW);
			graphics.setColor(Color.BLACK);
			graphics.drawString(shown, textX + 1, baseline + 1);
			graphics.setColor(leftColor);
			graphics.drawString(shown, textX, baseline);

			if (right != null)
			{
				int rx = location.x + width - metrics.stringWidth(right);
				graphics.setColor(Color.BLACK);
				graphics.drawString(right, rx + 1, baseline + 1);
				graphics.setColor(rightColor);
				graphics.drawString(right, rx, baseline);
			}

			Dimension dimension = new Dimension(width, lineH);
			bounds.setLocation(location);
			bounds.setSize(dimension);
			return dimension;
		}

		private static String fit(FontMetrics m, String s, int max)
		{
			if (m.stringWidth(s) <= max || s.isEmpty())
			{
				return s;
			}
			String ell = "…";
			int i = s.length();
			while (i > 0 && m.stringWidth(s.substring(0, i) + ell) > max)
			{
				i--;
			}
			return i <= 0 ? ell : s.substring(0, i) + ell;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point position)
		{
			this.location = position;
		}

		@Override
		public void setPreferredSize(Dimension dimension)
		{
			if (dimension != null && dimension.width > 0)
			{
				this.width = dimension.width;
			}
		}
	}

	/** A thin progress bar (the smaller variant, Luke 2026-07-24): a 6px
	 * accent fill over the standard trough, no in-bar label — the numeric
	 * progress lives on the "xp left" / "KC" line. */
	private static final class LabeledBar implements LayoutableRenderableEntity
	{
		private static final int HEIGHT = 6;

		private final Rectangle bounds = new Rectangle();
		private final double fraction;
		private Point location = new Point();
		private int width = WIDTH - 8;

		LabeledBar(double fraction, String label)
		{
			this.fraction = Math.min(1, Math.max(0, fraction));
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			graphics.setColor(UiTokens.OVERLAY_BAR_TROUGH);
			graphics.fillRect(location.x, location.y + 2, width, HEIGHT);
			Object oldAa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(UiTokens.ACCENT);
			graphics.fill(new Rectangle2D.Double(
				location.x, location.y + 2, width * fraction, HEIGHT));
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				oldAa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAa);

			Dimension dimension = new Dimension(width, HEIGHT + 4);
			bounds.setLocation(location);
			bounds.setSize(dimension);
			return dimension;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point position)
		{
			this.location = position;
		}

		@Override
		public void setPreferredSize(Dimension dimension)
		{
			if (dimension != null && dimension.width > 0)
			{
				this.width = dimension.width;
			}
		}
	}

	// ── compact formatters (were shared with the deleted PlannerTab) ──────

	/** Time as "~2.3h", or null when unknown — the overlay shows NOTHING
	 *  rather than a "?" (Luke, 2026-07-24). */
	static String timeText(double hours)
	{
		return Double.isNaN(hours) ? null : "~" + compactHours(hours);
	}

	static String formatCount(long count)
	{
		return String.format(Locale.ROOT, "%,d", count);
	}

	// ONE formatter set for the goals package — GoalsHubTab carried silently
	// divergent copies ("1.2m" vs "1.2M", different minute rounding) until
	// the 2026-07-20 audit; the tab's rules (Luke's polish rounds) won.
	static String compactXp(long xp)
	{
		if (xp >= 1_000_000)
		{
			return Math.round(xp / 100_000.0) / 10.0 + "M";
		}
		if (xp >= 1_000)
		{
			return Math.round(xp / 1000.0) + "k";
		}
		return String.valueOf(xp);
	}

	static String compactHours(double hours)
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
}
