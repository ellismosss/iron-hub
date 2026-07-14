package com.ironhub.modules.sync;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * External sync (all opt-in): WikiSync-compatible account sync (enables wiki
 * DPS calc import), Wise Old Man / TempleOSRS EHP context, and Discord
 * milestone webhooks with optional screenshots. See DESIGN.md §3.19.
 */
@Slf4j
@Singleton
public class ExternalSyncModule implements IronHubModule
{
	@Inject
	public ExternalSyncModule()
	{
	}

	@Override
	public String name()
	{
		return "External sync";
	}

	@Override
	public void startUp()
	{
		// TODO: WikiSync payload builder; WOM/Temple clients; webhook dispatcher
		// with milestone event subscriptions. All HTTP off client thread, opt-in.
	}

	@Override
	public void shutDown()
	{
	}
}
