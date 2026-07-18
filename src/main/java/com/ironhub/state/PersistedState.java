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
	Set<Integer> alchExcluded = new HashSet<>(); // legacy key set (kept readable + written for old profiles)
	Map<Integer, Integer> alchExcludedAtQty = new HashMap<>(); // item id -> banked qty when excluded (1 = auto-returns once the player owns more)
	Map<String, Integer> bankSkillTargets = new HashMap<>(); // skill name -> bank-tab target level
	Map<String, Boolean> dailiesChoice = new HashMap<>(); // daily id -> included in the run (absent = the pack's default)
	Map<String, Map<Integer, Integer>> lootBySource = new HashMap<>(); // npc -> item id -> total qty
	Map<String, Map<Integer, Integer>> suppliesBySource = new HashMap<>(); // npc -> canonical item id -> consumed qty
	Map<String, Map<String, Integer>> savedLoadouts = new HashMap<>(); // activity -> equipment slot name -> item id
	Map<String, SavedSetup> savedSetups = new HashMap<>(); // activity -> full setup (gear + inventory + rune pouch)
	java.util.List<Long> herbRunsMs = new ArrayList<>(); // completed farm run durations
	Map<String, FarmRun> farmRuns = new HashMap<>(); // custom run name -> ordered stops
	Map<String, SavedSetup> farmRunSetups = new HashMap<>(); // run name -> saved gear+inventory
	Map<String, String> farmTeleportPrefs = new HashMap<>(); // location id -> preferred teleport id
	Map<String, Boolean> farmRunChoice = new HashMap<>(); // run name -> included in "start all" (absent = on)
	java.util.List<String> farmRunOrder = new ArrayList<>(); // the player's run-list order (unknown names ignored, new runs appended)

	/** A user-built farm run: an ordered list of farm-runs.json location
	 *  ids. Teleports are auto-picked from what the player owns at run
	 *  time, never stored. */
	public static class FarmRun
	{
		public java.util.List<String> locationIds = new ArrayList<>();
	}

	java.util.List<FarmRunRecord> farmRunLog = new ArrayList<>(); // completed runs, oldest first, capped

	/** One COMPLETED farm run's outcome: Farming xp attributed to each
	 *  stop-type bucket as the run advanced, and the grimy herbs picked up
	 *  (id -> count) — the raw material for "avg xp per tree run" and
	 *  "herb runs to the next Herblore level". */
	public static class FarmRunRecord
	{
		public long endMs;
		public String name;
		public long durationMs;
		public Map<String, Integer> xpByBucket = new HashMap<>();
		public Map<Integer, Integer> herbsByType = new HashMap<>();
	}
	java.util.List<SlayerTaskRecord> slayerRecords = new ArrayList<>(); // oldest first, capped; last may be active (end == 0)

	/** One slayer assignment's outcome. The record opens at assignment and
	 *  closes when the task ends: {@code completed} is set only when the
	 *  streak varbit was SEEN to increment — a task that vanished without
	 *  that (points skip, plugin off) stays honest as not-completed. */
	public static class SlayerTaskRecord
	{
		public String name = "";   // canonical catalog task name
		public String master = ""; // assigning master, "" unknown
		public int assigned;       // initial amount (0 unknown)
		public int killed;
		public int xpStart;        // Slayer xp at assignment (0 = not yet seen)
		public int xpGained;
		public long lootValue;     // GE value of drops from task targets
		public long start;         // epoch ms
		public long end;           // 0 while active
		public boolean completed;

		public SlayerTaskRecord copy()
		{
			SlayerTaskRecord c = new SlayerTaskRecord();
			c.name = name;
			c.master = master;
			c.assigned = assigned;
			c.killed = killed;
			c.xpStart = xpStart;
			c.xpGained = xpGained;
			c.lootValue = lootValue;
			c.start = start;
			c.end = end;
			c.completed = completed;
			return c;
		}
	}

	java.util.Set<String> pohBuilt = new java.util.HashSet<>();     // built POH tier ids (pack keys)

	Map<String, String> rumourPrefLocations = new HashMap<>(); // creature -> preferred location name
	java.util.List<RumourRecord> rumourRecords = new ArrayList<>(); // oldest first, capped; last may be active

	/** One assigned rumour's outcome. Opens on assignment, closes on the
	 *  next assignment or a hand-in — {@code caught} is the observed catch
	 *  count and {@code pieceFound} marks the rare-piece message. */
	public static class RumourRecord
	{
		public String rumourId = "";
		public String hunterId = "";
		public int caught;
		public boolean pieceFound;
		public long start;
		public long end;      // 0 while active

		public RumourRecord copy()
		{
			RumourRecord c = new RumourRecord();
			c.rumourId = rumourId;
			c.hunterId = hunterId;
			c.caught = caught;
			c.pieceFound = pieceFound;
			c.start = start;
			c.end = end;
			return c;
		}
	}

	java.util.Set<Integer> stashBuilt = new java.util.HashSet<>();  // built STASH object ids
	java.util.Set<Integer> stashFilled = new java.util.HashSet<>(); // filled STASH object ids

	/**
	 * The unified goal seed (Goals v2 G1): every synthetic goal family —
	 * ca / diary / clue / clog / custom, and families to come — persists
	 * this one finished shape, built at add time by {@link GoalSeeds} so
	 * it renders offline. Keyed by the FULL goal id ("ca:340").
	 */
	Map<String, GoalSeed> goalSeeds = new HashMap<>();

	public static class GoalSeed
	{
		public String id = "";      // full goal id, family + ":" + local id
		public String family = "";  // "ca" | "diary" | "clue" | "clog" | "custom" | ...
		public String name = "";
		public int iconItemId;      // 0 = no item sprite
		public java.util.List<SeedStep> steps = new ArrayList<>();
		public java.util.List<String> achieved = new ArrayList<>();
		public long addedAt;        // epoch ms; 0 = migrated (date unknown)

		public GoalSeed copy()
		{
			GoalSeed c = new GoalSeed();
			c.id = id;
			c.family = family;
			c.name = name;
			c.iconItemId = iconItemId;
			for (SeedStep step : steps)
			{
				SeedStep s = new SeedStep();
				s.label = step.label;
				s.requirement = step.requirement;
				c.steps.add(s);
			}
			c.achieved = new ArrayList<>(achieved);
			c.addedAt = addedAt;
			return c;
		}
	}

	public static class SeedStep
	{
		public String label = "";
		public String requirement = "";
	}

	// ── legacy per-family goal seeds — read once for migration into
	// goalSeeds (AccountState.activateProfile), never written again ──

	Map<String, ClueGoal> clueGoals = new HashMap<>(); // legacy: clue id -> seed

	public static class ClueGoal
	{
		public String text = "";
		public String tier = "";
		public java.util.List<String> reqs = new ArrayList<>();
	}

	Map<String, String> slayerNotes = new HashMap<>();          // task -> player note
	Map<String, String> slayerLocationPrefs = new HashMap<>();  // task -> preferred location name
	Map<String, java.util.List<String>> slayerBlockPrefs = new HashMap<>(); // master -> preferred block list
	Map<String, java.util.List<String>> slayerSkipPrefs = new HashMap<>();  // master -> always-skip list

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
	Map<String, ClogGoal> clogGoals = new HashMap<>(); // legacy: slot item id -> seed

	public static class ClogGoal
	{
		public String name;
		public String activity;
		public java.util.List<String> reqs = new ArrayList<>();
	}
	Set<String> selectedGoals = new HashSet<>();
	String activeGoal = "";
	Map<String, CaGoal> caGoals = new HashMap<>(); // legacy: CA task id -> seed

	public static class CaGoal
	{
		public String name;
		public String description;
		public String tier;
	}

	Map<String, DiaryGoal> diaryGoals = new HashMap<>(); // legacy: task slug -> seed
	Set<String> plannerPins = new HashSet<>();      // action ids pinned to the front
	Set<String> plannerSnoozes = new HashSet<>();   // action ids sunk to the end
	Set<String> plannerBans = new HashSet<>();      // methods.json ids never suggested
	Map<String, String> plannerPreferred = new HashMap<>(); // skill -> preferred method id
	double lastPlanHours;                            // known hours at last replan (session diffs)
	boolean plannerRouteChapters;                    // Route view: chapter headers (default flat order)
	Map<String, CustomGoal> customGoals = new HashMap<>(); // legacy: goal id -> seed

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
