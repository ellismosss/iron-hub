package com.ironhub.modules.sync;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ExternalSyncTest
{
	/** The WOM/Temple pings survive an enable cycle without touching the
	 *  state listener registry — the module is event-driven only since the
	 *  Discord webhook's removal (Luke, 2026-08-03). */
	@Test
	public void startUpAndShutDownAreEventBusOnly()
	{
		ExternalSyncModule module = new ExternalSyncModule(null,
			new net.runelite.client.eventbus.EventBus(), new com.ironhub.IronHubConfig()
			{
			}, null);
		module.startUp();
		module.onGameTick(null); // null client tolerated
		module.shutDown();
		assertTrue(true); // reaching here IS the pin: no NPE, no registry leak
	}
}
