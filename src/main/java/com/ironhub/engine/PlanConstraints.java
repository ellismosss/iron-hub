package com.ironhub.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The player's standing instructions to the router (ENGINE-DESIGN §4:
 * the player outranks the engine). All ids are action ids except
 * bannedMethods/preferredMethods which use methods.json ids.
 */
public class PlanConstraints
{
	/** Do these as early as feasibility allows. */
	public final Set<String> pinned = new HashSet<>();
	/** Sink these to the end of the plan (not today). */
	public final Set<String> snoozed = new HashSet<>();
	/** Never spend hours on these training methods. */
	public final Set<String> bannedMethods = new HashSet<>();
	/** skill display name → methods.json id to use whenever possible. */
	public final Map<String, String> preferredMethods = new HashMap<>();

	public static PlanConstraints none()
	{
		return new PlanConstraints();
	}
}
