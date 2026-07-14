package com.ironhub.modules.dashboard;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Progress dashboard & account score: weighted composite completion score,
 * trend sparklines from periodic snapshots, at-a-glance strips, and a
 * shareable progress-post image export. See DESIGN.md §3.20.
 */
@Slf4j
@Singleton
public class DashboardModule implements IronHubModule
{
	@Inject
	public DashboardModule()
	{
	}

	@Override
	public String name()
	{
		return "Dashboard";
	}

	@Override
	public void startUp()
	{
		// TODO: score aggregation across modules (user-tunable weights);
		// weekly snapshot persistence; snapshot image renderer
	}

	@Override
	public void shutDown()
	{
	}
}
