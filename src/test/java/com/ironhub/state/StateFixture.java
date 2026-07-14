package com.ironhub.state;

import com.google.gson.Gson;
import java.io.File;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Test access to AccountState ingestion without a client — the mock-driven
 * seam promised by DESIGN.md §2.2. No RuneLite client is constructed.
 */
public final class StateFixture
{
	private StateFixture()
	{
	}

	public static AccountState state(File storageDir)
	{
		// Runnable::run = synchronous writes, so tests see files immediately
		return new AccountState(null, null, new ProfileStore(new Gson(), Runnable::run, storageDir));
	}

	public static ProfileStore store(File storageDir)
	{
		return new ProfileStore(new Gson(), Runnable::run, storageDir);
	}

	public static void stat(AccountState state, Skill skill, int level, int xp)
	{
		state.ingestStat(skill, level, xp);
	}

	public static void quest(AccountState state, Quest quest, QuestState questState)
	{
		state.ingestQuest(quest, questState);
	}

	public static void bank(AccountState state, Map<Integer, Integer> contents)
	{
		state.ingestBank(contents);
	}

	public static void inventory(AccountState state, Map<Integer, Integer> contents)
	{
		state.ingestInventory(contents);
	}

	public static void equipment(AccountState state, Map<Integer, Integer> contents)
	{
		state.ingestEquipment(contents);
	}

	public static void inventorySlots(AccountState state, int[] slots)
	{
		state.ingestInventorySlots(slots);
	}

	public static void equipmentSlots(AccountState state, int[] slots)
	{
		state.ingestEquipmentSlots(slots);
	}

	public static void profile(AccountState state, long hash)
	{
		state.activateProfile(hash);
	}

	public static void varbit(AccountState state, int varbitId, int value)
	{
		state.ingestVarbit(varbitId, value);
	}

	public static void varp(AccountState state, int varpId, int value)
	{
		state.ingestVarp(varpId, value);
	}

	public static void checkpointSupplies(AccountState state)
	{
		state.checkpointSupplies();
	}

	public static void itemNames(AccountState state, Map<Integer, String> names)
	{
		state.ingestItemNames(names);
	}
}
