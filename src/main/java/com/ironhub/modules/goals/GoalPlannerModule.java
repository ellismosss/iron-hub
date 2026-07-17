package com.ironhub.modules.goals;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Goal Planner (DESIGN.md §3.13, frame 2d): pick capstone targets from the
 * goals pack; steps compile through the shared requirement graph and
 * auto-complete from account state — detectable steps are never ticked by
 * hand, manual (free-text) steps are click-to-tick.
 */
@Slf4j
@Singleton
public class GoalPlannerModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final net.runelite.client.game.SkillIconManager skillIconManager; // null in headless tests
	private final net.runelite.client.ui.overlay.OverlayManager overlayManager; // null in headless tests
	private net.runelite.client.eventbus.EventBus eventBus; // null in headless tests
	private PlannerOverlay overlay;
	private GoalsPack pack;
	private com.ironhub.data.GearProgressionPack gearPack;
	private com.ironhub.data.BankedXpPack bankedPack;
	private com.ironhub.data.XpActionsPack xpActionsPack;
	private com.ironhub.engine.EnginePacks enginePacks;
	private PlannerTab tab;

	// ── engine wiring: one worker, debounced replans, published plans ──
	private final java.util.concurrent.ScheduledExecutorService plannerExecutor =
		java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "iron-hub-planner");
			t.setDaemon(true);
			return t;
		});
	private java.util.concurrent.ScheduledFuture<?> pendingReplan;
	private volatile com.ironhub.engine.Plan currentPlan;
	/** Cross-module read seam (WhatNow, Dashboard): the freshest plan. */
	private static volatile com.ironhub.engine.Plan sharedPlan;
	/** Plan hours at session start — the "since last session" anchor. */
	private volatile double sessionStartPlanHours = -1;
	private final java.util.List<Runnable> planListeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();
	private final Runnable stateListener = this::requestReplan;
	private volatile boolean engineActive;

	/** Test convenience (headless: no icon/overlay managers). */
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager)
	{
		this(state, config, dataPack, itemManager, null, null, null);
	}

	@Inject
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIconManager,
		net.runelite.client.ui.overlay.OverlayManager overlayManager,
		net.runelite.client.eventbus.EventBus eventBus)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.overlayManager = overlayManager;
		this.eventBus = eventBus;
	}

	@Override
	public String name()
	{
		return "Goal planner";
	}

	@Override
	public boolean enabled()
	{
		return config.goalPlanner();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("goals", GoalsPack.class);
		gearPack = dataPack.load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		bankedPack = dataPack.load("banked-xp", com.ironhub.data.BankedXpPack.class);
		xpActionsPack = dataPack.load("xp-actions", com.ironhub.data.XpActionsPack.class);
		enginePacks = new com.ironhub.engine.EnginePacks(
			dataPack.load("quests", com.ironhub.data.QuestsPack.class),
			dataPack.load("methods", com.ironhub.data.MethodsPack.class),
			dataPack.load("effects", com.ironhub.data.EffectsPack.class),
			gearPack,
			dataPack.load("boosts", com.ironhub.data.BoostsPack.class),
			dataPack.load("diaries", com.ironhub.data.DiariesPack.class));
		state.addListener(stateListener);
		engineActive = true;
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (overlayManager != null)
		{
			overlay = new PlannerOverlay(this, state, config);
			overlayManager.add(overlay);
		}
		requestReplan();
	}

	/** A theme flip re-clothes the tab: drop it, the panel's next mount
	 *  builds a fresh one (the open block is already closed by then). */
	@net.runelite.client.eventbus.Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
	{
		if (IronHubConfig.GROUP.equals(event.getGroup()) && "osrsTheme".equals(event.getKey()))
		{
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (tab != null)
				{
					tab.dispose();
					tab = null;
				}
			});
		}
	}

	@Override
	public void shutDown()
	{
		engineActive = false;
		state.removeListener(stateListener);
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		sharedPlan = null; // never leak a stale plan across profiles/lifecycles
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (pendingReplan != null)
		{
			pendingReplan.cancel(false);
		}
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			if (enginePacks == null)
			{
				startUp();
			}
			tab = new PlannerTab(this, state, pack, gearPack, itemManager, skillIconManager,
				config.osrsTheme());
			if (currentPlan != null)
			{
				tab.onPlanUpdated(currentPlan);
			}
		}
		return tab;
	}

	// ── the engine loop ────────────────────────────────────────────────

	/** Debounced off-client-thread replan; listeners fire on the EDT. */
	synchronized void requestReplan()
	{
		if (enginePacks == null)
		{
			return;
		}
		if (pendingReplan != null)
		{
			pendingReplan.cancel(false);
		}
		pendingReplan = plannerExecutor.schedule(this::replanNow, 400,
			java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private void replanNow()
	{
		try
		{
			if (!engineActive)
			{
				return;
			}
			com.ironhub.engine.Plan plan = com.ironhub.engine.PlannerService.plan(
				state, enginePacks, bankedPack, unmetGoals(), state.plannerConstraints());
			if (!engineActive)
			{
				return; // shut down mid-computation: drop the result
			}
			if (sessionStartPlanHours < 0)
			{
				sessionStartPlanHours = state.getLastPlanHours();
			}
			currentPlan = plan;
			sharedPlan = plan;
			state.recordPlanHours(plan.knownHours);
			for (Runnable listener : planListeners)
			{
				javax.swing.SwingUtilities.invokeLater(listener);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("replan failed", e);
		}
	}

	com.ironhub.data.MethodsPack methodsPack()
	{
		return enginePacks == null ? null : enginePacks.methods;
	}

	com.ironhub.data.XpActionsPack xpActions()
	{
		return xpActionsPack;
	}

	com.ironhub.data.QuestsPack.QuestEntry questEntry(String name)
	{
		return enginePacks == null || name == null ? null : enginePacks.quest(name);
	}

	java.util.List<String> diaryTierReqs(String region, String tier)
	{
		return enginePacks == null ? java.util.List.of()
			: enginePacks.diaryTierReqs(region, tier);
	}

	java.util.List<String> diaryTaskReqs(String slug)
	{
		return enginePacks == null ? java.util.List.of()
			: enginePacks.diaryTaskReqs(slug);
	}

	/** The latest computed plan (null until the first replan lands). */
	public com.ironhub.engine.Plan currentPlan()
	{
		return currentPlan;
	}

	/** The freshest plan for cross-module consumers; null pre-first-replan. */
	public static com.ironhub.engine.Plan sharedPlan()
	{
		return sharedPlan;
	}

	/** Known plan hours at session start; negative = no earlier session. */
	double sessionStartPlanHours()
	{
		return sessionStartPlanHours;
	}

	void addPlanListener(Runnable listener)
	{
		planListeners.add(listener);
	}

	void removePlanListener(Runnable listener)
	{
		planListeners.remove(listener);
	}

	/** Selected, unmet goals across every source (pack + gear + CA + diary). */
	java.util.List<GoalsPack.Goal> unmetGoals()
	{
		java.util.List<GoalsPack.Goal> unmet = new ArrayList<>();
		for (GoalsPack.Goal goal : allGoals(pack, gearPack, state))
		{
			if (state.getSelectedGoals().contains(goal.getId()) && !isAchieved(goal, state))
			{
				unmet.add(goal);
			}
		}
		return unmet;
	}

	/**
	 * Dry-run merge preview for the add-goal flow: hours the candidate adds
	 * on top of the current selection, and how many of its actions are
	 * already in the merged plan (shared). Runs on the caller's thread —
	 * call it off the EDT for live typing, or accept ~100ms.
	 */
	public MergePreview previewMerge(GoalsPack.Goal candidate)
	{
		java.util.List<GoalsPack.Goal> base = unmetGoals();
		com.ironhub.engine.ActionDag baseDag =
			com.ironhub.engine.GoalExpander.expand(base, state, enginePacks);
		java.util.Set<String> baseIds = new java.util.HashSet<>();
		baseDag.nodes().forEach(n -> baseIds.add(n.id));

		java.util.List<GoalsPack.Goal> merged = new ArrayList<>(base);
		merged.add(candidate);
		com.ironhub.engine.Plan withPlan = com.ironhub.engine.PlannerService.plan(
			state, enginePacks, bankedPack, merged, state.plannerConstraints());
		com.ironhub.engine.Plan basePlan = currentPlan != null ? currentPlan
			: com.ironhub.engine.PlannerService.plan(
				state, enginePacks, bankedPack, base, state.plannerConstraints());

		int candidateNodes = 0;
		int shared = 0;
		for (com.ironhub.engine.Plan.Step step : withPlan.steps)
		{
			if (step.action.neededBy.contains(candidate.getId()))
			{
				candidateNodes++;
				if (baseIds.contains(step.action.id))
				{
					shared++;
				}
			}
		}
		return new MergePreview(Math.max(0, withPlan.knownHours - basePlan.knownHours),
			candidateNodes, shared);
	}

	/** Add-goal preview numbers (ENGINE-DESIGN merge preview). */
	public static class MergePreview
	{
		public final double addedHours;
		public final int steps;
		public final int shared;

		MergePreview(double addedHours, int steps, int shared)
		{
			this.addedHours = addedHours;
			this.steps = steps;
			this.shared = shared;
		}
	}

	/**
	 * Remove a goal wherever it lives: CA/diary goals drop their module
	 * seed (which also deselects); everything else — pack goals, gear
	 * targets, custom skill goals — simply deselects.
	 */
	public static void removeGoal(AccountState state, String goalId)
	{
		if (goalId.startsWith("ca:"))
		{
			state.removeCaGoal(Integer.parseInt(goalId.substring(3)));
		}
		else if (goalId.startsWith("diary:"))
		{
			state.removeDiaryGoal(goalId.substring("diary:".length()));
		}
		else if (goalId.startsWith("clog:"))
		{
			state.removeClogGoal(Integer.parseInt(goalId.substring("clog:".length())));
		}
		else if (goalId.startsWith("custom:"))
		{
			state.removeCustomGoal(goalId);
		}
		else
		{
			state.selectGoal(goalId, false);
		}
	}

	// ── gear-chart targets as synthetic goals ─────────────────────────

	/**
	 * A targeted gear-chart item as a goal: its requirements become steps,
	 * a final "Obtain" step and the achieved proof are ownership (or the
	 * manual mark for undetectable entries like POH furniture).
	 */
	public static GoalsPack.Goal toGoal(com.ironhub.data.GearProgressionPack.Item item)
	{
		String proof = item.isManual()
			? "unlock:" + item.markKey()
			: (item.isExact() ? "itemx:" : "item:") + item.getItemId();
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(item.goalId());
		goal.setName(item.getName());
		goal.setIconItemId(item.icon());
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (String raw : item.getRequirements())
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(Requirements.parse(raw).describe());
			step.setRequirement(raw);
			steps.add(step);
		}
		GoalsPack.Step obtain = new GoalsPack.Step();
		obtain.setLabel("Obtain " + item.getName());
		obtain.setRequirement(proof);
		steps.add(obtain);
		goal.setSteps(steps);
		goal.setAchieved(List.of(proof));
		return goal;
	}

	// ── combat-achievement tasks as synthetic goals ───────────────────

	/**
	 * A Combat Achievement task added from the CA tab: one step — the task
	 * itself — proven by the {@code catask_<id>} unlock flag the CA module
	 * marks once the live catalog shows the task completed.
	 */
	public static GoalsPack.Goal toCaGoal(String taskId, com.ironhub.state.PersistedState.CaGoal seed)
	{
		String proof = "unlock:catask_" + taskId;
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId("ca:" + taskId);
		goal.setName(seed.name);
		GoalsPack.Step step = new GoalsPack.Step();
		step.setLabel(seed.description + " (" + seed.tier + " combat task)");
		step.setRequirement(proof);
		goal.setSteps(List.of(step));
		goal.setAchieved(List.of(proof));
		return goal;
	}

	/**
	 * An achievement diary task added from the diaries tab: one step — the
	 * task itself — proven by the {@code diarytask_<slug>} unlock flag the
	 * diaries module marks once the completion flag (or the tier's own
	 * completion) shows the task done.
	 */
	public static GoalsPack.Goal toDiaryGoal(String slug, com.ironhub.state.PersistedState.DiaryGoal seed)
	{
		String proof = "unlock:diarytask_" + slug;
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId("diary:" + slug);
		goal.setName(seed.task);
		GoalsPack.Step step = new GoalsPack.Step();
		step.setLabel(seed.task + " (" + seed.region + " " + seed.tier + " diary)");
		step.setRequirement(proof);
		goal.setSteps(List.of(step));
		goal.setAchieved(List.of(proof));
		return goal;
	}

	/**
	 * A collection-log slot added from the collection log tab: the source
	 * activity's requirements become steps, then obtaining the slot itself —
	 * proven by the {@code clogitem_<id>} unlock the collection-log module
	 * marks when the slot is seen obtained (chat drop or Log Sync).
	 */
	public static GoalsPack.Goal toClogGoal(String itemId, com.ironhub.state.PersistedState.ClogGoal seed)
	{
		String proof = "unlock:clogitem_" + itemId;
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId("clog:" + itemId);
		goal.setName(seed.name);
		goal.setIconItemId(Integer.parseInt(itemId));
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (String raw : seed.reqs)
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(Requirements.parse(raw).describe());
			step.setRequirement(raw);
			steps.add(step);
		}
		GoalsPack.Step obtain = new GoalsPack.Step();
		obtain.setLabel("Obtain " + seed.name + " (" + seed.activity + ")");
		obtain.setRequirement(proof);
		steps.add(obtain);
		goal.setSteps(steps);
		goal.setAchieved(List.of(proof));
		return goal;
	}

	/** A user-typed goal ("Agility 70"): one detectable step, achieved
	 * when its requirement holds. */
	public static GoalsPack.Goal toCustomGoal(String goalId, com.ironhub.state.PersistedState.CustomGoal seed)
	{
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(goalId);
		goal.setName(seed.name);
		GoalsPack.Step step = new GoalsPack.Step();
		step.setLabel(seed.name);
		step.setRequirement(seed.req);
		goal.setSteps(List.of(step));
		goal.setAchieved(List.of(seed.req));
		return goal;
	}

	/** Pack goals plus a synthetic goal per targeted gear-chart item and
	 * per combat/diary task added from its module tab. */
	public static List<GoalsPack.Goal> allGoals(GoalsPack goals,
		com.ironhub.data.GearProgressionPack gear, AccountState state)
	{
		List<GoalsPack.Goal> all = new ArrayList<>(goals.getGoals());
		for (com.ironhub.data.GearProgressionPack.Phase phase : gear.getPhases())
		{
			for (com.ironhub.data.GearProgressionPack.Group group : phase.getGroups())
			{
				for (com.ironhub.data.GearProgressionPack.Item item : group.getItems())
				{
					if (state.getSelectedGoals().contains(item.goalId()))
					{
						all.add(toGoal(item));
					}
				}
			}
		}
		state.getCaGoals().forEach((taskId, seed) ->
		{
			if (state.getSelectedGoals().contains("ca:" + taskId))
			{
				all.add(toCaGoal(taskId, seed));
			}
		});
		state.getDiaryGoals().forEach((slug, seed) ->
		{
			if (state.getSelectedGoals().contains("diary:" + slug))
			{
				all.add(toDiaryGoal(slug, seed));
			}
		});
		state.getClogGoals().forEach((itemId, seed) ->
		{
			if (state.getSelectedGoals().contains("clog:" + itemId))
			{
				all.add(toClogGoal(itemId, seed));
			}
		});
		state.getCustomGoals().forEach((goalId, seed) ->
		{
			if (state.getSelectedGoals().contains(goalId))
			{
				all.add(toCustomGoal(goalId, seed));
			}
		});
		return all;
	}

	// ── compilation (pure; static for tests) ──────────────────────────

	/** A goal step evaluated against the account. */
	public static class CompiledStep
	{
		public final String label;
		public final boolean met;
		public final boolean manual;
		public final String unlockKey; // manual steps tick via this unlock flag

		CompiledStep(String label, boolean met, boolean manual, String unlockKey)
		{
			this.label = label;
			this.met = met;
			this.manual = manual;
			this.unlockKey = unlockKey;
		}
	}

	/** Compile a goal's steps: detectable ones evaluate live. */
	public static List<CompiledStep> compile(GoalsPack.Goal goal, AccountState state)
	{
		List<CompiledStep> steps = new ArrayList<>();
		for (int i = 0; i < goal.getSteps().size(); i++)
		{
			GoalsPack.Step step = goal.getSteps().get(i);
			Requirement requirement = Requirements.parse(step.getRequirement());
			boolean manual = Requirements.isManual(requirement);
			String key = "goalstep:" + goal.getId() + ":" + i;
			boolean met = manual ? state.isUnlocked(key) : requirement.isMet(state);
			steps.add(new CompiledStep(step.getLabel(), met, manual, key));
		}
		return steps;
	}

	/**
	 * Complete: every step met, or the pack's ownership proof holds — owning
	 * the end product trumps steps that can't detect the past (pre-plugin
	 * kill counts, already-spent marks of grace).
	 */
	public static boolean isAchieved(GoalsPack.Goal goal, AccountState state)
	{
		List<String> proof = goal.getAchieved();
		if (proof != null && !proof.isEmpty()
			&& proof.stream().allMatch(r -> Requirements.parse(r).isMet(state)))
		{
			return true;
		}
		return compile(goal, state).stream().allMatch(s -> s.met);
	}

	public static double progress(GoalsPack.Goal goal, AccountState state)
	{
		if (isAchieved(goal, state))
		{
			return 1.0;
		}
		List<CompiledStep> steps = compile(goal, state);
		return steps.stream().filter(s -> s.met).count() / (double) steps.size();
	}

	/** First unmet step, or null when the goal is complete. */
	public static CompiledStep nextStep(GoalsPack.Goal goal, AccountState state)
	{
		if (isAchieved(goal, state))
		{
			return null;
		}
		return compile(goal, state).stream().filter(s -> !s.met).findFirst().orElse(null);
	}
}
