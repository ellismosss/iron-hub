package com.ironhub.modules.farming;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Farming patch state tracking, run timers with historical stats, route
 * ordering, ready notifications, and seed stock awareness. See DESIGN.md §3.8.
 */
@Slf4j
@Singleton
public class FarmingRunModule implements IronHubModule
{
	@Inject
	public FarmingRunModule()
	{
	}

	@Override
	public String name()
	{
		return "Farming runs";
	}

	@Override
	public void startUp()
	{
		// TODO: patch varbit map, run timer overlay, notification hooks
	}

	@Override
	public void shutDown()
	{
	}
}
