package com.ironhub.modules.dashboard;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountScoreTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final QolPack qolPack = new DataPack(new Gson()).load("qol", QolPack.class);

	@Test
	public void componentsBlendAndMissingDataIsSkipped()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.quest(state, Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_EASY, 1);
		state.recordCollectionLog(500, 1000);

		Map<String, Integer> components = AccountScore.components(state, qolPack);
		assertTrue(components.get("quests") >= 0);
		assertEquals(2, (int) components.get("diaries")); // 1/48 tiers
		assertEquals(-1, (int) components.get("CA"));      // thresholds unseen
		assertEquals(50, (int) components.get("log"));

		// CA excluded from the composite while it has no data
		int composite = AccountScore.composite(components);
		assertTrue(composite > 0 && composite <= 100);

		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_GRANDMASTER, 2525);
		StateFixture.varbit(state, VarbitID.CA_POINTS, 505);
		assertEquals(20, (int) AccountScore.components(state, qolPack).get("CA"));
	}

	@Test
	public void everyComponentRoutesToAModule()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		AccountScore.components(state, qolPack).keySet().forEach(key ->
			assertTrue(key, AccountScore.COMPONENT_MODULES.containsKey(key)));
	}

	@Test
	public void snapshotsAreDailyAndCapped()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertTrue(state.maybeSnapshotScore(10));
		assertFalse(state.maybeSnapshotScore(11)); // too soon
		assertEquals(1, state.getScoreSnapshots().size());
		assertEquals(10, state.getScoreSnapshots().get(0)[1]);
	}
}
