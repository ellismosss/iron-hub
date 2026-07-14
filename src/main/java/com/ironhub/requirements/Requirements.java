package com.ironhub.requirements;

import com.ironhub.state.AccountState;
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
		return new SkillRequirement(skill, level);
	}

	public static Requirement quest(Quest quest)
	{
		return new QuestRequirement(quest);
	}

	public static Requirement item(int itemId, int quantity)
	{
		return new ItemRequirement(itemId, quantity);
	}

	public static Requirement unlock(String key)
	{
		return new UnlockRequirement(key);
	}

	public static Requirement kc(String source, int count)
	{
		return new KcRequirement(source, count);
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
		String[] parts = s.split(":");
		try
		{
			switch (parts[0].toLowerCase())
			{
				case "skill":
					return skill(Skill.valueOf(parts[1].toUpperCase()), Integer.parseInt(parts[2]));
				case "quest":
					Quest quest = questByName(s.substring("quest:".length()));
					return quest != null ? quest(quest) : text(s);
				case "item":
					return item(Integer.parseInt(parts[1]),
						parts.length > 2 ? Integer.parseInt(parts[2]) : 1);
				case "unlock":
					return unlock(parts[1]);
				case "kc":
					return kc(parts[1], Integer.parseInt(parts[2]));
				default:
					return text(s);
			}
		}
		catch (RuntimeException e) // malformed entry — CI pack tests catch these
		{
			return text(s);
		}
	}

	private static Quest questByName(String name)
	{
		return Arrays.stream(Quest.values())
			.filter(q -> q.getName().equalsIgnoreCase(name.trim()))
			.findFirst()
			.orElse(null);
	}

	private static class SkillRequirement implements Requirement
	{
		private final Skill skill;
		private final int level;

		SkillRequirement(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}

		@Override
		public boolean isMet(AccountState state)
		{
			return state.getRealLevel(skill) >= level;
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
		public boolean isMet(AccountState state)
		{
			return state.getQuestState(quest) == QuestState.FINISHED;
		}

		@Override
		public String describe()
		{
			return quest.getName();
		}
	}

	private static class ItemRequirement implements Requirement
	{
		private final int itemId;
		private final int quantity;

		ItemRequirement(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}

		@Override
		public boolean isMet(AccountState state)
		{
			// any variation counts: recoloured graceful, broken fire cape, ...
			return state.canonicalStock(itemId) >= quantity;
		}

		@Override
		public String describe()
		{
			// data packs supply display names; the raw form is a fallback
			return "item " + itemId + (quantity > 1 ? " ×" + quantity : "");
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
		public boolean isMet(AccountState state)
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
		public boolean isMet(AccountState state)
		{
			return state.getKillCount(source) >= count;
		}

		@Override
		public String describe()
		{
			return count + "× " + source;
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
		public boolean isMet(AccountState state)
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
		public boolean isMet(AccountState state)
		{
			return children.stream().allMatch(c -> c.isMet(state));
		}

		@Override
		public List<Requirement> missing(AccountState state)
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
		public boolean isMet(AccountState state)
		{
			return children.stream().anyMatch(c -> c.isMet(state));
		}

		@Override
		public String describe()
		{
			return "one of: " + children.stream().map(Requirement::describe).collect(Collectors.joining(" / "));
		}
	}
}
