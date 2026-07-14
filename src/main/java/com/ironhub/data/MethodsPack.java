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
