package com.ironhub.engine;

import com.ironhub.data.MethodsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.List;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CostModelTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static MethodsPack.Method method(String id, int startXp, int rate, String req)
	{
		MethodsPack.Method m = new MethodsPack.Method();
		m.id = id;
		m.name = id;
		m.startXp = startXp;
		m.rate = rate;
		m.req = req;
		m.style = "active";
		m.source = "test";
		return m;
	}

	private static MethodsPack pack(String skill, MethodsPack.Method... methods)
	{
		MethodsPack pack = new MethodsPack();
		MethodsPack.SkillLadder ladder = new MethodsPack.SkillLadder();
		ladder.skill = skill;
		ladder.methods = List.of(methods);
		pack.skills = List.of(ladder);
		return pack;
	}

	@Test
	public void integratesPiecewiseAcrossMethodThresholds()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.FISHING, 50, Experience.getXpForLevel(50));
		// 10k/hr from 0, 50k/hr once past level-60 xp
		MethodsPack methods = pack("Fishing",
			method("slow", 0, 10_000, null),
			method("fast", Experience.getXpForLevel(60), 50_000, null));

		long xp50 = Experience.getXpForLevel(50);
		long xp60 = Experience.getXpForLevel(60);
		long xp70 = Experience.getXpForLevel(70);
		double expected = (xp60 - xp50) / 10_000.0 + (xp70 - xp60) / 50_000.0;
		assertEquals(expected,
			CostModel.trainHours(Skill.FISHING, 70, new ProjectedState(state), methods, 0), 1e-9);
	}

	@Test
	public void measuredRateBeatsPackRatesAndSurvivesProjection()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.FISHING, 50, Experience.getXpForLevel(50));
		MethodsPack methods = pack("Fishing", method("slow", 0, 10_000, null));

		long remaining = Experience.getXpForLevel(70) - Experience.getXpForLevel(50);
		// below the 1h observation threshold: pack rates hold
		StateFixture.measuredRate(state, Skill.FISHING, 20_000, 0.5);
		assertEquals(remaining / 10_000.0,
			CostModel.trainHours(Skill.FISHING, 70, new ProjectedState(state), methods, 0), 1e-9);

		// enough observation: the measured EWMA prices the whole stretch,
		// and the projection delegates to the base view's pace
		StateFixture.measuredRate(state, Skill.FISHING, 40_000, 1.0);
		double rate = state.measuredRate(Skill.FISHING);
		assertTrue(rate > 0);
		assertEquals(remaining / rate,
			CostModel.trainHours(Skill.FISHING, 70, new ProjectedState(state), methods, 0), 1e-9);
	}

	/** Banked MATERIALS do not shorten training TIME — you still click each
	 *  one at the method's rate (Luke, 2026-07-24: Superglass 72→75 read
	 *  ~1m because banked molten glass zeroed the gap). Time = RAW gap. */
	@Test
	public void bankedXpDoesNotShortenTrainingTime()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.PRAYER, 43, Experience.getXpForLevel(43));
		MethodsPack methods = pack("Prayer", method("bones", 0, 100_000, null));

		long remaining = Experience.getXpForLevel(70) - Experience.getXpForLevel(43);
		double raw = remaining / 100_000.0;
		assertEquals(raw,
			CostModel.trainHours(Skill.PRAYER, 70, new ProjectedState(state), methods, 0), 1e-9);
		// banked xp is ignored for time — same raw hours regardless
		assertEquals(raw,
			CostModel.trainHours(Skill.PRAYER, 70, new ProjectedState(state), methods, 50_000), 1e-9);
		assertEquals(raw,
			CostModel.trainHours(Skill.PRAYER, 70, new ProjectedState(state), methods, 99_999_999L), 1e-9);
	}

	@Test
	public void lockedMethodsAreSkippedUntilTheProjectionUnlocksThem()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.FLETCHING, 55, Experience.getXpForLevel(55));
		MethodsPack methods = pack("Fletching",
			method("arrows", 0, 60_000, null),
			method("broads", 0, 240_000, "unlock:broader_fletching"));

		ProjectedState locked = new ProjectedState(state);
		long remaining = Experience.getXpForLevel(60) - Experience.getXpForLevel(55);
		assertEquals(remaining / 60_000.0,
			CostModel.trainHours(Skill.FLETCHING, 60, locked, methods, 0), 1e-9);

		ProjectedState unlocked = new ProjectedState(state);
		unlocked.addUnlock("broader_fletching");
		assertEquals(remaining / 240_000.0,
			CostModel.trainHours(Skill.FLETCHING, 60, unlocked, methods, 0), 1e-9);
	}

	@Test
	public void unknownRatesAreNaNNeverInvented()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		MethodsPack methods = pack("Sailing", method("trials", 0, 0, null));
		assertTrue(Double.isNaN(
			CostModel.trainHours(Skill.SAILING, 50, new ProjectedState(state), methods, 0)));
		assertTrue(Double.isNaN(
			CostModel.trainHours(Skill.HUNTER, 50, new ProjectedState(state), null, 0)));
	}

	@Test
	public void killAndDropMath()
	{
		assertEquals(2.0, CostModel.killHours(50, 25), 1e-9);
		assertEquals(50.0, CostModel.expectedKillsForDrop(1 / 50.0), 1e-9);
		// geometric P90 for 1/50 ≈ 114 kills
		assertEquals(114, CostModel.unluckyKillsForDrop(1 / 50.0), 1.0);
		assertTrue(Double.isNaN(CostModel.killHours(50, 0)));
	}

	@Test
	public void questHoursScaleWithTravelFactor()
	{
		assertEquals(1.5, CostModel.questHours(60, 1.5), 1e-9);
		assertEquals(1.0, CostModel.questHours(60, 1.0), 1e-9);
		assertTrue(Double.isNaN(CostModel.questHours(0, 1.0)));
	}
}
