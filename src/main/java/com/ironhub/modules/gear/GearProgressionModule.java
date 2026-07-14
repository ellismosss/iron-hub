package com.ironhub.modules.gear;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.GearLaddersPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.GridTile;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-slot gear upgrade ladders (DESIGN.md §3.1, mockup frame 2a):
 * owned → next obtainable (accent) → locked, driven by item ownership
 * and the shared requirement graph over the gear-ladders data pack.
 */
@Slf4j
@Singleton
public class GearProgressionModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private GearTab tab;

	@Inject
	public GearProgressionModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "Gear progression";
	}

	@Override
	public boolean enabled()
	{
		return config.gearProgression();
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
			tab = new GearTab(state, dataPack.load("gear-ladders", GearLaddersPack.class));
		}
		return tab;
	}

	/**
	 * Tile state per rung: owned; else the FIRST unowned rung with met
	 * requirements is the next upgrade (accent); the rest are locked.
	 */
	static List<GridTile.State> ladderStates(AccountState state, List<GearLaddersPack.Rung> ladder)
	{
		List<GridTile.State> states = new ArrayList<>();
		boolean nextAssigned = false;
		for (GearLaddersPack.Rung rung : ladder)
		{
			if (state.ownedCount(rung.getItemId()) > 0)
			{
				states.add(GridTile.State.OWNED);
			}
			else if (!nextAssigned && requirement(rung).isMet(state))
			{
				states.add(GridTile.State.NEXT);
				nextAssigned = true;
			}
			else
			{
				states.add(GridTile.State.LOCKED);
			}
		}
		return states;
	}

	static Requirement requirement(GearLaddersPack.Rung rung)
	{
		return Requirements.allOf(rung.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}
}
