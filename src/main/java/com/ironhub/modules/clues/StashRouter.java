package com.ironhub.modules.clues;

import com.ironhub.data.ClueStepsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;

/**
 * The STASH stocking router (Luke's 2026-07-28 goal): one tier at a time
 * — the first tier with unfilled units is the ACTIVE one — split its
 * unfilled units into a ready-to-fill ROUTE (outfit fully owned, ordered
 * nearest-neighbour from the player) and a WAITING list, and aggregate
 * everything the tier still needs: the missing outfit items across its
 * steps plus the build materials for its unbuilt units (per-tier recipe
 * verified against the wiki STASH page, 2026-07-28). Pure logic — the
 * tab renders it, {@link ClueStashModule} feeds it state.
 */
final class StashRouter
{
	static final String[] TIERS =
		{"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	/** Crossing planes on foot costs a staircase hunt — weigh it like a
	 *  detour so a same-plane unit slightly further wins the tie. */
	private static final int PLANE_PENALTY = 30;

	/** One tier's build recipe: Construction level, 2 planks of a kind,
	 *  10 nails of any one metal, gold leaves for Master. */
	static final class Build
	{
		final int level;
		final int plankId;
		final String plankName;
		final int goldLeaves;

		Build(int level, int plankId, String plankName, int goldLeaves)
		{
			this.level = level;
			this.plankId = plankId;
			this.plankName = plankName;
			this.goldLeaves = goldLeaves;
		}
	}

	static final Map<String, Build> BUILDS = new LinkedHashMap<>();

	static
	{
		BUILDS.put("Beginner", new Build(12, ItemID.WOODPLANK, "planks", 0));
		BUILDS.put("Easy", new Build(27, ItemID.WOODPLANK, "planks", 0));
		BUILDS.put("Medium", new Build(42, ItemID.PLANK_OAK, "oak planks", 0));
		BUILDS.put("Hard", new Build(55, ItemID.PLANK_TEAK, "teak planks", 0));
		BUILDS.put("Elite", new Build(77, ItemID.PLANK_MAHOGANY, "mahogany planks", 0));
		BUILDS.put("Master", new Build(88, ItemID.PLANK_MAHOGANY, "mahogany planks", 1));
	}

	static final int PLANKS_EACH = 2;
	static final int NAILS_EACH = 10;
	/** ItemID.NAILS is the steel one. */
	private static final int[] NAIL_TYPES = {ItemID.NAILS_BRONZE, ItemID.NAILS_IRON,
		ItemID.NAILS, ItemID.NAILS_BLACK, ItemID.NAILS_MITHRIL,
		ItemID.NAILS_ADAMANT, ItemID.NAILS_RUNE};
	private static final int[] HAMMERS = {ItemID.HAMMER, ItemID.IMCANDO_HAMMER,
		ItemID.IMCANDO_HAMMER_OFFHAND};
	private static final int[] SAWS = {ItemID.POH_SAW, ItemID.EYEGLO_CRYSTAL_SAW,
		ItemID.WEARABLE_SAW, ItemID.WEARABLE_SAW_OFFHAND};

	/** One unfilled unit of the active tier. */
	static final class Stop
	{
		ClueStepsPack.Stash unit;
		ClueStepsPack.Clue clue;      // null when the pack has no step for it
		boolean built;
		boolean ready;                // outfit fully owned (storages count)
		int distance = -1;            // tiles from the previous stop, -1 unknown
	}

	/** One requirement across the whole tier: every STASH keeps its own
	 *  copy, so three steps wanting a gold ring want THREE gold rings. */
	static final class Loadout
	{
		String label;   // the requirement's describe()
		int needed;     // unfilled steps asking for it
		int have;       // owned across its alternatives (storages count)

		boolean met()
		{
			return have >= needed;
		}
	}

	/** The active tier's marching orders. */
	static final class Plan
	{
		String tier;                  // null when every unit everywhere is filled
		int filled;
		int units;
		int unbuilt;
		final List<Stop> route = new ArrayList<>();    // ready, nearest-first
		final List<Stop> waiting = new ArrayList<>();  // missing outfit items
		final List<Loadout> loadout = new ArrayList<>();  // every item the tier needs
		final List<String> missing = new ArrayList<>();   // build shortfall lines
	}

	private StashRouter()
	{
	}

	/**
	 * Plan the CHOSEN tier (Luke, 2026-07-28: routing is per-tier and
	 * opt-in — no more first-incomplete auto-pick). {@code owning} answers
	 * outfit ownership (the storage-aware view); {@code state} answers
	 * built/filled marks and SPENDABLE stock for build materials;
	 * {@code from} is the player's position or null (route then starts at
	 * the tier's first unit). A complete tier returns an empty plan with
	 * its tally — the tab deactivates on it.
	 */
	static Plan plan(ClueStepsPack pack, AccountState state, StateView owning,
		WorldPoint from, String tier)
	{
		Plan plan = new Plan();
		plan.tier = tier;
		List<ClueStepsPack.Stash> units = new ArrayList<>();
		int filled = 0;
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			if (!tier.equals(unit.tier))
			{
				continue;
			}
			units.add(unit);
			if (state.isStashFilled(unit.objectId))
			{
				filled++;
			}
		}
		plan.filled = filled;
		plan.units = units.size();
		if (!units.isEmpty() && filled < units.size())
		{
			fill(plan, pack, units, state, owning, from);
		}
		return plan;
	}

	private static void fill(Plan plan, ClueStepsPack pack, List<ClueStepsPack.Stash> units,
		AccountState state, StateView owning, WorldPoint from)
	{
		List<Stop> ready = new ArrayList<>();
		for (ClueStepsPack.Stash unit : units)
		{
			if (state.isStashFilled(unit.objectId))
			{
				continue;
			}
			Stop stop = new Stop();
			stop.unit = unit;
			stop.clue = unit.clueId == null ? null : pack.clue(unit.clueId);
			stop.built = state.isStashBuilt(unit.objectId);
			stop.ready = stop.clue != null && !stop.clue.reqs.isEmpty()
				&& ClueStashModule.doable(stop.clue, owning);
			if (!stop.built)
			{
				plan.unbuilt++;
			}
			(stop.ready ? ready : plan.waiting).add(stop);
		}
		aggregateLoadout(plan, ready, owning);
		orderRoute(plan, ready, from);
		// distance shown is always FROM THE PLAYER — the NN chain's hop
		// lengths are ordering internals, not a readout
		if (from != null)
		{
			for (Stop stop : plan.route)
			{
				stop.distance = distance(from, stop.unit.worldPoint());
			}
		}
		aggregateMissing(plan, state);
	}

	/**
	 * The whole tier's shopping list (Luke, 2026-07-28): every requirement
	 * across the unfilled units, counted — filling a STASH keeps the
	 * outfit, so a shared item is needed once PER STEP — with how many the
	 * player owns across the requirement's alternatives.
	 */
	private static void aggregateLoadout(Plan plan, List<Stop> ready, StateView owning)
	{
		Map<String, Loadout> byReq = new LinkedHashMap<>();
		List<Stop> unfilled = new ArrayList<>(ready);
		unfilled.addAll(plan.waiting);
		for (Stop stop : unfilled)
		{
			if (stop.clue == null)
			{
				continue;
			}
			for (String raw : stop.clue.reqs)
			{
				Loadout entry = byReq.computeIfAbsent(raw, r ->
				{
					Loadout fresh = new Loadout();
					fresh.label = com.ironhub.requirements.Requirements.parse(r).describe();
					fresh.have = ownedFor(r, owning);
					return fresh;
				});
				entry.needed++;
			}
		}
		plan.loadout.addAll(byReq.values());
		// an A-Z list reads like the bank withdrawal it is (Luke, 2026-07-28)
		plan.loadout.sort(java.util.Comparator.comparing(l -> l.label,
			String.CASE_INSENSITIVE_ORDER));
	}

	/** Owned count across a raw requirement's alternatives — the pack's
	 *  outfit reqs are "item:id:qty:Name" or "any:item:...|item:...". */
	private static int ownedFor(String raw, StateView owning)
	{
		String body = raw.startsWith("any:") ? raw.substring(4) : raw;
		int total = 0;
		for (String alt : body.split("\\|"))
		{
			String[] parts = alt.split(":");
			if (parts.length >= 2 && "item".equals(parts[0]))
			{
				try
				{
					total += owning.canonicalStock(Integer.parseInt(parts[1]));
				}
				catch (NumberFormatException e)
				{
					// a malformed alt counts nothing
				}
			}
		}
		return total;
	}

	/** Greedy nearest-neighbour from the player: good enough for a
	 *  bank-and-hop circuit, no TSP theatrics. The tab FREEZES this order
	 *  per tier — moving never reshuffles a route mid-run. */
	private static void orderRoute(Plan plan, List<Stop> pool, WorldPoint from)
	{
		WorldPoint cursor = from;
		while (!pool.isEmpty())
		{
			Stop next = pool.get(0);
			if (cursor != null)
			{
				int best = Integer.MAX_VALUE;
				for (Stop stop : pool)
				{
					int d = distance(cursor, stop.unit.worldPoint());
					if (d < best)
					{
						best = d;
						next = stop;
					}
				}
			}
			pool.remove(next);
			plan.route.add(next);
			cursor = next.unit.worldPoint();
		}
	}

	private static int distance(WorldPoint a, WorldPoint b)
	{
		return a.distanceTo2D(b) + (a.getPlane() == b.getPlane() ? 0 : PLANE_PENALTY);
	}

	/** The build shortfalls for the unbuilt units, as lines — level,
	 *  planks, nails, gold leaves, hammer, saw. The outfit side lives in
	 *  {@link Plan#loadout} with counts. */
	private static void aggregateMissing(Plan plan, AccountState state)
	{
		if (plan.unbuilt == 0)
		{
			return;
		}
		Build build = BUILDS.get(plan.tier);
		if (state.getRealLevel(Skill.CONSTRUCTION) < build.level)
		{
			plan.missing.add("Level " + build.level + " Construction to build");
		}
		int planksNeeded = plan.unbuilt * PLANKS_EACH;
		int planksHave = state.canonicalStock(build.plankId);
		if (planksHave < planksNeeded)
		{
			plan.missing.add((planksNeeded - planksHave) + " " + build.plankName
				+ " to build (" + planksHave + "/" + planksNeeded + ")");
		}
		// ponytail: one build takes 10 nails of ONE metal — judge against the
		// single deepest stack; mixed part-stacks that only sum to 10 misread
		// as covered only in a case nobody stocks nails into
		int nailsNeeded = plan.unbuilt * NAILS_EACH;
		int nailsHave = 0;
		for (int nailId : NAIL_TYPES)
		{
			nailsHave = Math.max(nailsHave, state.canonicalStock(nailId));
		}
		if (nailsHave < nailsNeeded)
		{
			plan.missing.add((nailsNeeded - nailsHave) + " nails of one metal to build ("
				+ nailsHave + "/" + nailsNeeded + ")");
		}
		if (build.goldLeaves > 0)
		{
			int leavesNeeded = plan.unbuilt * build.goldLeaves;
			int leavesHave = state.canonicalStock(ItemID.GOLD_LEAF);
			if (leavesHave < leavesNeeded)
			{
				plan.missing.add((leavesNeeded - leavesHave) + " gold leaf to build ("
					+ leavesHave + "/" + leavesNeeded + ")");
			}
		}
		if (!ownsAny(state, HAMMERS))
		{
			plan.missing.add("A hammer to build");
		}
		if (!ownsAny(state, SAWS))
		{
			plan.missing.add("A saw to build");
		}
	}

	private static boolean ownsAny(AccountState state, int[] itemIds)
	{
		for (int itemId : itemIds)
		{
			if (state.canonicalStock(itemId) > 0)
			{
				return true;
			}
		}
		return false;
	}
}
