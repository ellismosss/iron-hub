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
	private final XpGauge gauge = new XpGauge();

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

	PlannerOverlay(GoalPlannerModule module, AccountState state, IronHubConfig config)
	{
		this.module = module;
		this.state = state;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Snooze step", MENU_TARGET, e -> snoozeHead());
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
		panelComponent.setBackgroundColor(UiTokens.OVERLAY_BG);
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
				if (head.why != null && !head.why.isEmpty())
				{
					line(head.why, UiTokens.CANVAS_LOCKED, null, null);
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

		Plan.Step next = plan.steps.stream()
			.filter(s -> !s.snoozed).skip(1).findFirst().orElse(null);
		if (next != null)
		{
			line("Next · " + next.action.name, UiTokens.CANVAS_LOCKED, null, null);
		}
		String goal = goalLine(plan, head);
		if (goal != null)
		{
			line(goal, UiTokens.CANVAS_LOCKED, null, null);
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
		line(head.action.name, Color.WHITE,
			timeText(ttlHours(head, xpLeft, measured)), UiTokens.OVERLAY_VALUE);

		long anchor = anchorFor(head.action.id, liveXp);
		bar(head.action.id, stepFraction(anchor, liveXp, targetXp));

		line("Lv " + state.getRealLevel(skill) + " of " + head.action.trainToLevel,
			UiTokens.CANVAS_LOCKED,
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
			kc + " of " + head.action.kcTarget, UiTokens.OVERLAY_VALUE);
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

	/** A 14px progress bar: sub-pixel accent fill over the standard trough,
	 * centered percent label in shadowed white. */
	private static final class LabeledBar implements LayoutableRenderableEntity
	{
		private static final int HEIGHT = 14;

		private final Rectangle bounds = new Rectangle();
		private final double fraction;
		private final String label;
		private Point location = new Point();
		private int width = WIDTH - 8;

		LabeledBar(double fraction, String label)
		{
			this.fraction = Math.min(1, Math.max(0, fraction));
			this.label = label;
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

			FontMetrics metrics = graphics.getFontMetrics();
			int textX = location.x + (width - metrics.stringWidth(label)) / 2;
			int textY = location.y + 2
				+ (HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.setColor(Color.BLACK);
			graphics.drawString(label, textX + 1, textY + 1);
			graphics.setColor(Color.WHITE);
			graphics.drawString(label, textX, textY);

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

	static String timeText(double hours)
	{
		return Double.isNaN(hours) ? "?" : "~" + compactHours(hours);
	}

	static String formatCount(long count)
	{
		return String.format(Locale.ROOT, "%,d", count);
	}

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
}
