package com.ironhub.modules.farming;

import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class PatchPredictionTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private AccountState.HerbPatchSeen seen(AccountState state, String cropState, int stage)
	{
		state.recordHerbPatch("falador", cropState, "Ranarr", stage);
		return state.herbPatchSeen("falador");
	}

	@Test
	public void predictionFollowsGrowthClock()
	{
		AccountState state = StateFixture.state(temp.getRoot());

		assertEquals(FarmingRunModule.PatchView.UNKNOWN, FarmingRunModule.predict(null, 0));

		AccountState.HerbPatchSeen harvestable = seen(state, "HARVESTABLE", 0);
		assertEquals(FarmingRunModule.PatchView.READY,
			FarmingRunModule.predict(harvestable, harvestable.timeMs));

		// growing at stage 2 of 4: two 20-min stages left
		AccountState.HerbPatchSeen growing = seen(state, "GROWING", 2);
		long readyAt = FarmingRunModule.readyAtMs(growing);
		assertEquals(growing.timeMs + 40 * 60_000, readyAt);
		assertEquals(FarmingRunModule.PatchView.GROWING,
			FarmingRunModule.predict(growing, readyAt - 1));
		assertEquals(FarmingRunModule.PatchView.PREDICTED_READY,
			FarmingRunModule.predict(growing, readyAt));

		assertEquals(FarmingRunModule.PatchView.DISEASED,
			FarmingRunModule.predict(seen(state, "DISEASED", 1), 0));
		assertEquals(FarmingRunModule.PatchView.DEAD,
			FarmingRunModule.predict(seen(state, "DEAD", 1), 0));
		assertEquals(FarmingRunModule.PatchView.EMPTY,
			FarmingRunModule.predict(seen(state, "EMPTY", 0), 0));
	}

	@Test
	public void observedStatePersistsAcrossRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 8L);
		before.recordHerbPatch("falador", "GROWING", "Ranarr", 1);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 8L);
		AccountState.HerbPatchSeen seen = after.herbPatchSeen("falador");
		assertEquals("GROWING", seen.state);
		assertEquals("Ranarr", seen.herb);
		assertEquals(1, seen.stage);
	}
}
