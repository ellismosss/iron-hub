package com.ironhub.modules.goals;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Goal Planner: compiles any target (item, quest, capstone) into a DAG of
 * Requirement nodes against AccountState, producing an ordered, live-updating
 * plan of only what's missing. See DESIGN.md §3.13.
 */
@Slf4j
@Singleton
public class GoalPlannerModule implements IronHubModule
{
	@Inject
	public GoalPlannerModule()
	{
	}

	@Override
	public String name()
	{
		return "Goal planner";
	}

	@Override
	public void startUp()
	{
		// TODO: requirement graph resolver, goal DAG compiler (topological sort,
		// cheap-first tie-breaking), active-goal dashboard strip, auto-completion
		// of steps on AccountState changes
	}

	@Override
	public void shutDown()
	{
	}
}
