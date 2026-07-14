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
class PersistedState
{
	Map<Integer, Integer> bank = new HashMap<>();
	long bankTimestamp;
	Map<Integer, String> itemNames = new HashMap<>();
	Set<String> unlocks = new HashSet<>();
	Map<String, Integer> killCounts = new HashMap<>();
	Map<String, Long> dailiesDoneAt = new HashMap<>(); // daily id -> epoch millis of manual tick
	Map<String, Map<Integer, Integer>> lootBySource = new HashMap<>(); // npc -> item id -> total qty
	Map<String, Map<Integer, Integer>> suppliesBySource = new HashMap<>(); // npc -> canonical item id -> consumed qty
	java.util.List<Long> herbRunsMs = new ArrayList<>(); // completed herb run durations
	java.util.List<DeathRecord> deaths = new ArrayList<>(); // most recent last, capped

	Map<String, PatchSeen> herbPatchSeen = new HashMap<>(); // patch id -> last observed state
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
}
