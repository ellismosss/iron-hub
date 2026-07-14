package com.ironhub.modules.clues;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Clue & STASH helper: STASH built/filled tracking via varbits, emote-clue
 * readiness percentage against owned items, active-clue step support, and a
 * bank keep-list of clue-required items. See DESIGN.md §3.15.
 */
@Slf4j
@Singleton
public class ClueStashModule implements IronHubModule
{
	@Inject
	public ClueStashModule()
	{
	}

	@Override
	public String name()
	{
		return "Clues & STASH";
	}

	@Override
	public void startUp()
	{
		// TODO: STASH varbit map, emote clue item data pack, readiness calc,
		// keep-list flags in bank view
	}

	@Override
	public void shutDown()
	{
	}
}
