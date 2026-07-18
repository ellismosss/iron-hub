package com.ironhub.modules.goals;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The completion archive (Goals v2 G2): a selected goal flipping
 * unachieved→achieved records once, dated only when genuinely worked this
 * session; goal removal never records; a login replay of an archived goal
 * keeps its original date; a re-armed supply goal upserts. Detection runs
 * on the planner's own thread, so each case waits for the record to land.
 */
public class GoalArchiveTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private GoalPlannerModule module(AccountState state)
	{
		GoalPlannerModule module = new GoalPlannerModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null);
		module.startUp();
		return module;
	}

	@Test
	public void genuineCompletionIsDated() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, Skill.AGILITY, 1, 0);
		state.addGoalSeed(GoalSeeds.custom("custom:skill:agility:5", "Agility 5", "skill:Agility:5"));
		GoalPlannerModule module = module(state);
		waitForPlan(module);                    // first pass: goal is unachieved, nothing archived
		Thread.sleep(300);
		assertTrue(state.getGoalRecords().isEmpty());

		// worked this session: level up past the gate
		StateFixture.stat(state, Skill.AGILITY, 5, net.runelite.api.Experience.getXpForLevel(5));
		PersistedState.GoalRecord record = waitForRecord(state, "custom:skill:agility:5");
		assertTrue("worked this session must be dated", record.completedAt > 0);
		assertEquals("Agility 5", record.name);
		module.shutDown();
	}

	@Test
	public void preExistingFeatIsDetectedNotDated() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, Skill.AGILITY, 10, net.runelite.api.Experience.getXpForLevel(10));
		// already satisfied before the module ever plans
		state.addGoalSeed(GoalSeeds.custom("custom:skill:agility:5", "Agility 5", "skill:Agility:5"));
		GoalPlannerModule module = module(state);

		PersistedState.GoalRecord record = waitForRecord(state, "custom:skill:agility:5");
		assertEquals("a feat already done when first seen is detected, not dated",
			0, record.completedAt);
		module.shutDown();
	}

	@Test
	public void removalNeverRecords() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, Skill.AGILITY, 1, 0);
		state.addGoalSeed(GoalSeeds.custom("custom:skill:agility:5", "Agility 5", "skill:Agility:5"));
		GoalPlannerModule module = module(state);
		waitForPlan(module);

		GoalPlannerModule.removeGoal(state, "custom:skill:agility:5");
		Thread.sleep(700); // let any replan settle
		assertTrue("removing a goal must never archive it", state.getGoalRecords().isEmpty());
		module.shutDown();
	}

	@Test
	public void loginReplayKeepsTheOriginalDate() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, Skill.AGILITY, 1, 0);
		state.addGoalSeed(GoalSeeds.custom("custom:skill:agility:5", "Agility 5", "skill:Agility:5"));
		GoalPlannerModule module = module(state);
		waitForPlan(module);
		StateFixture.stat(state, Skill.AGILITY, 5, net.runelite.api.Experience.getXpForLevel(5));
		PersistedState.GoalRecord dated = waitForRecord(state, "custom:skill:agility:5");
		long originalDate = dated.completedAt;
		assertTrue(originalDate > 0);
		state.persistNow();
		module.shutDown();

		// reload: the goal is still selected + achieved. A fresh module's
		// first pass must NOT re-date it to "detected".
		AccountState reloaded = StateFixture.state(temp.getRoot());
		StateFixture.profile(reloaded, 1L);
		assertEquals(1, reloaded.getGoalRecords().size());
		GoalPlannerModule replay = module(reloaded);
		waitForPlan(replay);
		// the archived goal must stay untouched through every replan/suggestion
		// cycle — poll for stability rather than trust one fixed sleep
		for (int i = 0; i < 15; i++)
		{
			java.util.List<PersistedState.GoalRecord> after = reloaded.getGoalRecords();
			assertEquals("no duplicate on replay", 1, after.size());
			assertEquals("the original completion date survives the replay",
				originalDate, after.get(0).completedAt);
			Thread.sleep(100);
		}
		replay.shutDown();
	}

	@Test
	public void reArmedSupplyGoalUpserts() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.itemNames(state, java.util.Map.of(385, "Shark"));
		StateFixture.bank(state, java.util.Map.of(385, 50));
		state.addGoalSeed(GoalSeeds.supply(385, "Shark", 100));
		GoalPlannerModule module = module(state);
		waitForPlan(module);

		StateFixture.bank(state, java.util.Map.of(385, 120)); // stock 100 done
		waitForRecord(state, "supply:385");
		assertEquals(1, state.getGoalRecords().size());

		// re-arm at 200 (unachieved again), then stock past it
		state.addGoalSeed(GoalSeeds.supply(385, "Shark", 200));
		Thread.sleep(600);
		StateFixture.bank(state, java.util.Map.of(385, 250));
		waitForRecordCount(state, 1); // still one record for the same goalId (upsert)
		assertEquals("re-arm upserts, never duplicates", 1, state.getGoalRecords().size());
		module.shutDown();
	}

	// ── waiters (detection is async on the planner thread) ─────────────

	private static void waitForPlan(GoalPlannerModule module) throws InterruptedException
	{
		for (int i = 0; i < 50 && module.currentPlan() == null; i++)
		{
			Thread.sleep(100);
		}
	}

	private static PersistedState.GoalRecord waitForRecord(AccountState state, String goalId)
		throws InterruptedException
	{
		for (int i = 0; i < 60; i++)
		{
			for (PersistedState.GoalRecord r : state.getGoalRecords())
			{
				if (r.goalId.equals(goalId))
				{
					return r;
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("goal never archived: " + goalId);
	}

	private static void waitForRecordCount(AccountState state, int n) throws InterruptedException
	{
		for (int i = 0; i < 30 && state.getGoalRecords().size() != n; i++)
		{
			Thread.sleep(100);
		}
	}
}
