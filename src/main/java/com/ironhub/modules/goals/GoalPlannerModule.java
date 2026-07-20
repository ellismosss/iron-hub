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
	private PlannerOverlay overlay;
	private GoalsPack pack;
	private com.ironhub.data.GearProgressionPack gearPack;
	private com.ironhub.data.BankedXpPack bankedPack;
	private com.ironhub.data.XpActionsPack xpActionsPack;
	private com.ironhub.data.RecipesPack recipesPack;
	private com.ironhub.engine.EnginePacks enginePacks;
	private GoalsHubTab tab;

	// ── engine wiring: one worker, debounced replans, published plans ──
	// created in startUp, terminated in shutDown — construction-time
	// executors left two threads alive for the JVM lifetime after a module
	// toggle-off and pinned the classloader after a hub uninstall
	// (2026-07-20 audit)
	private volatile java.util.concurrent.ScheduledExecutorService plannerExecutor;
	private java.util.concurrent.ScheduledFuture<?> pendingReplan;
	private volatile com.ironhub.engine.Plan currentPlan;
	/** Stepping-stone suggestions (G6), computed off the planner thread so a
	 *  ~40-candidate sweep never delays a replan; memoized on plan fingerprint. */
	private volatile java.util.concurrent.ExecutorService suggestExecutor;
	private volatile java.util.List<Suggester.Suggestion> suggestions = java.util.List.of();
	private volatile String suggestionsFingerprint = "";
	private volatile String lastNotifiedFingerprint; // planner thread only
	/** Cross-module read seam (WhatNow, Dashboard): the freshest plan. */
	private static volatile com.ironhub.engine.Plan sharedPlan;
	/** Plan hours at session start — the "since last session" anchor. */
	private volatile double sessionStartPlanHours = -1;
	private final java.util.List<Runnable> planListeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();
	private final Runnable stateListener = this::requestReplan;
	private volatile boolean engineActive;

	// ── completion archive (G2): remember which selected goals are achieved
	// and which have ever been worked (seen unachieved-while-selected), so a
	// transition to achieved records honestly — dated now() only if worked
	// this session, else "detected" (0). Touched only on the planner thread. ──
	private final java.util.Set<String> achievedGoalIds = new java.util.HashSet<>();
	private final java.util.Set<String> seenActiveGoalIds = new java.util.HashSet<>();
	private volatile boolean firstDetection = true;
	private int detectionGeneration; // planner thread only

	/** Test convenience (headless: no icon/overlay managers). */
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager)
	{
		this(state, config, dataPack, itemManager, null, null);
	}

	@Inject
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIconManager,
		net.runelite.client.ui.overlay.OverlayManager overlayManager)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.overlayManager = overlayManager;
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
		plannerExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "iron-hub-planner");
			t.setDaemon(true);
			return t;
		});
		suggestExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "iron-hub-suggester");
			t.setDaemon(true);
			return t;
		});
		pack = dataPack.load("goals", GoalsPack.class);
		gearPack = dataPack.load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		bankedPack = dataPack.load("banked-xp", com.ironhub.data.BankedXpPack.class);
		xpActionsPack = dataPack.load("xp-actions", com.ironhub.data.XpActionsPack.class);
		recipesPack = dataPack.load("recipes", com.ironhub.data.RecipesPack.class);
		enginePacks = new com.ironhub.engine.EnginePacks(
			dataPack.load("quests", com.ironhub.data.QuestsPack.class),
			dataPack.load("methods", com.ironhub.data.MethodsPack.class),
			dataPack.load("effects", com.ironhub.data.EffectsPack.class),
			gearPack,
			dataPack.load("boosts", com.ironhub.data.BoostsPack.class),
			dataPack.load("diaries", com.ironhub.data.DiariesPack.class),
			dataPack.load("clog", com.ironhub.data.ClogPack.class));
		state.addListener(stateListener);
		engineActive = true;
		if (overlayManager != null)
		{
			overlay = new PlannerOverlay(this, state, config);
			overlayManager.add(overlay);
		}
		requestReplan();
	}

	/** A theme flip re-clothes the tab: drop it, the panel's next mount
	 *  builds a fresh one (the open block is already closed by then). */
	@Override
	public void onThemeChanged()
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

	@Override
	public void shutDown()
	{
		engineActive = false;
		state.removeListener(stateListener);
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
		// wait out an IN-FLIGHT replan (bounded): it mutates state and
		// persists, and returning while that write runs hands the caller a
		// profile file mid-write (profile switches; headless reloads). The
		// single-threaded executor makes a no-op barrier a completion fence;
		// with nothing running it returns immediately.
		if (plannerExecutor != null)
		{
			try
			{
				plannerExecutor.submit(() -> { })
					.get(2, java.util.concurrent.TimeUnit.SECONDS);
			}
			catch (Exception e)
			{
				log.debug("replan still running at shutdown", e);
			}
			plannerExecutor.shutdown();
		}
		if (suggestExecutor != null)
		{
			suggestExecutor.shutdownNow(); // suggestions are droppable mid-compute
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
			// the tab's constructor reads currentPlan itself — the old extra
			// onPlanUpdated queued a redundant ungated rebuild (2026-07-20 audit)
			tab = new GoalsHubTab(this, state, pack, gearPack, itemManager, skillIconManager,
				config.osrsTheme());
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
			// detect BEFORE publishing: once currentPlan is visible, callers
			// may mutate state, and this pass's detector must have already
			// seen each active goal unachieved (else a completion lands
			// "detected" instead of dated — a real race, caught by
			// GoalArchiveTest under load)
			detectCompletions(plan);
			currentPlan = plan;
			sharedPlan = plan;
			state.recordPlanHours(plan.knownHours);
			// the engine is deterministic: a state change that doesn't touch
			// the plan reproduces it byte-for-byte, and notifying anyway
			// rebuilt the (visible) Goals tab twice per burst (2026-07-20
			// audit). The state listener still covers state-driven repaint.
			if (!plan.fingerprint.equals(lastNotifiedFingerprint))
			{
				lastNotifiedFingerprint = plan.fingerprint;
				for (Runnable listener : planListeners)
				{
					javax.swing.SwingUtilities.invokeLater(listener);
				}
			}
			scheduleSuggestions(plan);
		}
		catch (RuntimeException e)
		{
			log.warn("replan failed", e);
		}
	}

	/**
	 * Compute stepping-stone suggestions for a settled plan, on a SEPARATE
	 * executor so the ~40-candidate router sweep never delays the next
	 * replan. Memoized on plan fingerprint; abandoned if a newer plan lands
	 * before it runs (the numbers would be stale) (G6).
	 */
	private void scheduleSuggestions(com.ironhub.engine.Plan plan)
	{
		if (plan.fingerprint.equals(suggestionsFingerprint))
		{
			return; // already computed for this exact plan
		}
		suggestExecutor.submit(() ->
		{
			try
			{
				com.ironhub.engine.Plan current = currentPlan;
				if (!engineActive || current == null
					|| !current.fingerprint.equals(plan.fingerprint))
				{
					return; // a newer replan superseded this one
				}
				java.util.List<Suggester.Suggestion> computed = Suggester.compute(
					unmetGoals(), state, enginePacks, bankedPack,
					state.plannerConstraints(), plan);
				if (!engineActive || currentPlan == null
					|| !currentPlan.fingerprint.equals(plan.fingerprint))
				{
					return; // stale by the time we finished — drop it
				}
				suggestions = computed;
				suggestionsFingerprint = plan.fingerprint;
				for (Runnable listener : planListeners)
				{
					javax.swing.SwingUtilities.invokeLater(listener);
				}
			}
			catch (RuntimeException e)
			{
				log.warn("suggestion compute failed", e);
			}
		});
	}

	/** The freshest stepping-stone suggestions (empty until first computed). */
	public java.util.List<Suggester.Suggestion> suggestions()
	{
		return suggestions;
	}

	/**
	 * Archive goals that just completed (G2). A SELECTED goal flipping
	 * unachieved→achieved records one {@link com.ironhub.state.PersistedState.GoalRecord}
	 * (upserted by id): dated now() if the goal was ever seen unachieved this
	 * session (genuinely worked), else 0 = "detected" (a pre-plugin feat or
	 * already-satisfied at add). Goal removal drops out of the selected set
	 * and never records — the never-flash-Done rule. The first pass never
	 * re-records a goal that already has an archive entry, so a login replay
	 * of an already-completed goal keeps its original date. Planner-thread only.
	 */
	private void detectCompletions(com.ironhub.engine.Plan plan)
	{
		// a mid-session profile switch resets the session sets: goal ids are
		// content-derived and identical across profiles, so profile A's
		// achieved/seen sets would re-date or clobber profile B's archive
		// records (2026-07-20 audit)
		int generation = state.profileGeneration();
		if (generation != detectionGeneration)
		{
			detectionGeneration = generation;
			achievedGoalIds.clear();
			seenActiveGoalIds.clear();
			firstDetection = true;
		}
		java.util.Set<String> nowAchieved = new java.util.HashSet<>();
		for (GoalsPack.Goal goal : allGoals(pack, gearPack, state))
		{
			if (!state.getSelectedGoals().contains(goal.getId()))
			{
				continue;
			}
			if (isAchieved(goal, state))
			{
				nowAchieved.add(goal.getId());
			}
			else
			{
				seenActiveGoalIds.add(goal.getId());
			}
		}
		java.util.Set<String> recorded = new java.util.HashSet<>();
		for (com.ironhub.state.PersistedState.GoalRecord r : state.getGoalRecords())
		{
			recorded.add(r.goalId);
		}
		for (String goalId : nowAchieved)
		{
			if (achievedGoalIds.contains(goalId))
			{
				continue; // no transition — already known achieved this session
			}
			if (firstDetection && recorded.contains(goalId))
			{
				continue; // login replay of an archived goal — keep its record
			}
			com.ironhub.state.PersistedState.GoalSeed seed = state.getGoalSeeds().get(goalId);
			com.ironhub.state.PersistedState.GoalRecord record =
				new com.ironhub.state.PersistedState.GoalRecord();
			record.goalId = goalId;
			record.name = nameOf(goalId, seed);
			record.family = familyOf(goalId, seed);
			record.addedAt = seed == null ? 0 : seed.addedAt;
			record.estimatedHours = seed == null ? 0 : seed.estimatedHours;
			record.hoursAtCompletion = plan.knownHours;
			// dated only when genuinely worked this session; else "detected"
			record.completedAt = !firstDetection && seenActiveGoalIds.contains(goalId)
				? System.currentTimeMillis() : 0;
			state.recordGoalCompletion(record);
		}
		achievedGoalIds.clear();
		achievedGoalIds.addAll(nowAchieved);
		firstDetection = false;
	}

	/** A completed goal's display name: from the seed, else the matching
	 *  goal in the live catalog, else the id. */
	private String nameOf(String goalId, com.ironhub.state.PersistedState.GoalSeed seed)
	{
		if (seed != null)
		{
			return seed.name;
		}
		for (GoalsPack.Goal goal : allGoals(pack, gearPack, state))
		{
			if (goal.getId().equals(goalId))
			{
				return goal.getName();
			}
		}
		return goalId;
	}

	/** A goal's archive family: its seed's family, else the id prefix
	 *  (gear:/pack goals have no seed). */
	private static String familyOf(String goalId, com.ironhub.state.PersistedState.GoalSeed seed)
	{
		if (seed != null)
		{
			return seed.family;
		}
		int colon = goalId.indexOf(':');
		return colon > 0 ? goalId.substring(0, colon) : "pack";
	}

	com.ironhub.data.MethodsPack methodsPack()
	{
		return enginePacks == null ? null : enginePacks.methods;
	}

	com.ironhub.data.EffectsPack effectsPack()
	{
		return enginePacks == null ? null : enginePacks.effects;
	}

	com.ironhub.data.QuestsPack questsPack()
	{
		return enginePacks == null ? null : enginePacks.quests;
	}

	/** The drop/kill rate source (G3), for OBTAIN drop-rate display labels. */
	com.ironhub.engine.RateSource ratesSource()
	{
		return enginePacks == null ? null : enginePacks.rates;
	}

	/** Item recipes, for supply-goal component-material breakdown. */
	com.ironhub.data.RecipesPack recipesPack()
	{
		return recipesPack;
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
	/**
	 * Fill the just-added goal's seed.estimatedHours off the EDT — the
	 * archive's "est" figure. Its only writer was PlannerTab's add flow,
	 * deleted in G7 part 2, so every goal archived since recorded 0 and
	 * rendered "est —" (2026-07-20 audit). Best-effort: an unknown-cost
	 * goal honestly stays 0.
	 */
	void captureEstimate(String goalId)
	{
		if (enginePacks == null)
		{
			return;
		}
		plannerExecutor.execute(() ->
		{
			try
			{
				if (!engineActive || state.getGoalSeeds().get(goalId) == null)
				{
					return;
				}
				java.util.List<GoalsPack.Goal> all = unmetGoals();
				java.util.List<GoalsPack.Goal> base = new ArrayList<>();
				GoalsPack.Goal candidate = null;
				for (GoalsPack.Goal goal : all)
				{
					if (goal.getId().equals(goalId))
					{
						candidate = goal;
					}
					else
					{
						base.add(goal);
					}
				}
				if (candidate == null)
				{
					return;
				}
				com.ironhub.engine.Plan with = com.ironhub.engine.PlannerService.plan(
					state, enginePacks, bankedPack, all, state.plannerConstraints());
				com.ironhub.engine.Plan without = com.ironhub.engine.PlannerService.plan(
					state, enginePacks, bankedPack, base, state.plannerConstraints());
				double added = with.knownHours - without.knownHours;
				if (!Double.isNaN(added))
				{
					state.setGoalEstimatedHours(goalId, Math.max(0, added));
				}
			}
			catch (RuntimeException e)
			{
				log.debug("estimate capture failed for {}", goalId, e);
			}
		});
	}

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
	 * Remove a goal wherever it lives: seeded goals drop their persisted
	 * seed (which also deselects); everything else — pack goals, gear
	 * targets — simply deselects.
	 */
	public static void removeGoal(AccountState state, String goalId)
	{
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
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

	// ── persisted goal seeds as goals (every synthetic family) ────────

	/**
	 * A persisted {@link com.ironhub.state.PersistedState.GoalSeed} as a
	 * planner goal — mechanical: the seed already carries the finished
	 * labels, steps and proofs (built by
	 * {@link com.ironhub.state.GoalSeeds} at add/migration time).
	 */
	public static GoalsPack.Goal toGoal(com.ironhub.state.PersistedState.GoalSeed seed)
	{
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(seed.id);
		goal.setName(seed.name);
		if (seed.iconItemId > 0)
		{
			goal.setIconItemId(seed.iconItemId);
		}
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (com.ironhub.state.PersistedState.SeedStep raw : seed.steps)
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(raw.label);
			step.setRequirement(raw.requirement);
			steps.add(step);
		}
		goal.setSteps(steps);
		goal.setAchieved(new ArrayList<>(seed.achieved));
		return goal;
	}

	/** Pack goals plus a synthetic goal per targeted gear-chart item and
	 * per persisted goal seed (CA/diary/clue/clog/custom families). */
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
		state.getGoalSeeds().forEach((goalId, seed) ->
		{
			if (state.getSelectedGoals().contains(goalId))
			{
				all.add(toGoal(seed));
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
		return proofHolds(goal, state)
			|| compile(goal, state).stream().allMatch(s -> s.met);
	}

	/** The pack's ownership proof — owning the end product trumps steps. */
	private static boolean proofHolds(GoalsPack.Goal goal, AccountState state)
	{
		List<String> proof = goal.getAchieved();
		return proof != null && !proof.isEmpty()
			&& proof.stream().allMatch(r -> Requirements.parse(r).isMet(state));
	}

	// progress/nextStep compile ONCE and derive achieved from the same
	// list — the old isAchieved-then-compile-again shape evaluated every
	// step's requirements two or three times per goal row per rebuild,
	// on the EDT (2026-07-20 audit)

	public static double progress(GoalsPack.Goal goal, AccountState state)
	{
		if (proofHolds(goal, state))
		{
			return 1.0;
		}
		List<CompiledStep> steps = compile(goal, state);
		long met = steps.stream().filter(s -> s.met).count();
		return steps.isEmpty() || met == steps.size()
			? 1.0 : met / (double) steps.size();
	}

	/** First unmet step, or null when the goal is complete. */
	public static CompiledStep nextStep(GoalsPack.Goal goal, AccountState state)
	{
		if (proofHolds(goal, state))
		{
			return null;
		}
		return compile(goal, state).stream().filter(s -> !s.met).findFirst().orElse(null);
	}
}
