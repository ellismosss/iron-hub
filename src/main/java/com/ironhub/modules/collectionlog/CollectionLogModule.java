package com.ironhub.modules.collectionlog;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Collection log & pets: log progress per activity, dry-streak and
 * expected-vs-actual rate stats, pet chance-seen-so-far, and cheapest
 * unfinished slot suggestions. See DESIGN.md §3.18.
 */
@Slf4j
@Singleton
public class CollectionLogModule implements IronHubModule
{
	@Inject
	public CollectionLogModule()
	{
	}

	@Override
	public String name()
	{
		return "Collection log";
	}

	@Override
	public void startUp()
	{
		// TODO: log state capture on interface open; KC sources from loot
		// tracker + chat; dry-streak math; slot suggestions via loadout solver
	}

	@Override
	public void shutDown()
	{
	}
}
