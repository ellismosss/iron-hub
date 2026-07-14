package com.ironhub.engine;

import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectedStateTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void overlaysProjectWithoutMutatingTheBase()
	{
		AccountState base = StateFixture.state(temp.getRoot());
		StateFixture.stat(base, Skill.AGILITY, 62, Experience.getXpForLevel(62));

		ProjectedState projected = new ProjectedState(base);
		projected.reachLevel(Skill.AGILITY, 70);
		projected.completeQuest(Quest.SONG_OF_THE_ELVES);
		projected.addUnlock("fairy_rings");
		projected.addKillCount("Zulrah", 50);

		assertEquals(70, projected.getRealLevel(Skill.AGILITY));
		assertEquals(QuestState.FINISHED, projected.getQuestState(Quest.SONG_OF_THE_ELVES));
		assertTrue(projected.isUnlocked("fairy_rings"));
		assertEquals(50, projected.getKillCount("Zulrah"));

		// the live account is untouched
		assertEquals(62, base.getRealLevel(Skill.AGILITY));
		assertEquals(QuestState.NOT_STARTED, base.getQuestState(Quest.SONG_OF_THE_ELVES));
		assertFalse(base.isUnlocked("fairy_rings"));

		// the requirement graph reads the projection transparently
		assertTrue(Requirements.parse("skill:Agility:70").isMet(projected));
		assertTrue(Requirements.parse("quest:Song of the Elves").isMet(projected));
		assertFalse(Requirements.parse("skill:Agility:70").isMet(base));
	}

	@Test
	public void itemDeltasCountCanonicallyLikeTheLiveState()
	{
		AccountState base = StateFixture.state(temp.getRoot());
		StateFixture.bank(base, Map.of(net.runelite.api.gameval.ItemID.GRACEFUL_HOOD, 1));

		ProjectedState projected = new ProjectedState(base);
		// a recoloured variant joins the same variation group
		projected.addItems(net.runelite.api.gameval.ItemID.ZEAH_GRACEFUL_HOOD_ARCEUUS, 1);

		assertEquals(2, projected.canonicalStock(net.runelite.api.gameval.ItemID.GRACEFUL_HOOD));
		assertEquals(1, base.canonicalStock(net.runelite.api.gameval.ItemID.GRACEFUL_HOOD));
		assertEquals(1, projected.ownedCount(net.runelite.api.gameval.ItemID.ZEAH_GRACEFUL_HOOD_ARCEUUS));
	}

	@Test
	public void branchIsolatesCandidateOrderings()
	{
		AccountState base = StateFixture.state(temp.getRoot());
		ProjectedState trunk = new ProjectedState(base);
		trunk.addUnlock("graceful");

		ProjectedState branch = trunk.branch();
		branch.addUnlock("fairy_rings");

		assertTrue(branch.isUnlocked("graceful"));
		assertTrue(branch.isUnlocked("fairy_rings"));
		assertFalse(trunk.isUnlocked("fairy_rings"));
	}

	@Test
	public void xpFloorsToLevelForPrePluginAccounts()
	{
		AccountState base = StateFixture.state(temp.getRoot());
		StateFixture.stat(base, Skill.MINING, 60, 0); // level known, xp history absent

		ProjectedState projected = new ProjectedState(base);
		assertEquals(Experience.getXpForLevel(60), projected.getXp(Skill.MINING));
		projected.addXp(Skill.MINING, 1000);
		assertEquals(Experience.getXpForLevel(60) + 1000, projected.getXp(Skill.MINING));
		assertEquals(60, projected.getRealLevel(Skill.MINING));
	}
}
