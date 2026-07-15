package com.ironhub.requirements;

import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Factories, composites and the data-pack string form of {@link Requirement}.
 *
 * Data packs express requirements as compact strings:
 * {@code skill:<Skill>:<level>} · {@code quest:<name>} ·
 * {@code item:<itemId>[:qty]} · {@code unlock:<key>} ·
 * {@code kc:<source>:<count>}. Anything unparseable becomes a manual
 * (free-text) requirement that is never auto-met — the UI shows those as
 * "check manually" rather than guessing.
 */
public final class Requirements
{
	private Requirements()
	{
	}

	public static Requirement skill(Skill skill, int level)
	{
		return new SkillRequirement(skill, level, false);
	}

	/** Skill gate that temporary boosts can satisfy (actions, not equipping). */
	public static Requirement skillBoostable(Skill skill, int level)
	{
		return new SkillRequirement(skill, level, true);
	}

	public static Requirement quest(Quest quest)
	{
		return new QuestRequirement(quest);
	}

	/** Met once the quest is started (in progress or finished). */
	public static Requirement questStarted(Quest quest)
	{
		return new QuestStartedRequirement(quest);
	}

	public static Requirement item(int itemId, int quantity)
	{
		return new ItemRequirement(itemId, quantity, false, null);
	}

	/** Exact-id ownership: tiers/imbues in the same variation group don't count. */
	public static Requirement itemExact(int itemId, int quantity)
	{
		return new ItemRequirement(itemId, quantity, true, null);
	}

	public static Requirement unlock(String key)
	{
		return new UnlockRequirement(key);
	}

	public static Requirement kc(String source, int count)
	{
		return new KcRequirement(source, count);
	}

	public static Requirement questPoints(int points)
	{
		return new QpRequirement(points);
	}

	public static Requirement text(String text)
	{
		return new TextRequirement(text);
	}

	public static Requirement allOf(Requirement... requirements)
	{
		return new AllOf(List.of(requirements));
	}

	public static Requirement anyOf(Requirement... requirements)
	{
		return new AnyOf(List.of(requirements));
	}

	/** True for free-text requirements that can only be checked manually. */
	public static boolean isManual(Requirement requirement)
	{
		return requirement instanceof TextRequirement;
	}

	/** Parse the data-pack string form; falls back to a manual text requirement. */
	public static Requirement parse(String s)
	{
		// alternative obtainment paths: any:<path>|<path>, leaves within a
		// path joined by & — e.g. glory: any:skill:Crafting:80|skill:Hunter:83
		if (s.toLowerCase().startsWith("any:"))
		{
			List<Requirement> paths = new java.util.ArrayList<>();
			for (String path : s.substring("any:".length()).split("\\|"))
			{
				List<Requirement> leaves = new java.util.ArrayList<>();
				for (String leaf : path.split("&"))
				{
					Requirement parsed = parse(leaf);
					if (isManual(parsed))
					{
						// a broken leaf would silently never be met inside the
						// composite — surface the whole string as manual instead
						return text(s);
					}
					leaves.add(parsed);
				}
				paths.add(leaves.size() == 1 ? leaves.get(0) : allOf(leaves.toArray(new Requirement[0])));
			}
			return anyOf(paths.toArray(new Requirement[0]));
		}
		String[] parts = s.split(":");
		try
		{
			switch (parts[0].toLowerCase())
			{
				case "skill":
					return skill(Skill.valueOf(parts[1].toUpperCase()), Integer.parseInt(parts[2]));
				case "skillb": // boostable: creation/activity gates, never equip gates
					return skillBoostable(Skill.valueOf(parts[1].toUpperCase()), Integer.parseInt(parts[2]));
				case "quest":
					Quest quest = questByName(s.substring("quest:".length()));
					return quest != null ? quest(quest) : text(s);
				case "queststarted": // partial-quest gates (fairy rings, diary tasks)
					Quest started = questByName(s.substring("queststarted:".length()));
					return started != null ? questStarted(started) : text(s);
				case "item": // item:<id>[:qty[:display name]]
					return new ItemRequirement(Integer.parseInt(parts[1]),
						parts.length > 2 ? Integer.parseInt(parts[2]) : 1, false, itemLabel(parts));
				case "itemx": // exact id only — tiered/imbued items where variants don't count
					return new ItemRequirement(Integer.parseInt(parts[1]),
						parts.length > 2 ? Integer.parseInt(parts[2]) : 1, true, itemLabel(parts));
				case "unlock":
					return unlock(parts[1]);
				case "kc":
					return kc(parts[1], Integer.parseInt(parts[2]));
				case "qp": // quest points (Dragon Slayer II, Barrows gloves gates)
					return questPoints(Integer.parseInt(parts[1]));
				default:
					return text(s);
			}
		}
		catch (RuntimeException e) // malformed entry — CI pack tests catch these
		{
			return text(s);
		}
	}

	/** Display name from item:<id>:<qty>:<name> (name may contain colons). */
	private static String itemLabel(String[] parts)
	{
		return parts.length > 3
			? String.join(":", java.util.Arrays.copyOfRange(parts, 3, parts.length))
			: null;
	}

	private static Quest questByName(String name)
	{
		// trailing period tolerated: wiki-sourced packs strip sentence-final
		// dots, which also strips it from names like "Another Slice of H.A.M."
		String wanted = stripTrailingDot(name.trim());
		return Arrays.stream(Quest.values())
			.filter(q -> stripTrailingDot(q.getName()).equalsIgnoreCase(wanted))
			.findFirst()
			.orElse(null);
	}

	private static String stripTrailingDot(String s)
	{
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	private static class SkillRequirement implements Requirement
	{
		private final Skill skill;
		private final int level;
		private final boolean boostable;

		SkillRequirement(Skill skill, int level, boolean boostable)
		{
			this.skill = skill;
			this.level = level;
			this.boostable = boostable;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.getRealLevel(skill) >= level;
		}

		@Override
		public boolean isMetWithBoosts(StateView state, java.util.Map<Skill, Integer> boosts)
		{
			int boost = boostable ? boosts.getOrDefault(skill, 0) : 0;
			return state.getRealLevel(skill) + boost >= level;
		}

		@Override
		public Skill boostableSkill()
		{
			return boostable ? skill : null;
		}

		@Override
		public String describe()
		{
			return level + " " + skill.getName();
		}
	}

	private static class QuestRequirement implements Requirement
	{
		private final Quest quest;

		QuestRequirement(Quest quest)
		{
			this.quest = quest;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.getQuestState(quest) == QuestState.FINISHED;
		}

		@Override
		public String describe()
		{
			return quest.getName();
		}
	}

	private static class QuestStartedRequirement implements Requirement
	{
		private final Quest quest;

		QuestStartedRequirement(Quest quest)
		{
			this.quest = quest;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.getQuestState(quest) != QuestState.NOT_STARTED;
		}

		@Override
		public String describe()
		{
			return "Started " + quest.getName();
		}
	}

	private static class ItemRequirement implements Requirement
	{
		private final int itemId;
		private final int quantity;
		private final boolean exact;
		private final String name; // display label from the pack, or null

		ItemRequirement(int itemId, int quantity, boolean exact, String name)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.exact = exact;
			this.name = name;
		}

		@Override
		public boolean isMet(StateView state)
		{
			// default: any variation counts (recoloured graceful, broken fire
			// cape); exact: the id itself (Ghommal's hilt 4, diary tier 4)
			return (exact ? state.ownedCount(itemId) : state.canonicalStock(itemId)) >= quantity;
		}

		@Override
		public String describe()
		{
			// data packs supply display names; the raw form is a fallback
			return (name != null ? name : "item " + itemId)
				+ (quantity > 1 ? " ×" + quantity : "");
		}
	}

	private static class UnlockRequirement implements Requirement
	{
		private final String key;

		UnlockRequirement(String key)
		{
			this.key = key;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.isUnlocked(key);
		}

		@Override
		public String describe()
		{
			return key;
		}
	}

	private static class KcRequirement implements Requirement
	{
		private final String source;
		private final int count;

		KcRequirement(String source, int count)
		{
			this.source = source;
			this.count = count;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.getKillCount(source) >= count;
		}

		@Override
		public String describe()
		{
			return count + "× " + source;
		}
	}

	private static class QpRequirement implements Requirement
	{
		private final int points;

		QpRequirement(int points)
		{
			this.points = points;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return state.getQuestPoints() >= points;
		}

		@Override
		public String describe()
		{
			return points + " quest points";
		}
	}

	private static class TextRequirement implements Requirement
	{
		private final String text;

		TextRequirement(String text)
		{
			this.text = text;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return false; // undetectable — never assume met; manual tick in the UI
		}

		@Override
		public String describe()
		{
			return text;
		}
	}

	private static class AllOf implements Requirement
	{
		private final List<Requirement> children;

		AllOf(List<Requirement> children)
		{
			this.children = children;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return children.stream().allMatch(c -> c.isMet(state));
		}

		@Override
		public boolean isMetWithBoosts(StateView state, java.util.Map<Skill, Integer> boosts)
		{
			return children.stream().allMatch(c -> c.isMetWithBoosts(state, boosts));
		}

		@Override
		public List<Requirement> missing(StateView state)
		{
			List<Requirement> missing = new ArrayList<>();
			for (Requirement child : children)
			{
				missing.addAll(child.missing(state));
			}
			return missing;
		}

		@Override
		public String describe()
		{
			return children.stream().map(Requirement::describe).collect(Collectors.joining(" + "));
		}
	}

	private static class AnyOf implements Requirement
	{
		private final List<Requirement> children;

		AnyOf(List<Requirement> children)
		{
			this.children = children;
		}

		@Override
		public boolean isMet(StateView state)
		{
			return children.stream().anyMatch(c -> c.isMet(state));
		}

		@Override
		public boolean isMetWithBoosts(StateView state, java.util.Map<Skill, Integer> boosts)
		{
			return children.stream().anyMatch(c -> c.isMetWithBoosts(state, boosts));
		}

		@Override
		public String describe()
		{
			return "one of: " + children.stream().map(Requirement::describe).collect(Collectors.joining(" / "));
		}
	}
}
