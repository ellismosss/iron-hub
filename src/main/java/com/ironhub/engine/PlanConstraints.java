package com.ironhub.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's standing instructions to the router (ENGINE-DESIGN §4:
 * the player outranks the engine). Action ids for step-level pins/snoozes/
 * bans; goal ids for the Goals-v2 goal-level priority + pins.
 */
public class PlanConstraints
{
	/** Do these STEPS as early as feasibility allows. */
	public final Set<String> pinned = new HashSet<>();
	/** Sink these STEPS to the end of the plan (not today). */
	public final Set<String> snoozed = new HashSet<>();
	/** Never spend hours on these training methods. */
	public final Set<String> bannedMethods = new HashSet<>();
	/** skill display name → methods.json id to use whenever possible. */
	public final Map<String, String> preferredMethods = new HashMap<>();

	// ── Goals v2 G5: goal-level priority + pins + per-route task order ──
	/** goal id → "high" | "normal" | "someday" (absent = normal). A step's
	 *  value is weighted by the MAX tier among the goals it serves, so a
	 *  someday goal's UNIQUE steps sink but its shared steps still count. */
	public final Map<String, String> goalPriority = new HashMap<>();
	/** Pinned GOALS in priority order (first = highest). A pinned goal's
	 *  steps outrank tiers and route in pin order. */
	public final List<String> pinnedGoals = new ArrayList<>();
	/** goal id → the player's manual task order (action id sequence). The
	 *  router honours it among feasible picks; stale ids are ignored. */
	public final Map<String, List<String>> routeTaskOrder = new HashMap<>();

	public static PlanConstraints none()
	{
		return new PlanConstraints();
	}
}
