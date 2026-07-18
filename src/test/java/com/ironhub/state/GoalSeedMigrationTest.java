package com.ironhub.state;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Goals v2 G1: profiles written before the unified seed map carry five
 * per-family goal maps — activateProfile must rebuild them as GoalSeeds
 * byte-identically to what the old to&lt;X&gt;Goal builders rendered,
 * exactly once, without touching the player's selection.
 */
public class GoalSeedMigrationTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/** A pre-G1 profile, in the exact JSON shape the old code wrote. */
	private static final String LEGACY_JSON = "{"
		+ "\"selectedGoals\":[\"ca:340\",\"diary:1196_0\",\"clue:e1\",\"clog:13262\",\"custom:skill:agility:70\"],"
		+ "\"activeGoal\":\"ca:340\","
		+ "\"caGoals\":{\"340\":{\"name\":\"Noxious Foe\",\"description\":\"Kill an Aberrant Spectre.\",\"tier\":\"Easy\"}},"
		+ "\"diaryGoals\":{\"1196_0\":{\"task\":\"Enter the Essence Mine\",\"region\":\"Ardougne\",\"tier\":\"Easy\"}},"
		+ "\"clueGoals\":{\"e1\":{\"text\":\"Bow to Brugsen Bursen\",\"tier\":\"Beginner\",\"reqs\":[\"item:1205:1:Bronze dagger\"]}},"
		+ "\"clogGoals\":{\"13262\":{\"name\":\"Abyssal orphan\",\"activity\":\"Killing abyssal sire\",\"reqs\":[\"skill:Slayer:85\"]}},"
		+ "\"customGoals\":{\"custom:skill:agility:70\":{\"name\":\"Agility 70\",\"req\":\"skill:Agility:70\"}}"
		+ "}";

	@Test
	public void legacyProfileMigratesToUnifiedSeeds() throws Exception
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		Files.createDirectories(store.stateFile(7L).getParentFile().toPath());
		Files.write(store.stateFile(7L).toPath(), LEGACY_JSON.getBytes(StandardCharsets.UTF_8));

		AccountState state = StateFixture.state(temp.getRoot());
		state.activateProfile(7L);

		// every family arrives, keyed by full goal id, selection untouched
		assertEquals(java.util.Set.of("ca:340", "diary:1196_0", "clue:e1",
			"clog:13262", "custom:skill:agility:70"), state.getGoalSeeds().keySet());
		assertEquals(java.util.Set.of("ca:340", "diary:1196_0", "clue:e1",
			"clog:13262", "custom:skill:agility:70"), state.getSelectedGoals());
		assertEquals("ca:340", state.getActiveGoal());

		// each seed renders byte-identically to the old builder output
		PersistedState.GoalSeed ca = state.getGoalSeeds().get("ca:340");
		assertEquals("ca", ca.family);
		assertEquals("Noxious Foe", ca.name);
		assertEquals("Kill an Aberrant Spectre. (Easy combat task)", ca.steps.get(0).label);
		assertEquals("unlock:catask_340", ca.steps.get(0).requirement);
		assertEquals(java.util.List.of("unlock:catask_340"), ca.achieved);
		assertEquals("a migrated seed never invents a date", 0, ca.addedAt);

		PersistedState.GoalSeed diary = state.getGoalSeeds().get("diary:1196_0");
		assertEquals("Enter the Essence Mine", diary.name);
		assertEquals("Enter the Essence Mine (Ardougne Easy diary)", diary.steps.get(0).label);
		assertEquals(java.util.List.of("unlock:diarytask_1196_0"), diary.achieved);

		PersistedState.GoalSeed clue = state.getGoalSeeds().get("clue:e1");
		assertEquals("Beginner clue step: Bow to Brugsen Bursen", clue.name);
		assertEquals("item:1205:1:Bronze dagger", clue.steps.get(0).requirement);
		assertEquals(java.util.List.of("unlock:cluestep_e1"), clue.achieved);

		PersistedState.GoalSeed clog = state.getGoalSeeds().get("clog:13262");
		assertEquals("Abyssal orphan", clog.name);
		assertEquals(13262, clog.iconItemId);
		assertEquals("skill:Slayer:85", clog.steps.get(0).requirement);
		assertEquals("Obtain Abyssal orphan (Killing abyssal sire)", clog.steps.get(1).label);
		// the obtain step routes through item: for engine costing (G3); the
		// clogitem_ unlock stays the achieved proof
		assertEquals("item:13262", clog.steps.get(1).requirement);
		assertEquals("unlock:clogitem_13262", clog.achieved.get(0));

		PersistedState.GoalSeed custom = state.getGoalSeeds().get("custom:skill:agility:70");
		assertEquals("custom", custom.family);
		assertEquals("Agility 70", custom.name);
		assertEquals("skill:Agility:70", custom.steps.get(0).requirement);
		assertEquals(java.util.List.of("skill:Agility:70"), custom.achieved);
	}

	@Test
	public void migrationWritesThroughOnceAndNeverRepeats() throws Exception
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		Files.createDirectories(store.stateFile(9L).getParentFile().toPath());
		Files.write(store.stateFile(9L).toPath(), LEGACY_JSON.getBytes(StandardCharsets.UTF_8));

		AccountState first = StateFixture.state(temp.getRoot());
		first.activateProfile(9L);
		// migration persisted the unified form; the legacy keys are gone
		String rewritten = Files.readString(store.stateFile(9L).toPath());
		assertTrue(rewritten.contains("\"goalSeeds\""));
		assertFalse("legacy ca seed still on disk", rewritten.contains("Noxious Foe\",\"description\""));

		// a second activation sees no legacy entries and the same five seeds
		AccountState second = StateFixture.state(temp.getRoot());
		second.activateProfile(9L);
		assertEquals(first.getGoalSeeds().keySet(), second.getGoalSeeds().keySet());
		assertEquals(5, second.getGoalSeeds().size());
	}

	@Test
	public void existingUnifiedSeedIsNeverOverwrittenByALegacyTwin()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 11L);
		PersistedState.GoalSeed seed = GoalSeeds.ca(340, "Renamed since", "New text.", "Easy");
		state.addGoalSeed(seed);
		assertTrue("live adds are dated", seed.addedAt > 0);

		// reload: the persisted profile has BOTH shapes if a legacy map ever
		// coexisted — the unified one must win
		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 11L);
		assertEquals("Renamed since", after.getGoalSeeds().get("ca:340").name);
		assertEquals(seed.addedAt, after.getGoalSeeds().get("ca:340").addedAt);
	}
}
