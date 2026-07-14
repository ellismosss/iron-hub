package com.ironhub.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The slice of AccountState that cannot be re-read from the client after a
 * restart (the bank is only readable while open; unlocks and kill counts
 * accumulate from events). Serialized as JSON per profile by ProfileStore.
 */
public class PersistedState
{
	Map<Integer, Integer> bank = new HashMap<>();
	long bankTimestamp;
	Map<Integer, String> itemNames = new HashMap<>();
	Set<String> unlocks = new HashSet<>();
	Map<String, Integer> killCounts = new HashMap<>();
	Map<String, Long> dailiesDoneAt = new HashMap<>(); // daily id -> epoch millis of manual tick
	Map<String, Map<Integer, Integer>> lootBySource = new HashMap<>(); // npc -> item id -> total qty
	Map<String, Map<Integer, Integer>> suppliesBySource = new HashMap<>(); // npc -> canonical item id -> consumed qty
	Map<String, Map<String, Integer>> savedLoadouts = new HashMap<>(); // activity -> equipment slot name -> item id
	Map<String, SavedSetup> savedSetups = new HashMap<>(); // activity -> full setup (gear + inventory + rune pouch)
	java.util.List<Long> herbRunsMs = new ArrayList<>(); // completed herb run durations
	java.util.List<DeathRecord> deaths = new ArrayList<>(); // most recent last, capped

	Map<String, PatchSeen> herbPatchSeen = new HashMap<>(); // patch id -> last observed state
	java.util.List<ConsumptionEvent> consumptionLog = new ArrayList<>(); // rolling, capped

	static class ConsumptionEvent
	{
		long timeMs;
		int itemId; // canonical
		int quantity;
	}

	java.util.List<ScoreSnapshot> scoreSnapshots = new ArrayList<>(); // periodic, capped

	static class ScoreSnapshot
	{
		long timeMs;
		int score;
	}

	int collectionLogSlots;
	int collectionLogTotal;
	long collectionLogSeenMs;
	Set<String> selectedGoals = new HashSet<>();
	String activeGoal = "";

	static class PatchSeen
	{
		String state;
		String herb;
		int stage;
		long timeMs;
	}

	static class DeathRecord
	{
		long timeMs;
		int x;
		int y;
		int plane;
		Map<Integer, Integer> carried = new HashMap<>();
	}

	/** A remembered activity setup: worn gear, inventory and rune pouch. */
	public static class SavedSetup
	{
		public Map<String, Integer> equipment = new HashMap<>(); // slot name -> item id
		public int[] inventory = new int[0];      // 28 slots, -1 empty
		public int[] inventoryQty = new int[0];   // parallel quantities
		public int[] pouchRunes = new int[0];     // rune item ids, -1 empty
		public int[] pouchAmounts = new int[0];
	}
}
