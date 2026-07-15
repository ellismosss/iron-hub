package com.ironhub.state;

import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * The read surface the requirement graph evaluates against. AccountState
 * (the live account) and the goal engine's ProjectedState (a simulated
 * future account) both satisfy it, so requirements — and everything built
 * on them — can be checked against "the account as it will be after these
 * plan steps" exactly like against the account as it is now.
 */
public interface StateView
{
	/** Real (unboosted) level, 1 when unknown. */
	int getRealLevel(Skill skill);

	/** Real xp, 0 when unknown. */
	int getXp(Skill skill);

	QuestState getQuestState(Quest quest);

	/** Count owned of this exact item id across bank/inventory/equipment. */
	int ownedCount(int itemId);

	/** Count owned of any variant in the item's variation group. */
	int canonicalStock(int itemId);

	boolean isUnlocked(String key);

	/** Quest points from the last quest refresh. */
	int getQuestPoints();

	int getKillCount(String source);

	/** Last seen value of a watched varbit (0 until first refresh). */
	int getVarbit(int varbitId);
}
