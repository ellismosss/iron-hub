package com.ironhub.modules.bank;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Bank snapshots + search, banked XP computation, and the best-in-bank
 * loadout solver with OSRS Wiki DPS calculator export. See DESIGN.md §3.6.
 */
@Slf4j
@Singleton
public class BankTrackerModule implements IronHubModule
{
	@Inject
	public BankTrackerModule()
	{
	}

	@Override
	public String name()
	{
		return "Bank tracker";
	}

	@Override
	public void startUp()
	{
		// TODO: subscribe to bank snapshots from AccountState; load banked-XP
		// action mappings from the data pack
	}

	@Override
	public void shutDown()
	{
	}
}
