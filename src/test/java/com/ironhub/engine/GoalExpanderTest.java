package com.ironhub.engine;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.EffectsPack;
import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoalExpanderTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static EnginePacks packs()
	{
		DataPack dataPack = new DataPack(new Gson());
		return new EnginePacks(
			dataPack.load("quests", QuestsPack.class),
			dataPack.load("methods", MethodsPack.class),
			dataPack.load("effects", EffectsPack.class),
			dataPack.load("gear-progression", GearProgressionPack.class),
			dataPack.load("boosts", com.ironhub.data.BoostsPack.class),
			dataPack.load("diaries", com.ironhub.data.DiariesPack.class));
	}

	private static GoalsPack.Goal goal(String id, String... reqs)
	{
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(id);
		goal.setName(id);
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (String req : reqs)
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(req);
			step.setRequirement(req);
			steps.add(step);
		}
		goal.setSteps(steps);
		return goal;
	}

	@Test
	public void mergesSharedDemandsAcrossGoals()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal bowfa = goal("bowfa", "quest:Song of the Elves");
		GoalsPack.Goal base70s = goal("base70s", "skill:Agility:70", "skill:Herblore:70");

		ActionDag dag = GoalExpander.expand(List.of(bowfa, base70s), state, packs());

		// SotE pulls its quest chain in through the pack
		Action sote = dag.get("quest:Song of the Elves");
		assertNotNull(sote);
		assertTrue(sote.dependsOn.contains("quest:Mourning's End Part II"));
		assertNotNull(dag.get("quest:Roving Elves")); // transitive chain
		// SotE's 70 Agility and base70s' 70 Agility are ONE node, tagged twice
		Action agility = dag.get("train:Agility:70");
		assertNotNull(agility);
		assertEquals(70, agility.trainToLevel);
		assertTrue(agility.neededBy.contains("bowfa"));
		assertTrue(agility.neededBy.contains("base70s"));
	}

	@Test
	public void trainLevelsChainInsteadOfOverMerging()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("a", "skill:Fishing:70"), goal("b", "skillb:Fishing:81")), state, packs());
		// a 70 demand must not wait on the 81 demand; 81 passes through 70
		assertNotNull(dag.get("train:Fishing:70"));
		assertTrue(dag.get("train:Fishing:81").dependsOn.contains("train:Fishing:70"));
	}

	@Test
	public void metDemandsExpandToNothing()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.AGILITY, 70, Experience.getXpForLevel(70));
		StateFixture.quest(state, Quest.SONG_OF_THE_ELVES, QuestState.FINISHED);

		ActionDag dag = GoalExpander.expand(List.of(
			goal("bowfa", "quest:Song of the Elves", "skill:Agility:70")), state, packs());
		assertNull(dag.get("quest:Song of the Elves"));
		assertNull(dag.get("train:Agility:70"));
		assertEquals(0, dag.size());
	}

	@Test
	public void questPointGatesFillFromTheRouteOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(goal("ds1", "qp:32")), state, packs());
		int qp = 0;
		int quests = 0;
		for (Action node : dag.nodes())
		{
			if (node.kind == Action.Kind.QUEST)
			{
				QuestsPack.QuestEntry entry = packs().quest(node.questName);
				qp += entry == null ? 0 : entry.qp;
				quests++;
			}
		}
		assertTrue("filled to " + qp + " qp", qp >= 32);
		assertTrue(quests > 3);
	}

	@Test
	public void manualStepsBecomeTickableNodesChainedInOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal bowfa = goal("bowfa",
			"quest:Song of the Elves", "150 crystal shards");
		ActionDag dag = GoalExpander.expand(List.of(bowfa), state, packs());

		Action manual = dag.get("manual:goalstep:bowfa:1");
		assertNotNull(manual);
		assertEquals("goalstep:bowfa:1", manual.unlockKey);
		// checklist order: the shard grind waits on SotE
		assertTrue(manual.dependsOn.contains("quest:Song of the Elves"));
	}

	@Test
	public void boostableGatesTrainOnlyToTheBoostedLevel()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 80, Experience.getXpForLevel(80));
		// crystal saw (+3, item-gated) + spicy stew (+5, Evil Dave quest)
		StateFixture.bank(state, java.util.Map.of(9625, 1));
		StateFixture.quest(state, Quest.RECIPE_FOR_DISASTER__EVIL_DAVE, QuestState.FINISHED);

		// the ornate jewellery box case: nobody trains to 91 with saw+stew
		ActionDag dag = GoalExpander.expand(List.of(
			goal("box", "skillb:Construction:91")), state, packs());
		assertNull(dag.get("train:Construction:91"));
		Action boosted = dag.get("train:Construction:83");
		assertNotNull("expected a train-to-83 node: 91 - saw(3) - stew(5)", boosted);
		assertTrue(boosted.name.contains("boost to 91"));

		// already at 83: nothing to train at all
		StateFixture.stat(state, Skill.CONSTRUCTION, 83, Experience.getXpForLevel(83));
		ActionDag met = GoalExpander.expand(List.of(
			goal("box", "skillb:Construction:91")), state, packs());
		assertEquals(0, met.size());

		// non-boostable demands are untouched by boosts
		ActionDag strict = GoalExpander.expand(List.of(
			goal("real", "skill:Construction:91")), state, packs());
		assertNotNull(strict.get("train:Construction:91"));
	}

	@Test
	public void ownedItemsNeverBecomeObtainSteps()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// holding an Arclight: a goal requiring it must not plan obtaining it
		StateFixture.equipment(state, java.util.Map.of(19675, 1));
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:19675")), state, packs());
		assertEquals(0, dag.size());
	}

	@Test
	public void diaryClaimsAreDetectedNotManual()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// unclaimed: a detected diary node (no tick key), not a manual one
		ActionDag dag = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		Action node = dag.get("diarytier:Lumbridge & Draynor:Elite");
		assertNotNull(node);
		assertNull(node.unlockKey);

		// claimed: nothing to plan
		StateFixture.varbit(state, net.runelite.api.Varbits.DIARY_LUMBRIDGE_ELITE, 1);
		ActionDag claimed = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		assertEquals(0, claimed.size());
	}

	@Test
	public void diaryTierGoalsCarryAggregatedRequirements()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		Action tier = dag.get("diarytier:Lumbridge & Draynor:Elite");
		assertNotNull(tier);
		// the tier's hardest demands gate it (81 Craft w/ QP cape path skipped,
		// but 76+ skills and quest chains must appear as dependencies)
		assertFalse("tier should depend on its skill demands", tier.dependsOn.isEmpty());
		boolean hasTrain = tier.dependsOn.stream().anyMatch(d -> d.startsWith("train:"));
		assertTrue("expected TRAIN dependencies, got: " + tier.dependsOn, hasTrain);
	}

	@Test
	public void dagIsAcyclicAndFullyOrderable()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack goals = new DataPack(new Gson()).load("goals", GoalsPack.class);
		ActionDag dag = GoalExpander.expand(goals.getGoals(), state, packs());
		assertTrue(dag.size() > 20);
		assertEquals("cycles cut: " + dag.degraded, 0, dag.degraded.size());
		assertEquals(dag.size(), dag.topological().size());
	}

	@Test
	public void anyPathsPickTheCheapestDeterministically()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// Herblore 70 (~700k xp) is far cheaper than Slayer 85 (~3.3M) —
		// with Druidic Ritual done the Herblore ladder costs honestly from
		// level 3 (before the 2026-07-23 wiki-rate merge this leaned on
		// quickCost's flat pessimism for the quest-gated floor, and faster
		// wiki Slayer tiers flipped the comparison)
		StateFixture.quest(state, Quest.DRUIDIC_RITUAL, QuestState.FINISHED);
		// the quest grants the xp to level 3 — the ladder's floor; without it
		// the 0->174xp stretch has no method and Herblore costs NaN
		StateFixture.stat(state, net.runelite.api.Skill.HERBLORE, 3,
			net.runelite.api.Experience.getXpForLevel(3));
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "any:skillb:Slayer:85|skillb:Herblore:70")), state, packs());
		assertNotNull(dag.get("train:Herblore:70"));
		assertNull(dag.get("train:Slayer:85"));
	}
}
