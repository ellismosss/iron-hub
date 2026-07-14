package com.ironhub.state;

import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;

/**
 * Single source of truth for account progression state.
 *
 * Ingests client events and exposes a normalized, module-friendly view of
 * skills, quest states, diary/CA varbits, bank contents and unlock flags.
 * Modules subscribe to this service rather than reading the client directly.
 */
@Slf4j
@Singleton
public class AccountState
{
	private final Client client;

	@Getter
	private final Map<Skill, Integer> realLevels = new EnumMap<>(Skill.class);

	@Inject
	public AccountState(Client client)
	{
		this.client = client;
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		// TODO: on LOGGED_IN, refresh full state snapshot; resolve RSProfile key
	}

	public void onStatChanged(StatChanged event)
	{
		realLevels.put(event.getSkill(), event.getLevel());
		// TODO: notify listeners (milestones, banked XP recompute, diary reqs)
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		// TODO: route to quest / diary / CA / farming / daily state maps
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK.getId())
		{
			// TODO: snapshot + diff bank contents, persist, notify listeners
		}
	}
}
