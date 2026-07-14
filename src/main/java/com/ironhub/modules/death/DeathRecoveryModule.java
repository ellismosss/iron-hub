package com.ironhub.modules.death;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Death recovery helper: on player death, captures lost-item state from the
 * last container snapshot, grave location/timer, reclaim info and untradeable
 * handling, plus a recent-death history. See DESIGN.md §3.21.
 */
@Slf4j
@Singleton
public class DeathRecoveryModule implements IronHubModule
{
	@Inject
	public DeathRecoveryModule()
	{
	}

	@Override
	public String name()
	{
		return "Death recovery";
	}

	@Override
	public void startUp()
	{
		// TODO: ActorDeath handler + container diff capture; grave timer;
		// death history persistence; Path button to grave
	}

	@Override
	public void shutDown()
	{
	}
}
