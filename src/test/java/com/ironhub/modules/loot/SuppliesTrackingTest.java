package com.ironhub.modules.loot;

import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Trip-based consumption diffing (DESIGN.md §3.9). */
public class SuppliesTrackingTest
{
	private static final int SHARK = 385;
	private static final int PRAYER_POTION_4 = 2434;
	private static final int PRAYER_POTION_3 = 139;
	private static final int SCALES = 12934;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void decreasesSinceCheckpointAttributeToTheKill()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(SHARK, 5));
		StateFixture.checkpointSupplies(state);

		StateFixture.inventory(state, Map.of(SHARK, 3)); // ate two
		state.ingestLoot("Zulrah", Map.of(SCALES, 100));

		assertEquals(2, (int) state.suppliesFor("Zulrah").get(SHARK));

		// next kill without eating adds nothing
		state.ingestLoot("Zulrah", Map.of(SCALES, 120));
		assertEquals(2, (int) state.suppliesFor("Zulrah").get(SHARK));
	}

	@Test
	public void potionSipsCancelViaVariationMapping()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(PRAYER_POTION_4, 1, SHARK, 1));
		StateFixture.checkpointSupplies(state);

		// a sip converts (4) -> (3): same canonical potion, no consumption
		StateFixture.inventory(state, Map.of(PRAYER_POTION_3, 1, SHARK, 1));
		state.ingestLoot("Zulrah", Map.of(SCALES, 100));
		assertTrue(state.suppliesFor("Zulrah").isEmpty()
			|| !state.suppliesFor("Zulrah").containsKey(PRAYER_POTION_4));
	}

	@Test
	public void bankInteractionResetsTheBaseline()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(SHARK, 20));
		StateFixture.checkpointSupplies(state);

		// deposit 15 sharks: bank event re-baselines, no consumption
		StateFixture.inventory(state, Map.of(SHARK, 5));
		StateFixture.bank(state, Map.of(SHARK, 15));

		state.ingestLoot("Zulrah", Map.of(SCALES, 100));
		assertTrue(state.suppliesFor("Zulrah").isEmpty());
	}
}
