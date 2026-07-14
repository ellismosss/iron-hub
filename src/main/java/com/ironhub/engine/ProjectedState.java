package com.ironhub.engine;

import com.ironhub.state.StateView;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemVariationMapping;

/**
 * A simulated future account: the live state plus the effects of plan
 * actions applied so far (ENGINE-DESIGN §3.1). Reads are base + overlay,
 * so constructing one never copies the bank. The requirement graph
 * evaluates against this exactly as against the live account.
 */
public class ProjectedState implements StateView
{
	private final StateView base;
	private final Map<Skill, Integer> xpOverride = new EnumMap<>(Skill.class);
	private final Set<Quest> questsDone = EnumSet.noneOf(Quest.class);
	private final Set<String> unlocksAdded = new HashSet<>();
	private final Map<Integer, Integer> itemsDelta = new HashMap<>(); // raw id → qty
	private final Map<String, Integer> kcDelta = new HashMap<>();

	public ProjectedState(StateView base)
	{
		this.base = base;
	}

	/** Deep copy — branch a candidate ordering without disturbing the trunk. */
	public ProjectedState branch()
	{
		ProjectedState copy = new ProjectedState(base);
		copy.xpOverride.putAll(xpOverride);
		copy.questsDone.addAll(questsDone);
		copy.unlocksAdded.addAll(unlocksAdded);
		copy.itemsDelta.putAll(itemsDelta);
		copy.kcDelta.putAll(kcDelta);
		return copy;
	}

	// ── effects (the mutation surface actions apply through) ──────────

	public void addXp(Skill skill, long amount)
	{
		int now = getXp(skill);
		xpOverride.put(skill, (int) Math.min(Experience.MAX_SKILL_XP, now + Math.max(0, amount)));
	}

	/** Raise xp to at least the given level's threshold (training targets). */
	public void reachLevel(Skill skill, int level)
	{
		int target = Experience.getXpForLevel(Math.min(99, Math.max(1, level)));
		if (getXp(skill) < target)
		{
			xpOverride.put(skill, target);
		}
	}

	public void completeQuest(Quest quest)
	{
		questsDone.add(quest);
	}

	public void addUnlock(String key)
	{
		unlocksAdded.add(key);
	}

	public void addItems(int itemId, int quantity)
	{
		itemsDelta.merge(itemId, quantity, Integer::sum);
	}

	public void addKillCount(String source, int count)
	{
		kcDelta.merge(source, count, Integer::sum);
	}

	// ── StateView (base + overlay) ─────────────────────────────────────

	@Override
	public int getRealLevel(Skill skill)
	{
		Integer projected = xpOverride.get(skill);
		return projected != null
			? Experience.getLevelForXp(projected)
			: base.getRealLevel(skill);
	}

	@Override
	public int getXp(Skill skill)
	{
		Integer projected = xpOverride.get(skill);
		if (projected != null)
		{
			return projected;
		}
		int baseXp = base.getXp(skill);
		// pre-plugin accounts may have level without xp history: floor to level
		return Math.max(baseXp, Experience.getXpForLevel(base.getRealLevel(skill)));
	}

	@Override
	public QuestState getQuestState(Quest quest)
	{
		return questsDone.contains(quest) ? QuestState.FINISHED : base.getQuestState(quest);
	}

	@Override
	public int ownedCount(int itemId)
	{
		return Math.max(0, base.ownedCount(itemId) + itemsDelta.getOrDefault(itemId, 0));
	}

	@Override
	public int canonicalStock(int itemId)
	{
		int group = ItemVariationMapping.map(itemId);
		int delta = 0;
		for (Map.Entry<Integer, Integer> entry : itemsDelta.entrySet())
		{
			if (ItemVariationMapping.map(entry.getKey()) == group)
			{
				delta += entry.getValue();
			}
		}
		return Math.max(0, base.canonicalStock(itemId) + delta);
	}

	@Override
	public boolean isUnlocked(String key)
	{
		return unlocksAdded.contains(key) || base.isUnlocked(key);
	}

	@Override
	public int getKillCount(String source)
	{
		return base.getKillCount(source) + kcDelta.getOrDefault(source, 0);
	}
}
