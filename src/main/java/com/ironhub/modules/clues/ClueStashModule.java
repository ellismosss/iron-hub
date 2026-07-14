package com.ironhub.modules.clues;

import com.ironhub.IronHubConfig;
import com.ironhub.data.ClueItemsPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Clues & STASH (DESIGN.md §3.15): emote-clue readiness against owned
 * items via the requirement graph — clue completion on an iron is gated
 * on what you own, so this makes it visible. STASH built/filled tracking
 * is deferred: the API exposes no STASH varbit constants yet.
 */
@Slf4j
@Singleton
public class ClueStashModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private CluesTab tab;

	@Inject
	public ClueStashModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "Clues & STASH";
	}

	@Override
	public boolean enabled()
	{
		return config.clueStash();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new CluesTab(state, dataPack.load("clue-items", ClueItemsPack.class));
		}
		return tab;
	}

	static Requirement requirement(ClueItemsPack.Clue clue)
	{
		return Requirements.allOf(clue.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}

	static boolean doable(ClueItemsPack.Clue clue, AccountState state)
	{
		return requirement(clue).isMet(state);
	}

	/** "needs: <first missing item>" line, or null when doable. */
	static String blocking(ClueItemsPack.Clue clue, AccountState state)
	{
		List<Requirement> missing = requirement(clue).missing(state);
		return missing.isEmpty() ? null : missing.get(0).describe();
	}

	static long doableCount(List<ClueItemsPack.Clue> clues, AccountState state)
	{
		return clues.stream().filter(c -> doable(c, state)).count();
	}
}
