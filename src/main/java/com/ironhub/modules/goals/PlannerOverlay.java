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
	/** Idle gaps beyond this stop counting toward measured xp/hr. */
	static final long GAP_CAP_MS = 3 * 60_000;
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
					PlannerTab.timeText(head.hours), UiTokens.OVERLAY_VALUE);
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
					PlannerTab.formatCount(resource.missing) + " more", UiTokens.CANVAS_WARNING);
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
		gauge.observe(skill, liveXp, now);

		double measured = gauge.xpPerHour();
		line(head.action.name, Color.WHITE,
			PlannerTab.timeText(ttlHours(head, measured)), UiTokens.OVERLAY_VALUE);

		long anchor = anchorFor(head.action.id, liveXp);
		bar(head.action.id, stepFraction(anchor, liveXp, targetXp));

		line("Lv " + state.getRealLevel(skill) + " of " + head.action.trainToLevel,
			UiTokens.CANVAS_LOCKED,
			PlannerTab.compactXp(head.trainXpRemaining) + " left", UiTokens.OVERLAY_VALUE);

		if (gauge.gained() > 0)
		{
			line("+" + PlannerTab.formatCount(gauge.gained()) + " xp", UiTokens.CANVAS_OWNED,
				Double.isNaN(measured) ? null
					: PlannerTab.compactXp(Math.round(measured)) + "/hr", UiTokens.OVERLAY_VALUE);
		}

		methodLine(head, liveXp, targetXp, measured);
	}

	private void renderKill(Plan.Step head)
	{
		int kc = state.getKillCount(head.action.kcSource);
		line(head.action.name, Color.WHITE,
			PlannerTab.timeText(head.hours), UiTokens.OVERLAY_VALUE);
		bar(head.action.id, head.action.kcTarget > 0 ? kc / (double) head.action.kcTarget : 0);
		line("KC", UiTokens.CANVAS_LOCKED,
			kc + " of " + head.action.kcTarget, UiTokens.OVERLAY_VALUE);
	}

	/**
	 * The method line adapts to reality: while the player's xp drops match a
	 * known method's per-action xp, name that method; while their measured
	 * pace clearly diverges from the proposal without a match, own it as
	 * "Your method" rather than invent a name. The right side prefers a
	 * live actions-left count over the pack rate.
	 */
	private void methodLine(Plan.Step head, long liveXp, long targetXp, double measured)
	{
		long median = gauge.medianDrop();
		MethodsPack.Method matched = matchMethod(
			module.methodsPack(), head.action.trainSkill, median);

		String label = head.methodName;
		if (matched != null)
		{
			label = matched.name;
		}
		else if (median > 0 && !Double.isNaN(measured) && head.methodRate > 0
			&& Math.abs(measured - head.methodRate) / head.methodRate > PACE_DIVERGENCE)
		{
			label = "Your method";
		}
		if (label == null)
		{
			return;
		}

		String right = null;
		if (median > 0 && targetXp > liveXp)
		{
			long actions = (targetXp - liveXp + median - 1) / median;
			right = "~" + PlannerTab.formatCount(actions) + " actions";
		}
		else if (head.methodRate > 0)
		{
			right = PlannerTab.compactXp(head.methodRate) + "/hr";
		}
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

	/** Time to the step's target: the player's measured pace wins while xp
	 * is flowing; else the proposed rate; NaN stays an honest "?". */
	static double ttlHours(Plan.Step head, double measuredXpPerHour)
	{
		if (!Double.isNaN(measuredXpPerHour) && measuredXpPerHour > 0)
		{
			return head.trainXpRemaining / measuredXpPerHour;
		}
		if (head.methodRate > 0)
		{
			return head.trainXpRemaining / (double) head.methodRate;
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
	 * Live xp session for the head's skill: gained since first drop,
	 * measured xp/hr over active time (idle gaps capped so a break doesn't
	 * poison the rate), and the median drop for actions-left math.
	 */
	static final class XpGauge
	{
		private static final int DROP_WINDOW = 20;

		private Skill skill;
		private long lastXp = -1;
		private long startXp = -1;
		private long lastGainMs;
		private long activeMs;
		private long gainedTimed;
		private int dropCount;
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
				return;
			}
			if (xp == lastXp)
			{
				return;
			}
			long delta = xp - lastXp;
			if (dropCount == 0)
			{
				startXp = lastXp;
			}
			else
			{
				activeMs += Math.min(now - lastGainMs, GAP_CAP_MS);
				gainedTimed += delta;
			}
			dropCount++;
			lastGainMs = now;
			lastXp = xp;
			drops.addLast(delta);
			if (drops.size() > DROP_WINDOW)
			{
				drops.removeFirst();
			}
		}

		private void reset(Skill current)
		{
			skill = current;
			lastXp = -1;
			startXp = -1;
			lastGainMs = 0;
			activeMs = 0;
			gainedTimed = 0;
			dropCount = 0;
			drops.clear();
		}

		/** XP gained since the first drop this session (0 = none yet). */
		long gained()
		{
			return startXp < 0 ? 0 : lastXp - startXp;
		}

		/** Measured pace; NaN until two drops have set a real interval. */
		double xpPerHour()
		{
			if (dropCount < 2 || activeMs <= 0)
			{
				return Double.NaN;
			}
			return gainedTimed * 3_600_000.0 / activeMs;
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
}
