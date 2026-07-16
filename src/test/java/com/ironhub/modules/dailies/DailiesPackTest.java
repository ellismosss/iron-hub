package com.ironhub.modules.dailies;

import com.google.gson.Gson;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of data/dailies.json (regenerate with tools/gen_dailies.py — never
 * hand-edit). Schema shape is covered by DataPackTest; this is the semantic
 * half: the parts a schema cannot see.
 */
public class DailiesPackTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final DailiesPack pack = new DataPack(new Gson()).load("dailies", DailiesPack.class);

	/**
	 * The mandatory pack check: an unparseable requirement silently becomes a
	 * manual gate that is never met, which would hide a daily forever.
	 */
	@Test
	public void everyRequirementParsesToARealGate()
	{
		for (DailiesPack.Daily daily : pack.dailies)
		{
			for (String req : daily.reqs)
			{
				assertNotManual(daily.id + " req", req);
			}
			for (DailiesPack.Tier tier : daily.tiers)
			{
				if (tier.req != null)
				{
					assertNotManual(daily.id + " tier", tier.req);
				}
			}
		}
	}

	/** An unparseable string falls through to a manual requirement, which is
	 *  never automatically met — the daily would be gone for good. */
	private void assertNotManual(String what, String req)
	{
		Requirement parsed = Requirements.parse(req);
		assertNotNull(parsed);
		assertFalse(what + " is an unparseable manual gate: " + req,
			Requirements.isManual(parsed));
	}

	@Test
	public void idsAndDetectionVarbitsAreUnique()
	{
		Set<String> ids = new HashSet<>();
		Set<Integer> varbits = new HashSet<>();
		for (DailiesPack.Daily daily : pack.dailies)
		{
			assertTrue("duplicate id " + daily.id, ids.add(daily.id));
			if (daily.detection.varbit > 0)
			{
				assertTrue("two events share varbit " + daily.detection.varbit,
					varbits.add(daily.detection.varbit));
			}
		}
		assertEquals("every event the wiki lists for an ironman", 10, ids.size());
	}

	/** "Best met tier wins" is only meaningful if the ladder climbs. */
	@Test
	public void tierQuantitiesIncrease()
	{
		for (DailiesPack.Daily daily : pack.dailies)
		{
			int previous = 0;
			for (DailiesPack.Tier tier : daily.tiers)
			{
				assertTrue(daily.id + ": tier quantities must increase",
					tier.qty > previous);
				previous = tier.qty;
			}
		}
	}

	/** Only a manual event may lack a claim flag; everything else reads one. */
	@Test
	public void everyNonManualEventHasADetectionVarbit()
	{
		int manual = 0;
		for (DailiesPack.Daily daily : pack.dailies)
		{
			if ("manual".equals(daily.detection.mode))
			{
				manual++;
				assertEquals(daily.id + " is manual and must claim no varbit",
					0, daily.detection.varbit);
			}
			else
			{
				assertTrue(daily.id + " needs a detection varbit", daily.detection.varbit > 0);
			}
		}
		assertEquals("only Miscellania has no claim flag in the game", 1, manual);
	}

	/**
	 * The claim varbits must stay identical to RuneLite's own Daily Task
	 * Indicator — if a client update renumbers one, this fails with the
	 * regenerate command rather than shipping a wrong id.
	 */
	@Test
	public void claimVarbitsMatchTheClientsOwnConstants()
	{
		assertVarbit("zaff_battlestaves", net.runelite.api.Varbits.DAILY_STAVES_COLLECTED);
		assertVarbit("flax_bowstring", net.runelite.api.Varbits.DAILY_FLAX_STATE);
		assertVarbit("cromperty_essence", net.runelite.api.Varbits.DAILY_ESSENCE_COLLECTED);
		assertVarbit("bert_sand", net.runelite.api.Varbits.DAILY_SAND_COLLECTED);
		assertVarbit("rantz_arrows", net.runelite.api.Varbits.DAILY_ARROWS_STATE);
		assertVarbit("robin_bonemeal", net.runelite.api.Varbits.DAILY_BONEMEAL_STATE);
		assertVarbit("thirus_dynamite", net.runelite.api.Varbits.DAILY_DYNAMITE_COLLECTED);
		assertVarbit("lundail_runes", net.runelite.api.Varbits.DAILY_RUNES_COLLECTED);
	}

	private void assertVarbit(String id, int expected)
	{
		DailiesPack.Daily daily = pack.daily(id);
		assertNotNull("pack is missing " + id, daily);
		assertEquals(id + ": varbit drifted from the client — rerun tools/gen_dailies.py",
			expected, daily.detection.varbit);
	}

	/**
	 * Tears of Guthix is the one event the game announces in chat rather than
	 * in a varbit. The wording is transcribed identically by two independent
	 * server emulators; it is matched as a substring, so it must stay free of
	 * colour tags and sentence punctuation that could drift.
	 */
	@Test
	public void tearsOfGuthixCarriesJunasReminderWording()
	{
		DailiesPack.Daily tears = pack.daily("tears_of_guthix");
		assertNotNull("the reminder is the only no-history signal there is",
			tears.detection.chat);
		assertEquals("eligible to drink from the Tears of Guthix", tears.detection.chat);
		assertFalse("must not carry colour tags — we match on the stripped text",
			tears.detection.chat.contains("<"));
		assertFalse("no trailing punctuation: it is matched as a substring",
			tears.detection.chat.endsWith("."));
	}

	/**
	 * The NMZ herb boxes are the one wiki daily the game blocks for ironmen
	 * (core literally checks IRONMAN == 0), and Iron Hub is an ironman plugin.
	 */
	@Test
	public void ironmanBlockedAndChargeOnlyEventsAreNotStops()
	{
		for (DailiesPack.Daily daily : pack.dailies)
		{
			String name = daily.name.toLowerCase(java.util.Locale.ROOT);
			assertFalse("herb boxes cannot be bought by an ironman", name.contains("herb box"));
			assertFalse("cape/ring charges are used where you stand, not routed to",
				name.contains("cape") || name.contains("explorer"));
		}
	}

	/**
	 * Two stops deliberately do NOT point at their NPC — see gen_dailies.py.
	 * Pinned here so a well-meaning "fix" to the NPC's own tile fails loudly.
	 */
	@Test
	public void awkwardRoutingTargetsStayDeliberate()
	{
		// Lundail stands in the Mage Arena bank, which cannot be walked to:
		// the lever is the only way in, and Shortest Path ships no transport
		// for it. The target is the surface lever instead.
		DailiesPack.Daily lundail = pack.daily("lundail_runes");
		assertEquals(0, lundail.point.plane);
		assertTrue("must stay on the surface, not the bank's y-band",
			lundail.point.y < 6400);

		// The Chasm of Tears is plane 2 — the wiki's map template omits the
		// game plane, and Shortest Path's own tunnel out of the Lumbridge
		// Swamp Caves lands on plane 2.
		assertEquals(2, pack.daily("tears_of_guthix").point.plane);

		// Advisor Ghrim is upstairs in Miscellania castle.
		assertEquals(1, pack.daily("miscellania").point.plane);
	}
}
