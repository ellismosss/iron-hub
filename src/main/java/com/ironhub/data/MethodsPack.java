package com.ironhub.data;

import java.util.List;
import java.util.Locale;

/**
 * Training-method ladders (data/methods.json): per skill, piecewise
 * {startXp, rate} method tiers in Wise Old Man's community-standard shape
 * (ENGINE-DESIGN §5, Appendix C §1), overlaid with requirement strings —
 * the layer WOM deliberately lacks — plus cross-skill bonus-xp edges.
 * Rates carry provenance; a rate of 0 means "unknown" and the cost model
 * reports NaN rather than inventing a number.
 */
public class MethodsPack
{
	public String source;
	public String generated;
	public List<SkillLadder> skills;

	/** Meta-freshness after which the pack renders a staleness note; the
	 *  quarterly-ish regeneration cadence agreed for Goals v2. */
	public static final int STALE_AFTER_MONTHS = 4;

	/** "meta: Jul 2026" from the pack's generated date, or "" when unparseable. */
	public String metaLabel()
	{
		java.time.LocalDate date = generatedDate();
		if (date == null)
		{
			return "";
		}
		return "meta: " + date.getMonth().getDisplayName(
			java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + date.getYear();
	}

	/** Whether the pack is older than the staleness horizon at the given
	 *  instant (display-only; the caller supplies now for testability). */
	public boolean isStale(long nowMs)
	{
		java.time.LocalDate date = generatedDate();
		if (date == null)
		{
			return false;
		}
		java.time.LocalDate now = java.time.Instant.ofEpochMilli(nowMs)
			.atZone(java.time.ZoneOffset.UTC).toLocalDate();
		return date.plusMonths(STALE_AFTER_MONTHS).isBefore(now);
	}

	private java.time.LocalDate generatedDate()
	{
		if (generated == null || generated.isEmpty())
		{
			return null;
		}
		try
		{
			return java.time.LocalDate.parse(generated);
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
	}

	public SkillLadder ladder(net.runelite.api.Skill skill)
	{
		String wanted = skill.getName().toLowerCase(Locale.ROOT);
		return skills.stream()
			.filter(l -> l.skill.toLowerCase(Locale.ROOT).equals(wanted))
			.findFirst()
			.orElse(null);
	}

	public static class SkillLadder
	{
		public String skill;
		public List<Method> methods;
		public List<Bonus> bonuses;
	}

	public static class Method
	{
		public String id;
		public String name;
		/** Method applies from this xp (WOM threshold semantics). */
		public int startXp;
		/** XP per hour; 0 = unknown (never invent). */
		public int rate;
		/** Requirement-graph string gating the method, or null. */
		public String req;
		/** active | semi | afk | daily — the taste/budget filter axis. */
		public String style;
		/** Provenance (wiki page / WOM config), mandatory. */
		public String source;
		/** XP per action, when the method consumes materials (0 = n/a). */
		public double xpEach;
		/** Materials consumed per action (null when none/unknown). */
		public List<Input> inputs;
	}

	/** One consumable a method eats per action. */
	public static class Input
	{
		public int itemId;
		public int qty;
		public String name;
	}

	/** Byproduct xp: training origin pays bonusSkill at ratio (WOM bonuses). */
	public static class Bonus
	{
		public String originSkill;
		public String bonusSkill;
		public int startXp;
		public int endXp;
		public double ratio;
	}
}
