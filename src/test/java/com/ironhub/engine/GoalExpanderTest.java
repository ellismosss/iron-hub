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
			dataPack.load("gear-progression", GearProgressionPack.class));
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
		Action agility = dag.get("train:Agility");
		assertNotNull(agility);
		assertEquals(70, agility.trainToLevel);
		assertTrue(agility.neededBy.contains("bowfa"));
		assertTrue(agility.neededBy.contains("base70s"));
	}

	@Test
	public void trainNodesMergeToTheMaximumAskedLevel()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("a", "skill:Fishing:70"), goal("b", "skillb:Fishing:81")), state, packs());
		assertEquals(81, dag.get("train:Fishing").trainToLevel);
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
		assertNull(dag.get("train:Agility"));
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
		// Herblore 70 is far cheaper than Slayer 85 from scratch
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "any:skillb:Slayer:85|skillb:Herblore:70")), state, packs());
		assertNotNull(dag.get("train:Herblore"));
		assertNull(dag.get("train:Slayer"));
	}
}
