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
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final net.runelite.client.config.ConfigManager configManager; // null in headless tests
	private GearTab tab;

	@Inject
	public GearProgressionModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.config.ConfigManager configManager)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
		this.configManager = configManager;
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
			tab = new GearTab(state,
				dataPack.load("gear-progression", com.ironhub.data.GearProgressionPack.class),
				itemManager, config.gearHideComplete(), hide ->
				{
					if (configManager != null)
					{
						configManager.setConfiguration(com.ironhub.IronHubConfig.GROUP, "gearHideComplete", hide);
					}
				});
		}
		return tab;
	}

	// ── chart ownership (pure; static for tests) ──────────────────────

	/**
	 * Names of every obtained chart entry: item detection (or the manual
	 * mark), plus everything implied by an obtained successor — owning an
	 * Ava's assembler proves the accumulator and attractor came first,
	 * a slayer helmet proves its black mask, treads prove the fed boots.
	 */
	public static java.util.Set<String> obtainedNames(
		com.ironhub.data.GearProgressionPack pack, AccountState state)
	{
		List<com.ironhub.data.GearProgressionPack.Item> items = new ArrayList<>();
		java.util.Set<String> obtained = new java.util.HashSet<>();
		for (com.ironhub.data.GearProgressionPack.Phase phase : pack.getPhases())
		{
			for (com.ironhub.data.GearProgressionPack.Group group : phase.getGroups())
			{
				for (com.ironhub.data.GearProgressionPack.Item item : group.getItems())
				{
					items.add(item);
					if (directlyObtained(item, state))
					{
						obtained.add(item.getName());
					}
				}
			}
		}
		// propagate implications to a fixpoint (chains: assembler → accumulator → attractor)
		boolean changed = true;
		while (changed)
		{
			changed = false;
			for (com.ironhub.data.GearProgressionPack.Item item : items)
			{
				if (obtained.contains(item.getName()) && item.getImplies() != null)
				{
					changed |= obtained.addAll(item.getImplies());
				}
			}
		}
		return obtained;
	}

	private static boolean directlyObtained(
		com.ironhub.data.GearProgressionPack.Item item, AccountState state)
	{
		if (state.isUnlocked(item.markKey()))
		{
			return true;
		}
		if (item.isManual())
		{
			return false;
		}
		return (item.isExact()
			? state.ownedCount(item.getItemId())
			: state.canonicalStock(item.getItemId())) > 0;
	}

	/**
	 * Tile state per rung: owned; else the FIRST unowned rung with met
	 * requirements is the next upgrade (accent); the rest are locked.
	 */
	public static List<GridTile.State> ladderStates(AccountState state, List<GearLaddersPack.Rung> ladder)
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
