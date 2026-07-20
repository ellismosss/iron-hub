package com.ironhub.engine;

import com.ironhub.data.MethodsPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * Duration models (ENGINE-DESIGN §5). Pure and static: everything takes a
 * StateView so costs are computed against projections. Unknown data is
 * NaN, never an invented number — callers render "time unknown".
 */
public final class CostModel
{
	private CostModel()
	{
	}

	static Requirement parsed(String req)
	{
		// Requirements.parse memoizes centrally (lock-free) since the
		// 2026-07-20 audit — the synchronized map here contended the
		// planner and suggester threads in their hottest loops
		return Requirements.parse(req);
	}

	/**
	 * Hours to train a skill to targetLevel: piecewise integration over the
	 * xp range, using the best *unlocked* method at each xp position (WOM
	 * threshold semantics + our requirement overlay). Banked xp is applied
	 * at the front. NaN when a stretch has no known-rate unlocked method.
	 */
	public static double trainHours(Skill skill, int targetLevel, StateView view,
		MethodsPack methods, long bankedXp)
	{
		return trainHours(skill, targetLevel, view, methods, bankedXp,
			java.util.Set.of(), java.util.Map.of());
	}

	/**
	 * Train cost honoring the player's taste: banned method ids never
	 * used; a preferred method id (per skill) wins any band it can serve.
	 */
	public static double trainHours(Skill skill, int targetLevel, StateView view,
		MethodsPack methods, long bankedXp,
		java.util.Set<String> bannedMethods, java.util.Map<String, String> preferredMethods)
	{
		long targetXp = Experience.getXpForLevel(Math.min(99, targetLevel));
		long startXp = Math.min(targetXp, currentXp(skill, view) + Math.max(0, bankedXp));
		if (startXp >= targetXp)
		{
			return 0;
		}
		// the player's own measured pace beats any curated rate — it IS the
		// truth about how they train this skill (2026-07-20 intelligence
		// arc; EWMA-recent, so it follows them up the bands)
		double personal = view.measuredRate(skill);
		if (personal > 0)
		{
			return (targetXp - startXp) / personal;
		}
		MethodsPack.SkillLadder ladder = methods == null ? null : methods.ladder(skill);
		if (ladder == null || ladder.methods == null || ladder.methods.isEmpty())
		{
			return Double.NaN;
		}
		// methods usable in this projection, sorted by threshold; daily
		// methods live on the background lane, never in active hours
		List<MethodsPack.Method> unlocked = new ArrayList<>();
		String preferred = preferredMethods.get(skill.getName());
		for (MethodsPack.Method method : ladder.methods)
		{
			if ("daily".equals(method.style) || bannedMethods.contains(method.id))
			{
				continue;
			}
			if (method.req == null || method.req.isEmpty() || parsed(method.req).isMet(view))
			{
				unlocked.add(method);
			}
		}
		unlocked.sort(Comparator.comparingInt(m -> m.startXp));
		if (unlocked.isEmpty())
		{
			return Double.NaN;
		}

		double hours = 0;
		long position = startXp;
		while (position < targetXp)
		{
			MethodsPack.Method best = bestAt(unlocked, position, preferred);
			if (best == null || best.rate <= 0)
			{
				return Double.NaN; // no sourced rate for this stretch
			}
			long segmentEnd = nextThreshold(unlocked, position, targetXp);
			hours += (segmentEnd - position) / (double) best.rate;
			position = segmentEnd;
		}
		return hours;
	}

	/** Best-rate method whose threshold is at or below this xp; a
	 * player-preferred method wins any band it can serve. */
	private static MethodsPack.Method bestAt(List<MethodsPack.Method> unlocked, long xp,
		String preferredId)
	{
		MethodsPack.Method best = null;
		for (MethodsPack.Method method : unlocked)
		{
			if (method.startXp > xp)
			{
				continue;
			}
			if (method.id.equals(preferredId))
			{
				return method;
			}
			if (best == null || method.rate > best.rate)
			{
				best = method;
			}
		}
		return best;
	}

	/** The method a fresh hour of training would use right now. */
	public static MethodsPack.Method currentMethod(Skill skill, StateView view,
		MethodsPack methods, java.util.Set<String> bannedMethods,
		java.util.Map<String, String> preferredMethods)
	{
		MethodsPack.SkillLadder ladder = methods == null ? null : methods.ladder(skill);
		if (ladder == null || ladder.methods == null)
		{
			return null;
		}
		List<MethodsPack.Method> unlocked = new ArrayList<>();
		String preferred = preferredMethods.get(skill.getName());
		for (MethodsPack.Method method : ladder.methods)
		{
			if ("daily".equals(method.style) || bannedMethods.contains(method.id))
			{
				continue;
			}
			if (method.req == null || method.req.isEmpty() || parsed(method.req).isMet(view))
			{
				unlocked.add(method);
			}
		}
		return bestAt(unlocked, currentXp(skill, view), preferred);
	}

	/** Global travel multiplier for quest-shaped actions in this projection. */
	public static double travelFactor(StateView view, com.ironhub.data.EffectsPack effects)
	{
		if (effects == null)
		{
			return 1.0;
		}
		double factor = effects.baseTravelFactor;
		for (com.ironhub.data.EffectsPack.Effect effect : effects.effects)
		{
			if (parsed(effect.active).isMet(view))
			{
				factor -= effect.travelDelta;
			}
		}
		return Math.max(effects.minTravelFactor, factor);
	}

	/** Credit cross-skill byproduct xp for training origin over an xp span. */
	public static void applyBonuses(Skill origin, long trainedXp, ProjectedState projection,
		MethodsPack methods)
	{
		MethodsPack.SkillLadder ladder = methods == null ? null : methods.ladder(origin);
		if (ladder == null || ladder.bonuses == null)
		{
			return;
		}
		for (MethodsPack.Bonus bonus : ladder.bonuses)
		{
			for (Skill skill : Skill.values())
			{
				if (skill.getName().equalsIgnoreCase(bonus.bonusSkill))
				{
					projection.addXp(skill, (long) (trainedXp * bonus.ratio));
				}
			}
		}
	}

	private static long nextThreshold(List<MethodsPack.Method> unlocked, long xp, long cap)
	{
		long next = cap;
		for (MethodsPack.Method method : unlocked)
		{
			if (method.startXp > xp && method.startXp < next)
			{
				next = method.startXp;
			}
		}
		return next;
	}

	private static long currentXp(Skill skill, StateView view)
	{
		// floor to the level's xp for pre-plugin accounts without xp history
		return Math.max(view.getXp(skill), Experience.getXpForLevel(view.getRealLevel(skill)));
	}

	/** Quest hours: pack minutes scaled by the projection's travel factor. */
	public static double questHours(int minutes, double travelFactor)
	{
		return minutes <= 0 ? Double.NaN : minutes / 60.0 * Math.max(0.5, travelFactor);
	}

	/** Hours for a kill-count target at a kills-per-hour pace. */
	public static double killHours(int kills, double killsPerHour)
	{
		return killsPerHour <= 0 || kills <= 0 ? (kills <= 0 ? 0 : Double.NaN)
			: kills / killsPerHour;
	}

	/** Expected kills until a 1-in-N drop lands. */
	public static double expectedKillsForDrop(double dropRate)
	{
		return dropRate <= 0 || dropRate > 1 ? Double.NaN : 1.0 / dropRate;
	}

	/** Kills by which the drop has landed with 90% confidence (geometric P90). */
	public static double unluckyKillsForDrop(double dropRate)
	{
		if (dropRate <= 0 || dropRate >= 1)
		{
			return Double.NaN;
		}
		return Math.ceil(Math.log(0.1) / Math.log(1.0 - dropRate));
	}

	// ── collection-log rates (G3): the shared geometric core ClogRanker
	// and the drop coster both call, keyed on MEAN completions (clog.json's
	// Item.attempts = 1/p), not probability — one convention, no reciprocal
	// double-count. ──

	/** Hours to accrue N completions at a completions-per-hour pace, plus a
	 *  fixed first-run overhead. NaN when either input is non-positive. */
	public static double completionsToHours(double completions, double perHour, double extraFirst)
	{
		return perHour <= 0 || completions <= 0 ? Double.NaN
			: completions / perHour + Math.max(0, extraFirst);
	}

	/** Expected hours for a drop of mean {@code attempts} completions. */
	public static double dropHours(double attempts, double perHour, double extraFirst)
	{
		return completionsToHours(attempts, perHour, extraFirst);
	}

	/** P90 ("unlucky") hours for a drop of mean {@code attempts} completions:
	 *  the geometric 90th-percentile completion count over the pace. A
	 *  guaranteed drop (attempts ≤ 1) has no spread — same as expected. */
	public static double dropSpreadHours(double attempts, double perHour, double extraFirst)
	{
		if (attempts <= 1)
		{
			return completionsToHours(attempts, perHour, extraFirst);
		}
		return completionsToHours(unluckyKillsForDrop(1.0 / attempts), perHour, extraFirst);
	}
}
