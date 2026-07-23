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
import static org.junit.Assert.assertNull;
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
	public void gapMeasuresDistanceToMet()
	{
		StateFixture.stat(state, Skill.AGILITY, 65, 0);
		// skill leaves count level gaps; met = 0
		assertEquals(5, Requirements.parse("skill:Agility:70").gap(state), 1e-9);
		assertEquals(0, Requirements.parse("skill:Agility:60").gap(state), 1e-9);
		// quests grade: in progress is closer than unstarted
		Requirement quest = Requirements.parse("quest:Dragon Slayer I");
		double unstarted = quest.gap(state);
		StateFixture.quest(state, net.runelite.api.Quest.DRAGON_SLAYER_I,
			net.runelite.api.QuestState.IN_PROGRESS);
		double inProgress = quest.gap(state);
		StateFixture.quest(state, net.runelite.api.Quest.DRAGON_SLAYER_I,
			net.runelite.api.QuestState.FINISHED);
		assertTrue(unstarted > inProgress);
		assertEquals(0, quest.gap(state), 1e-9);
		// anyOf takes the cheapest path; the string form composes
		StateFixture.stat(state, Skill.HUNTER, 80, 0);
		assertEquals(15, Requirements.parse(
			"any:skill:Crafting:80|skill:Hunter:83&skill:Agility:77").gap(state), 1e-9);
		// unparseable manual requirements stay a flat chunk, never 0
		assertTrue(Requirements.parse("some manual thing").gap(state) > 0);
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
	public void combatRequirementDerivesFromTheSevenSkills()
	{
		Requirement req = Requirements.parse("combat:40");
		assertFalse(Requirements.isManual(req));
		assertFalse(req.isMet(state)); // fresh account = combat 3
		// Experience.getCombatLevel(60, 60, 60, 60, 1, 1, 43) = 60-ish melee build
		StateFixture.stat(state, Skill.ATTACK, 60, 0);
		StateFixture.stat(state, Skill.STRENGTH, 60, 0);
		StateFixture.stat(state, Skill.DEFENCE, 60, 0);
		StateFixture.stat(state, Skill.HITPOINTS, 60, 0);
		StateFixture.stat(state, Skill.PRAYER, 43, 0);
		int expected = net.runelite.api.Experience.getCombatLevel(60, 60, 60, 60, 1, 1, 43);
		assertTrue(expected >= 40);
		assertTrue(req.isMet(state));
		assertFalse(Requirements.parse("combat:" + (expected + 1)).isMet(state));
		assertEquals("Combat 40", req.describe());
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

	/** Item leaves expose their id — the join key where-from hovers use
	 *  against the item-sources pack; every other leaf answers null. */
	@Test
	public void itemLeavesExposeTheirIdForWhereFromJoins()
	{
		assertEquals((Integer) 4151, Requirements.parse("item:4151:1:Abyssal whip").itemId());
		assertEquals((Integer) 11832, Requirements.parse("itemx:11832").itemId());
		assertNull(Requirements.parse("skill:Agility:70").itemId());
		assertNull(Requirements.parse("quest:Dragon Slayer").itemId());
	}

	@Test
	public void boostableGatesUseAvailableBoostsButEquipGatesNever()
	{
		com.ironhub.data.BoostsPack boostsPack = new com.ironhub.data.DataPack(new com.google.gson.Gson())
			.load("boosts", com.ironhub.data.BoostsPack.class);
		StateFixture.stat(state, Skill.CONSTRUCTION, 86, 0);

		// no boost sources available yet: 92 is out of reach, and the boost
		// source itself must be obtained — no wild pie access at 1 Cooking
		// means no Slayer headroom either
		java.util.Map<Skill, Integer> none = Boosts.available(boostsPack, state);
		Requirement nexus = Requirements.parse("skillb:Construction:92");
		assertFalse(nexus.isMetWithBoosts(state, none));
		assertEquals(null, none.get(Skill.SLAYER));

		// Theoatrix stack: crystal saw (+3 invisible) + POH tea (+3 visible) = 92 from 86
		StateFixture.bank(state, Map.of(9625, 1)); // crystal saw
		state.setUnlocked("gearmark_teak_shelves_2", true);
		java.util.Map<Skill, Integer> sawTea = Boosts.available(boostsPack, state);
		assertEquals(6, (int) sawTea.get(Skill.CONSTRUCTION));
		assertTrue(nexus.isMetWithBoosts(state, sawTea));

		// visible boosts don't stack: Evil Dave's +5 stew replaces the +3 tea,
		// it doesn't add to it (saw +3 invisible still stacks on top = +8)
		StateFixture.quest(state, net.runelite.api.Quest.RECIPE_FOR_DISASTER__EVIL_DAVE, QuestState.FINISHED);
		java.util.Map<Skill, Integer> sawStew = Boosts.available(boostsPack, state);
		assertEquals(8, (int) sawStew.get(Skill.CONSTRUCTION));

		// equip gates (plain skill:) never consult boosts
		Requirement equip = Requirements.parse("skill:Construction:92");
		assertFalse(equip.isMetWithBoosts(state, sawStew));

		// with Evil Dave done the stew now covers Slayer as well
		assertEquals(5, (int) sawStew.get(Skill.SLAYER));
	}

	@Test
	public void anyOfPathsExpressAlternativeObtainment()
	{
		// amulet of glory: craft it (80 Crafting) OR catch dragon implings (83 Hunter)
		Requirement glory = Requirements.parse("any:skill:Crafting:80|skill:Hunter:83");
		assertFalse(Requirements.isManual(glory));
		assertFalse(glory.isMet(state));

		StateFixture.stat(state, Skill.HUNTER, 83, 0);
		assertTrue(glory.isMet(state)); // one path suffices

		// paths can be multi-leaf (&-joined): both leaves of a path must hold
		Requirement multi = Requirements.parse("any:skill:Crafting:80&skill:Magic:68|skill:Hunter:90");
		assertFalse(multi.isMet(state)); // 83 Hunter < 90, crafting path unmet
		StateFixture.stat(state, Skill.CRAFTING, 80, 0);
		StateFixture.stat(state, Skill.MAGIC, 68, 0);
		assertTrue(multi.isMet(state));

		// a typo'd leaf poisons the whole string into a manual requirement
		assertTrue(Requirements.isManual(Requirements.parse("any:skill:Craftin:80|skill:Hunter:83")));
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
