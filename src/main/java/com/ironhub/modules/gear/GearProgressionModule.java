package com.ironhub.modules.gear;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-slot gear upgrade ladders per combat style and activity context.
 * Items are marked Owned / Obtainable / Locked against AccountState, with
 * requirement trees and Shortest Path routing. See DESIGN.md §3.1.
 */
@Slf4j
@Singleton
public class GearProgressionModule implements IronHubModule
{
	@Inject
	public GearProgressionModule()
	{
	}

	@Override
	public String name()
	{
		return "Gear progression";
	}

	@Override
	public void startUp()
	{
		// TODO: load gear ladder data pack; register panel tab
	}

	@Override
	public void shutDown()
	{
	}
}
