package com.ironhub.modules.dailies;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Reset-aware daily/weekly reminders (Zaff staves, Tears of Guthix,
 * Miscellania, Hespori, ...) with varbit/chat detection where possible.
 * See DESIGN.md §3.12.
 */
@Slf4j
@Singleton
public class DailiesModule implements IronHubModule
{
	@Inject
	public DailiesModule()
	{
	}

	@Override
	public String name()
	{
		return "Dailies";
	}

	@Override
	public void startUp()
	{
		// TODO: load dailies definitions from data pack; infobox with count outstanding
	}

	@Override
	public void shutDown()
	{
	}
}
