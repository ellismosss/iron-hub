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
	private GoalsPack pack;
	private GoalsTab tab;

	@Inject
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
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
	}

	@Override
	public void shutDown()
	{
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
			if (pack == null)
			{
				pack = dataPack.load("goals", GoalsPack.class);
			}
			tab = new GoalsTab(state, pack,
				dataPack.load("gear-progression", com.ironhub.data.GearProgressionPack.class),
				itemManager);
		}
		return tab;
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
