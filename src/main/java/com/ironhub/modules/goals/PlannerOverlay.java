package com.ironhub.modules.goals;

import com.ironhub.IronHubConfig;
import com.ironhub.engine.Action;
import com.ironhub.engine.Plan;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * The plan-following canvas overlay (frame 3c, grown over the live engine):
 * pinned to the screen, it mirrors the plan head so the player rarely needs
 * the sidebar — step name + live ETA, a slim progress bar (xp for trains,
 * kc for kills), the suggested method and rate, missing resources, the
 * on-deck step, and the goal it all serves. Flashes the finished step green
 * once when the engine detects completion. Display-only; right-click
 * carries Snooze (and Mark done for manual steps). RuneLite handles
 * dragging/pinning and persists the position.
 */
class PlannerOverlay extends OverlayPanel
{
	private static final int WIDTH = 190;       // within the 250x200 budget
	private static final long FLASH_MS = 1_500;
	private static final String MENU_TARGET = "Iron Hub step";

	private final GoalPlannerModule module;
	private final AccountState state;
	private final IronHubConfig config;

	// render-thread bookkeeping (all reads/writes on the client thread)
	private Action lastHead;
	private long anchorXpRemaining;
	private long flashUntilMs;
	private String completedName;
	private boolean markDoneShown;

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

		line(head.action.name, Color.WHITE,
			PlannerTab.timeText(etaHours(head)), UiTokens.OVERLAY_VALUE);

		switch (head.action.kind)
		{
			case TRAIN:
				line("Lv " + state.getRealLevel(head.action.trainSkill)
						+ " of " + head.action.trainToLevel, UiTokens.CANVAS_LOCKED,
					PlannerTab.compactXp(head.trainXpRemaining) + " left", UiTokens.OVERLAY_VALUE);
				panelComponent.getChildren().add(new SlimBar(
					stepFraction(anchorXpRemaining, head.trainXpRemaining)));
				if (head.methodName != null)
				{
					line(head.methodName, UiTokens.CANVAS_LOCKED,
						head.methodRate > 0 ? PlannerTab.compactXp(head.methodRate) + "/hr" : null,
						UiTokens.CANVAS_LOCKED);
				}
				break;
			case KILL:
			{
				int kc = state.getKillCount(head.action.kcSource);
				line("KC", UiTokens.CANVAS_LOCKED,
					kc + " of " + head.action.kcTarget, UiTokens.OVERLAY_VALUE);
				panelComponent.getChildren().add(new SlimBar(
					head.action.kcTarget > 0 ? kc / (double) head.action.kcTarget : 0));
				break;
			}
			default:
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
		}
		lastHead = head == null ? null : head.action;
		anchorXpRemaining = head != null && head.action.kind == Action.Kind.TRAIN
			? head.trainXpRemaining : 0;
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

	/** Live ETA: banked-aware xp remaining over the chosen rate; else the
	 * routed hours (NaN stays an honest "?"). */
	static double etaHours(Plan.Step head)
	{
		if (head.action.kind == Action.Kind.TRAIN && head.methodRate > 0)
		{
			return head.trainXpRemaining / (double) head.methodRate;
		}
		return head.hours;
	}

	/** Share of the step's xp gap closed since it became the head. */
	static double stepFraction(long anchorRemaining, long nowRemaining)
	{
		if (anchorRemaining <= 0)
		{
			return 1;
		}
		return Math.min(1, Math.max(0, 1 - nowRemaining / (double) anchorRemaining));
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

	/** The design system's 6 px accent progress bar (frame 3c). */
	private static final class SlimBar implements LayoutableRenderableEntity
	{
		private final Rectangle bounds = new Rectangle();
		private final double fraction;
		private Point location = new Point();
		private int width = WIDTH - 8;

		SlimBar(double fraction)
		{
			this.fraction = Math.min(1, Math.max(0, fraction));
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			graphics.setColor(UiTokens.OVERLAY_BAR_TROUGH);
			graphics.fillRect(location.x, location.y + 2, width, 6);
			graphics.setColor(UiTokens.ACCENT);
			graphics.fillRect(location.x, location.y + 2, (int) Math.round(width * fraction), 6);
			Dimension dimension = new Dimension(width, 10);
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
