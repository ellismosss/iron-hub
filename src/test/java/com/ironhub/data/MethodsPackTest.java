package com.ironhub.data;

import com.google.gson.Gson;
import com.ironhub.requirements.Requirements;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2 G4: methods.json invariants that must hold on every build (the
 * WOM max-efficiency envelope cross-check runs in the generator, where the
 * network is; these are the structural guarantees the router relies on) —
 * a level-1 floor method per ladder, every requirement string parses to a
 * real gate, and the freshness label reads honestly.
 */
public class MethodsPackTest
{
	private final MethodsPack pack = new DataPack(new Gson()).load("methods", MethodsPack.class);

	@Test
	public void everyLadderHasAFloorMethod()
	{
		// a ladder whose cheapest method starts above the skill's minimum
		// trainable band leaves that band uncosted and the plan stalls behind
		// NaN (RouterTest history). Every skill floors at level 1 EXCEPT
		// Herblore, which cannot be trained until Druidic Ritual lifts it to
		// level 3 — its floor method legitimately starts there.
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			int floor = "Herblore".equals(ladder.skill)
				? net.runelite.api.Experience.getXpForLevel(3) : 0;
			assertTrue(ladder.skill + " has no floor method at or below xp " + floor,
				ladder.methods.stream().anyMatch(m -> m.startXp <= floor));
		}
	}

	@Test
	public void everyRequirementParsesToARealGate()
	{
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			for (MethodsPack.Method method : ladder.methods)
			{
				if (method.req == null || method.req.isEmpty())
				{
					continue;
				}
				assertFalse(ladder.skill + "/" + method.id + " req is manual: " + method.req,
					Requirements.isManual(Requirements.parse(method.req)));
			}
		}
	}

	@Test
	public void ratesAndSourcesArePresent()
	{
		int methods = 0;
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			for (MethodsPack.Method method : ladder.methods)
			{
				assertTrue(method.id + " has no positive rate", method.rate > 0);
				assertTrue(method.id + " has no source", method.source != null && !method.source.isEmpty());
				methods++;
			}
		}
		assertTrue("suspiciously few methods: " + methods, methods >= 90);
		assertTrue("all 24 skills present", pack.skills.size() == 24);
	}

	@Test
	public void constructionLadderIsTheMetaNotSailingNiche()
	{
		// Luke, 2026-08-03: the planner suggested HULL PARTS for a
		// Construction grind — a Sailing shipwright niche the training
		// guide documents but never recommends. gen_methods.py excludes
		// those table sections; the curated seed carries the real meta,
		// and Mahogany Homes must ALWAYS be present (plank-efficient,
		// the ironman staple).
		MethodsPack.SkillLadder construction = pack.skills.stream()
			.filter(l -> "Construction".equals(l.skill)).findFirst().orElseThrow(AssertionError::new);
		for (MethodsPack.Method m : construction.methods)
		{
			assertFalse("Sailing niche leaked into the ladder: " + m.name,
				m.name.toLowerCase().contains("hull part")
					|| m.name.toLowerCase().contains("repair kit"));
		}
		assertTrue("Mahogany Homes missing from the ladder",
			construction.methods.stream().anyMatch(m -> m.name.startsWith("Mahogany Homes")));
		assertTrue("mahogany furniture meta missing",
			construction.methods.stream().anyMatch(m -> m.name.startsWith("Mahogany tables")));
		// parse artifacts are not method names (the Magic prose sentence,
		// the Firemaking template fragment — 2026-08-03 sweep)
		for (MethodsPack.SkillLadder ladder : pack.skills)
		{
			for (MethodsPack.Method m : ladder.methods)
			{
				assertFalse(ladder.skill + " method name is a parse artifact: " + m.name,
					m.name.contains("{") || m.name.contains("#") || m.name.split(" ").length > 7);
			}
		}
	}

	@Test
	public void freshnessLabelAndStaleness()
	{
		// the pack ships a parseable date and a readable meta label
		assertTrue("meta label empty", pack.metaLabel().startsWith("meta: "));

		MethodsPack fake = new MethodsPack();
		fake.generated = "2026-01-15";
		assertEquals("meta: Jan 2026", fake.metaLabel());
		// noon UTC anchors (never the wall clock) — 3 months on is fresh, 5 stale
		long march = java.time.LocalDate.parse("2026-04-15")
			.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
		long june = java.time.LocalDate.parse("2026-06-20")
			.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
		assertFalse("3 months is within the cadence", fake.isStale(march));
		assertTrue("5 months is stale", fake.isStale(june));

		fake.generated = "not a date";
		assertEquals("", fake.metaLabel());
		assertFalse(fake.isStale(june));
	}
}
