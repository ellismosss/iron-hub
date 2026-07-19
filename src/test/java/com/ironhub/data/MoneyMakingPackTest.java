package com.ironhub.data;

import com.google.gson.Gson;
import com.ironhub.requirements.Requirements;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Money making guide pack (money-making.json, tools/gen_money_making.py): it
 * loads, the ~526 methods carry sane fields, and EVERY hard req / recommend is
 * a real graph string (the pack's availability logic gates on these, so an
 * unparseable one would silently become a never-met manual gate).
 */
public class MoneyMakingPackTest
{
	private final MoneyMakingPack pack = new DataPack(new Gson()).load("money-making", MoneyMakingPack.class);

	@Test
	public void loadsWithCategorisedMethods()
	{
		assertTrue("too few methods", pack.methods.size() > 400);
		Set<String> cats = new HashSet<>();
		int recurring = 0;
		for (MoneyMakingPack.Method m : pack.methods)
		{
			assertFalse("blank id", m.id.trim().isEmpty());
			assertFalse("blank name", m.name.trim().isEmpty());
			assertNotNull("null reqs " + m.id, m.reqs);
			assertNotNull("null recommends " + m.id, m.recommends);
			cats.add(m.category);
			if (m.recurring)
			{
				recurring++;
			}
		}
		assertTrue("categories present", cats.containsAll(java.util.List.of(
			"Combat", "Skilling", "Processing", "Collecting", "Recurring")));
		assertTrue("some recurring methods", recurring > 5);
	}

	@Test
	public void everyRequirementIsARealGraphLeaf()
	{
		for (MoneyMakingPack.Method m : pack.methods)
		{
			for (String req : m.reqs)
			{
				assertFalse(m.id + " hard req is a manual (unparseable) gate: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
			for (String rec : m.recommends)
			{
				assertFalse(m.id + " recommend is a manual (unparseable) gate: " + rec,
					Requirements.isManual(Requirements.parse(rec)));
			}
		}
	}

	@Test
	public void theExampleMethodClassifiesReqsRight()
	{
		// Luke's example: Doom of Mokhaiotl — access quest is the hard gate,
		// combat skills + "for X" quests are recommendations
		MoneyMakingPack.Method doom = byId("killing-the-doom-of-mokhaiotl-delve-1-16");
		assertTrue("The Final Dawn is the hard gate", doom.reqs.contains("quest:The Final Dawn"));
		assertTrue("combat skills are soft", doom.recommends.contains("skill:Ranged:90"));

		// a skilling gate stays hard even when a higher level is "recommended"
		MoneyMakingPack.Method nats = byId("crafting-nature-runes-through-the-abyss");
		assertTrue("44 Runecraft is a hard gate", nats.reqs.contains("skill:Runecraft:44"));
	}

	private MoneyMakingPack.Method byId(String id)
	{
		return pack.methods.stream().filter(m -> id.equals(m.id)).findFirst()
			.orElseThrow(() -> new AssertionError("missing method " + id));
	}
}
