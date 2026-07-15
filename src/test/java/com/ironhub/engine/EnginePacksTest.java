package com.ironhub.engine;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.EffectsPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.requirements.Requirements;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Referential integrity for the engine's data packs (E2 exit criteria):
 * every requirement string parses into the graph (never silently manual),
 * every quest identity resolves, and rates stay inside Wise Old Man's
 * max-efficiency envelope.
 */
public class EnginePacksTest
{
	private final DataPack dataPack = new DataPack(new Gson());

	@Test
	public void everyMethodRequirementParsesAndSkillsResolve()
	{
		MethodsPack pack = dataPack.load("methods", MethodsPack.class);
		assertEquals(24, pack.skills.size());
		List<String> broken = new ArrayList<>();
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			// ladder skill name must resolve to the Skill enum
			assertNotNull("unknown skill: " + ladder.skill, skillByName(ladder.skill));
			for (MethodsPack.Method method : ladder.methods)
			{
				if (method.req != null && Requirements.isManual(Requirements.parse(method.req)))
				{
					broken.add(method.id + ": " + method.req);
				}
				assertTrue("method missing provenance: " + method.id,
					method.source != null && !method.source.isEmpty());
			}
			if (ladder.bonuses != null)
			{
				for (MethodsPack.Bonus bonus : ladder.bonuses)
				{
					assertNotNull(skillByName(bonus.originSkill));
					assertNotNull(skillByName(bonus.bonusSkill));
				}
			}
		}
		assertTrue("unparseable method reqs:\n" + String.join("\n", broken), broken.isEmpty());
	}

	@Test
	public void everyQuestResolvesAndReferencesStayInPack()
	{
		QuestsPack pack = dataPack.load("quests", QuestsPack.class);
		assertTrue(pack.quests.size() >= 150);
		Set<String> names = new HashSet<>();
		pack.quests.forEach(q -> names.add(q.name));
		List<String> broken = new ArrayList<>();
		for (QuestsPack.QuestEntry entry : pack.quests)
		{
			if (entry.enumName != null && questByEnumName(entry.enumName) == null)
			{
				broken.add("enum unresolved: " + entry.name);
			}
			for (String req : entry.reqs)
			{
				if (Requirements.isManual(Requirements.parse(req)))
				{
					broken.add(entry.name + ": manual req " + req);
				}
				if (req.startsWith("quest:") && !names.contains(req.substring(6)))
				{
					broken.add(entry.name + ": dangling " + req);
				}
			}
		}
		assertTrue(String.join("\n", broken), broken.isEmpty());
		// the pack's total quest points corroborate the parse (330+ in 2026)
		int totalQp = pack.quests.stream().mapToInt(q -> q.qp).sum();
		assertTrue("suspicious total QP: " + totalQp, totalQp >= 300 && totalQp <= 400);
	}

	@Test
	public void everyEffectRequirementParses()
	{
		EffectsPack pack = dataPack.load("effects", EffectsPack.class);
		assertTrue(pack.baseTravelFactor >= pack.minTravelFactor);
		double totalDelta = 0;
		for (EffectsPack.Effect effect : pack.effects)
		{
			assertTrue("manual effect req: " + effect.id,
				!Requirements.isManual(Requirements.parse(effect.active)));
			totalDelta += effect.travelDelta;
		}
		// all effects together may floor the factor but not invert the model
		assertTrue(pack.baseTravelFactor - totalDelta <= pack.minTravelFactor + 0.2);
	}

	@Test
	public void ratesStayInsideTheWomEnvelope()
	{
		// WOM ironman EHP rates are max-efficiency by definition; a curated
		// practical rate above ~1.2M xp/hr means a data error somewhere
		MethodsPack pack = dataPack.load("methods", MethodsPack.class);
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			for (MethodsPack.Method method : ladder.methods)
			{
				if (!"daily".equals(method.style))
				{
					assertTrue(method.id + " rate " + method.rate + " exceeds envelope",
						method.rate <= 1_200_000);
				}
			}
		}
	}

	private static Skill skillByName(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return skill;
			}
		}
		return null;
	}

	private static Quest questByEnumName(String name)
	{
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(name))
			{
				return quest;
			}
		}
		return null;
	}
}
