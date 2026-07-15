package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.FarmRunsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locked patches must never enter a farm run: a fresh account's Herb run
 * skips the quest/diary/level-gated stops, and each unlocks as its
 * requirement is met.
 */
public class FarmRunGatingTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private FarmingRunModule module(AccountState state)
	{
		FarmingRunModule module = new FarmingRunModule(state, null, new EventBus(),
			null, null, null, new IronHubConfig()
		{
		}, null, new DataPack(new Gson()), null,
			TimetrackingFixture.configManager(), null, null, null, null, null);
		module.startUp();
		return module;
	}

	private Set<String> unlockedHerbIds(FarmingRunModule module)
	{
		return module.unlockedLocations(module.pack().category("herb")).stream()
			.map(l -> l.id).collect(Collectors.toSet());
	}

	@Test
	public void freshAccountSkipsEveryGatedHerbPatch()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		FarmingRunModule module = module(state);

		Set<String> unlocked = unlockedHerbIds(module);
		// no farming level, no quests, no diaries — only the ungated patches
		assertEquals(Set.of("herb/ardougne", "herb/catherby", "herb/falador", "herb/kourend"),
			unlocked);

		// and a started run holds exactly those stops
		module.startTemplate("Herb run");
		assertEquals(4, module.stops().size());
		assertFalse(module.stops().stream().anyMatch(s -> s.location.id.equals("herb/harmony-island")));
		module.shutDown();
	}

	@Test
	public void requirementsUnlockTheirPatches()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		FarmingRunModule module = module(state);

		StateFixture.stat(state, Skill.FARMING, 65, 5_346_332);
		assertTrue(unlockedHerbIds(module).contains("herb/farming-guild"));

		StateFixture.quest(state, Quest.MY_ARMS_BIG_ADVENTURE, QuestState.FINISHED);
		assertTrue(unlockedHerbIds(module).contains("herb/troll-stronghold"));

		StateFixture.quest(state, Quest.MAKING_FRIENDS_WITH_MY_ARM, QuestState.FINISHED);
		assertTrue(unlockedHerbIds(module).contains("herb/weiss"));

		StateFixture.quest(state, Quest.PRIEST_IN_PERIL, QuestState.IN_PROGRESS); // started is enough
		assertTrue(unlockedHerbIds(module).contains("herb/morytania"));

		StateFixture.quest(state, Quest.CHILDREN_OF_THE_SUN, QuestState.FINISHED);
		assertTrue(unlockedHerbIds(module).contains("herb/civitas-illa-fortis"));

		// Harmony Island needs the Elite Morytania diary (not granted) — still locked
		assertFalse(unlockedHerbIds(module).contains("herb/harmony-island"));
		module.shutDown();
	}

	@Test
	public void everyReqStringParsesToARealGate()
	{
		FarmRunsPack pack = new DataPack(new Gson()).load("farm-runs", FarmRunsPack.class);
		for (FarmRunsPack.Location loc : pack.locations)
		{
			for (String req : loc.reqs)
			{
				assertFalse(loc.id + " req is an unparseable manual gate: " + req,
					com.ironhub.requirements.Requirements.isManual(
						com.ironhub.requirements.Requirements.parse(req)));
			}
		}
	}
}
