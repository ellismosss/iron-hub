package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.ironhub.data.ItemNameIndex;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Parser run against captured real wikitext (src/test/resources/wiki). */
public class WikiStrategyTest
{
	private final ItemNameIndex names = new ItemNameIndex(new Gson());

	private static String fixture(String file) throws Exception
	{
		try (InputStream in = WikiStrategyTest.class.getResourceAsStream("/wiki/" + file))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void nameIndexResolvesEquipmentNames()
	{
		assertEquals((Integer) 11865, names.idOf("Slayer helmet (i)"));
		assertEquals((Integer) 12002, names.idOf("Occult necklace"));
		assertEquals((Integer) 28307, names.idOf("Ultor ring")); // live id, not the beta
		assertEquals(null, names.idOf("Not a real item"));
	}

	@Test
	public void slayerTaskPageParses() throws Exception
	{
		List<WikiStrategy> strategies = WikiStrategy.parse(fixture("dust-devils.wikitext"), names);
		assertFalse(strategies.isEmpty());
		WikiStrategy bursting = strategies.get(0);
		assertEquals("Bursting/Barraging", bursting.name());

		// head1 = slayer helmet (i); occult tops the neck ranks
		assertEquals(11865, bursting.slots.get("head").get(0).itemId);
		assertEquals(12002, bursting.slots.get("neck").get(0).itemId);
		assertTrue(bursting.slots.containsKey("weapon"));
		// preference order preserved: rank-1 candidates come before rank-2
		List<WikiStrategy.Candidate> head = bursting.slots.get("head");
		assertEquals("Slayer helmet (i)", head.get(0).name);
	}

	@Test
	public void strategiesPageParsesTabberStyles() throws Exception
	{
		List<WikiStrategy> strategies = WikiStrategy.parse(fixture("zulrah-strategies.wikitext"), names);
		assertTrue(strategies.size() >= 2);
		assertEquals("Magic", strategies.get(0).name());
		// occult tops magic neck; the pic= override resolves the actual item
		assertEquals(12002, strategies.get(0).slots.get("neck").get(0).itemId);
		// second tab is the ranged setup
		assertTrue(strategies.stream().anyMatch(s -> s.name().toLowerCase().contains("ranged")));
	}
}
