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
	private GoalsPack pack;
	private GoalsTab tab;

	@Inject
	public GoalPlannerModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
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
			tab = new GoalsTab(state, pack);
		}
		return tab;
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

	public static double progress(GoalsPack.Goal goal, AccountState state)
	{
		List<CompiledStep> steps = compile(goal, state);
		return steps.stream().filter(s -> s.met).count() / (double) steps.size();
	}

	/** First unmet step, or null when the goal is complete. */
	public static CompiledStep nextStep(GoalsPack.Goal goal, AccountState state)
	{
		return compile(goal, state).stream().filter(s -> !s.met).findFirst().orElse(null);
	}
}
