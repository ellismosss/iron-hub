package com.ironhub.modules.suggest;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * "What Now?" suggestion engine: ranks 3-5 activities by impact x urgency x fit
 * given time available, active goals, dailies, farming state, slayer task and
 * runway warnings. Every suggestion is explainable. See DESIGN.md §3.14.
 */
@Slf4j
@Singleton
public class WhatNowModule implements IronHubModule
{
	@Inject
	public WhatNowModule()
	{
	}

	@Override
	public String name()
	{
		return "What now?";
	}

	@Override
	public void startUp()
	{
		// TODO: candidate providers from other modules; scoring pipeline
		// (impact x urgency x fit); time-available selector on dashboard
	}

	@Override
	public void shutDown()
	{
	}
}
