package com.ironhub.data;

import com.google.gson.Gson;
import com.ironhub.requirements.Requirements;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integrity of the universal item-sources projection (design/KB-RUNTIME.md):
 * every req parses non-manual, the id index answers the crown-jewel items,
 * and unknown items honestly return null.
 */
public class ItemSourcesPackTest
{
	private final ItemSourcesPack pack =
		new DataPack(new Gson()).load("item-sources", ItemSourcesPack.class);

	@Test
	public void everyReqParsesNonManual()
	{
		int reqd = 0;
		for (ItemSourcesPack.Entry e : pack.getItems())
		{
			if (e.getReqs() == null)
			{
				continue;
			}
			reqd++;
			assertNotNull(e.getName() + " has reqs but no origin", e.getReqsOrigin());
			for (String req : e.getReqs())
			{
				assertFalse(e.getName() + " req is manual: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
		}
		assertTrue("suspiciously few req'd items: " + reqd, reqd >= 1000);
	}

	@Test
	public void crownJewelsResolveById()
	{
		// Abyssal whip 4151: drop + the audited equip gate
		String whip = pack.sourceLine(4151);
		assertNotNull(whip);
		assertTrue(whip, whip.contains("Drop: Abyssal demon 1/512"));
		assertTrue(pack.reqs(4151).contains("skill:Attack:70"));
		// Rope 954: shop-buyable
		String rope = pack.sourceLine(954);
		assertNotNull(rope);
		assertTrue(rope, rope.contains("Buy:"));
		// Dragon warhammer 13576: the classic no-clog-rate OBTAIN — the whole
		// point of this pack is that this now answers
		String dwh = pack.sourceLine(13576);
		assertNotNull(dwh);
		assertTrue(dwh, dwh.contains("Lizardman shaman"));
	}

	@Test
	public void unknownStaysUnknown()
	{
		assertNull(pack.sourceLine(-1));
		assertNull(pack.sourceLine(0));
		assertNull(pack.reqs(-1));
		assertFalse(pack.reqsExtracted(-1));
	}

	@Test
	public void sourceLineCapsAtThree()
	{
		for (ItemSourcesPack.Entry e : pack.getItems())
		{
			String line = pack.sourceLine(e.getIds().get(0));
			if (line != null)
			{
				assertTrue(e.getName() + ": " + line,
					line.split(" · ").length <= 3);
			}
		}
	}

	/** The 2026-07-23 data-quality report (Luke's list) stays fixed. */
	@Test
	public void dataQualityFixesHold()
	{
		// Salve amulet(ei) 12018: real imbue recipes, never "(see recipe)"
		String salve = pack.sourceLine(12018);
		assertNotNull(salve);
		assertTrue(salve, salve.contains("Nightmare Zone points"));
		assertFalse(salve, salve.contains("see recipe"));
		// Amulet of glory 1704: jar is an Open (not a "reward"), recipe named
		String glory = pack.sourceLine(1704);
		assertTrue(glory, glory.contains("Open: Dragon impling jar"));
		assertTrue(glory, glory.contains("Dragonstone amulet"));
		// Imbued saradomin cape 21791: the miniquest, never the reclaim shop
		String cape = pack.sourceLine(21791);
		assertNotNull(cape);
		assertTrue(cape, cape.contains("Mage Arena II"));
		// Pharaoh's sceptre 9044: activity-contextualised chest, no diary
		// claim AND no Beneath Cursed Sands reward (only a teleport mention)
		String scep = pack.sourceLine(9044);
		assertTrue(scep, scep.contains("Pyramid Plunder"));
		assertFalse(scep, scep.contains("Diary"));
		assertFalse(scep, scep.contains("Beneath Cursed Sands"));
		// point/currency purchases carry a machine-readable balance to gate
		// on: Amy's saw 24880 = 500 Mahogany Homes points (unreadable, but
		// named), Herb sack 13226 = a readable slayer-point OR Tithe balance
		ItemSourcesPack.Entry amy = pack.entry(24880);
		assertNotNull(amy);
		assertNotNull("Amy's saw price carries a currency", amy.getSources().get(0).getCurrency());
		ItemSourcesPack.Entry herb = pack.entry(13226);
		boolean readable = herb.getSources().stream().anyMatch(s -> s.currencyReq() != null);
		assertTrue("herb sack has a readable point balance", readable);
		// make rows carry STRUCTURED materials, not just a sentence
		ItemSourcesPack.Entry ava = pack.entry(22109);
		boolean hasMats = ava.getSources().stream()
			.anyMatch(s -> s.getMaterials() != null && !s.getMaterials().isEmpty());
		assertTrue("Ava's assembler make row has structured materials", hasMats);
		// Seed box 13639 / Gem bag 12020: real point prices
		assertTrue(pack.sourceLine(13639), pack.sourceLine(13639).contains("250 Tithe Farm points"));
		assertTrue(pack.sourceLine(12020), pack.sourceLine(12020).contains("100 Golden nuggets"));
		// nothing anywhere credits a reclaim shop
		for (ItemSourcesPack.Entry e : pack.getItems())
		{
			for (ItemSourcesPack.Source s : e.getSources() == null
				? java.util.List.<ItemSourcesPack.Source>of() : e.getSources())
			{
				assertFalse(e.getName(), (s.getFrom() != null ? s.getFrom() : "").contains("Lost Property"));
			}
		}
	}

	@Test
	public void packShapeSane()
	{
		assertEquals(2, pack.getVersion());
		assertTrue(pack.getItems().size() >= 6000);
		int sourced = 0;
		for (ItemSourcesPack.Entry e : pack.getItems())
		{
			if (e.getSources() != null && !e.getSources().isEmpty())
			{
				sourced++;
			}
		}
		assertTrue("suspiciously few sourced items: " + sourced, sourced >= 6000);
	}
}
