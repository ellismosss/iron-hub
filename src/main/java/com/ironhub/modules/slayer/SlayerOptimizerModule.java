package com.ironhub.modules.slayer;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Slayer optimizer: task tracking, point income/spend planning, block/skip
 * advisor ranked by time-cost, and per-task readiness (auto loadout, method,
 * supply expectations from own history). See DESIGN.md §3.16.
 */
@Slf4j
@Singleton
public class SlayerOptimizerModule implements IronHubModule
{
	@Inject
	public SlayerOptimizerModule()
	{
	}

	@Override
	public String name()
	{
		return "Slayer optimizer";
	}

	@Override
	public void startUp()
	{
		// TODO: assignment chat/varp parsing; point planner; block-list ranking
		// from data pack task rates; per-task loadout generation hook
	}

	@Override
	public void shutDown()
	{
	}
}
