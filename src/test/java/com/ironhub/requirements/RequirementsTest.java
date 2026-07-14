package com.ironhub.requirements;

import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequirementsTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private AccountState state;

	@Before
	public void setUp()
	{
		state = StateFixture.state(temp.getRoot());
	}

	@Test
	public void skillRequirement()
	{
		Requirement req = Requirements.skill(Skill.AGILITY, 70);
		StateFixture.stat(state, Skill.AGILITY, 69, 0);
		assertFalse(req.isMet(state));
		StateFixture.stat(state, Skill.AGILITY, 70, 0);
		assertTrue(req.isMet(state));
		assertEquals("70 Agility", req.describe());
	}

	@Test
	public void questRequirement()
	{
		Requirement req = Requirements.quest(Quest.TEARS_OF_GUTHIX);
		assertFalse(req.isMet(state)); // default NOT_STARTED
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.IN_PROGRESS);
		assertFalse(req.isMet(state));
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		assertTrue(req.isMet(state));
		assertEquals("Tears of Guthix", req.describe());
	}

	@Test
	public void itemRequirementCountsAcrossContainers()
	{
		StateFixture.bank(state, Map.of(4151, 1));
		StateFixture.inventory(state, Map.of(4151, 1));
		StateFixture.equipment(state, Map.of(4151, 1));
		assertTrue(Requirements.item(4151, 3).isMet(state));
		assertFalse(Requirements.item(4151, 4).isMet(state));
	}

	@Test
	public void exactItemRequirementIgnoresTierVariants()
	{
		// Ghommal's hilt 2 (25928) and hilt 4 (25932) share a variation
		// group, but owning tier 2 must NOT satisfy a tier-4 requirement
		StateFixture.bank(state, Map.of(25928, 1));
		assertTrue(Requirements.parse("itemx:25928").isMet(state));
		assertFalse(Requirements.parse("itemx:25932").isMet(state));
		// the canonical form would (wrongly, for tiers) accept it — which is
		// exactly why tiered pack entries carry exact=true
		assertTrue(Requirements.parse("item:25932").isMet(state));
	}

	@Test
	public void itemRequirementCountsVariations()
	{
		// Arceuus-recoloured graceful hood (13579) satisfies base 11850,
		// and either id in the requirement matches either id owned
		StateFixture.equipment(state, Map.of(13579, 1));
		assertTrue(Requirements.item(11850, 1).isMet(state));
		assertTrue(Requirements.item(13579, 1).isMet(state));
		assertFalse(Requirements.item(11852, 1).isMet(state)); // cape group untouched
	}

	@Test
	public void unlockAndKcRequirements()
	{
		assertFalse(Requirements.unlock("fairy_rings").isMet(state));
		state.setUnlocked("fairy_rings", true);
		assertTrue(Requirements.unlock("fairy_rings").isMet(state));

		assertFalse(Requirements.kc("Zulrah", 50).isMet(state));
		state.setKillCount("Zulrah", 50);
		assertTrue(Requirements.kc("Zulrah", 50).isMet(state));
	}

	@Test
	public void parseRoundTrip()
	{
		StateFixture.stat(state, Skill.FARMING, 65, 0);
		Requirement skill = Requirements.parse("skill:Farming:65");
		assertFalse(Requirements.isManual(skill));
		assertTrue(skill.isMet(state));

		Requirement quest = Requirements.parse("quest:Tears of Guthix");
		assertFalse(Requirements.isManual(quest));
		assertEquals("Tears of Guthix", quest.describe());

		Requirement item = Requirements.parse("item:4151:2");
		assertFalse(Requirements.isManual(item));
		assertFalse(item.isMet(state));
	}

	@Test
	public void unparseableFallsBackToManual()
	{
		Requirement freeText = Requirements.parse("100k XP or 1 QP since last visit");
		assertTrue(Requirements.isManual(freeText));
		assertFalse(freeText.isMet(state)); // never auto-met
		assertEquals("100k XP or 1 QP since last visit", freeText.describe());

		assertTrue(Requirements.isManual(Requirements.parse("skill:NotASkill:10")));
		assertTrue(Requirements.isManual(Requirements.parse("quest:Not A Real Quest")));
	}

	@Test
	public void compositesAndMissingLeaves()
	{
		StateFixture.stat(state, Skill.AGILITY, 70, 0);
		Requirement met = Requirements.skill(Skill.AGILITY, 70);
		Requirement unmetSkill = Requirements.skill(Skill.HERBLORE, 38);
		Requirement unmetQuest = Requirements.quest(Quest.SONG_OF_THE_ELVES);

		Requirement all = Requirements.allOf(met, unmetSkill, unmetQuest);
		assertFalse(all.isMet(state));
		assertEquals(2, all.missing(state).size()); // flattened unmet leaves
		assertEquals("38 Herblore", all.missing(state).get(0).describe());

		Requirement any = Requirements.anyOf(unmetSkill, met);
		assertTrue(any.isMet(state));
		assertTrue(any.missing(state).isEmpty());

		assertTrue(met.missing(state).isEmpty());
		assertEquals(1, unmetSkill.missing(state).size());
	}
}
