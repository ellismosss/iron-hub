package com.ironhub.data;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The reverse unlock index: the packs' requirement strings joined into
 * "quest -> everything it gates" (2026-07-20 intelligence arc). Built
 * synchronously via the package seam; live callers get the lazy thread.
 */
public class UnlockIndexTest
{
	@Test
	public void joinsQuestGatesAcrossThePacks()
	{
		UnlockIndex index = new UnlockIndex(new DataPack(new Gson()));
		index.build();

		// a famous edge: Dragon Slayer II gates gear (Ava's assembler et al)
		List<UnlockIndex.Ref> ds2 = index.questUnlocks("Dragon Slayer II");
		assertTrue("DS2 must gate pack entries", !ds2.isEmpty());
		Set<String> sources = new HashSet<>();
		ds2.forEach(ref -> sources.add(ref.source));
		assertTrue("DS2 gates gear", sources.contains("gear item"));

		// trailing-dot tolerance (wiki-sourced names strip sentence dots)
		assertTrue(!index.questUnlocks("Dragon Slayer II.").isEmpty());

		// breadth: the join must cover many quests, not a handful — this is
		// what effects.json's 8 curated edges could never answer
		Set<String> quests = new HashSet<>();
		for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
		{
			if (!index.questUnlocks(quest.getName()).isEmpty())
			{
				quests.add(quest.getName());
			}
		}
		assertTrue("expected 20+ quests with unlock edges, got " + quests.size(),
			quests.size() >= 20);
	}
}
