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
	java.util.List<Long> herbRunsMs = new ArrayList<>(); // completed farm run durations
	Map<String, FarmRun> farmRuns = new HashMap<>(); // custom run name -> ordered stops
	Map<String, SavedSetup> farmRunSetups = new HashMap<>(); // run name -> saved gear+inventory

	/** A user-built farm run: an ordered list of farm-runs.json location
	 *  ids. Teleports are auto-picked from what the player owns at run
	 *  time, never stored. */
	public static class FarmRun
	{
		public java.util.List<String> locationIds = new ArrayList<>();
	}
	java.util.List<DeathRecord> deaths = new ArrayList<>(); // most recent last, capped

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
	Set<Integer> clogObtained = new HashSet<>(); // canonical log-slot item ids seen obtained
	Set<Integer> clogSkipped = new HashSet<>();  // activity indices hidden from the TTNS ranking
	int clogBaseline = -1;   // player's COLLECTION_COUNT at the last full sync (-1 = never synced)
	long clogSyncedMs;       // when the last full sync completed
	Map<String, ClogGoal> clogGoals = new HashMap<>(); // slot item id -> goal-planner seed

	/**
	 * A collection-log slot added to the goal planner. Display data and the
	 * source activity's requirement strings are snapshotted at add time;
	 * completion is proven by the {@code clogitem_<id>} unlock flag the
	 * collection-log module marks when the slot is seen obtained.
	 */
	public static class ClogGoal
	{
		public String name;
		public String activity;
		public java.util.List<String> reqs = new ArrayList<>();
	}
	Set<String> selectedGoals = new HashSet<>();
	String activeGoal = "";
	Map<String, CaGoal> caGoals = new HashMap<>(); // CA task id -> goal-planner seed

	/**
	 * A Combat Achievement task added to the goal planner. The catalog only
	 * exists while logged in, so the goal's display data is snapshotted at
	 * add time; completion is proven by the {@code catask_<id>} unlock flag
	 * the CA module marks when the live catalog shows the task done.
	 */
	public static class CaGoal
	{
		public String name;
		public String description;
		public String tier;
	}

	Map<String, DiaryGoal> diaryGoals = new HashMap<>(); // task slug -> goal-planner seed
	Set<String> plannerPins = new HashSet<>();      // action ids pinned to the front
	Set<String> plannerSnoozes = new HashSet<>();   // action ids sunk to the end
	Set<String> plannerBans = new HashSet<>();      // methods.json ids never suggested
	Map<String, String> plannerPreferred = new HashMap<>(); // skill -> preferred method id
	double lastPlanHours;                            // known hours at last replan (session diffs)
	boolean plannerRouteChapters;                    // Route view: chapter headers (default flat order)
	Map<String, CustomGoal> customGoals = new HashMap<>(); // goal id -> seed (add-goal skill targets)

	/** A user-typed goal from the add-goal search ("Agility 70"): one
	 * requirement string, achieved when it holds. */
	public static class CustomGoal
	{
		public String name;
		public String req;
	}

	/**
	 * An achievement diary task added to the goal planner, keyed by its
	 * completion-flag slug ("&lt;varp&gt;_&lt;bit&gt;" or "vb&lt;varbit&gt;").
	 * Display data is snapshotted at add time; completion is proven by the
	 * {@code diarytask_<slug>} unlock flag the diaries module marks once
	 * the flag (or the tier's own completion) shows the task done.
	 */
	public static class DiaryGoal
	{
		public String task;
		public String region;
		public String tier;
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
