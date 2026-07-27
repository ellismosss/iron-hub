// Derived from guccifurs/best-dps (BSD-2-Clause, Copyright (c) 2026, Noid) - see licenses/best-dps-LICENSE.
package com.loadoutlab.engine;

import org.junit.Assert;
import org.junit.Test;

public class RollMathTest
{
	@Test
	public void normalAccuracyMatchesOsrsFormulaWhenAttackRollWins()
	{
		double accuracy = RollMath.normalAccuracy(20_000, 10_000);
		Assert.assertEquals(0.7499625018749062, accuracy, 1e-12);
	}

	@Test
	public void normalAccuracyMatchesOsrsFormulaWhenDefenceRollWins()
	{
		double accuracy = RollMath.normalAccuracy(10_000, 20_000);
		Assert.assertEquals(0.24998750062496874, accuracy, 1e-12);
	}

	@Test
	public void expectedHitIncludesAccurateZeroAsOne()
	{
		double expected = RollMath.normalExpectedHit(1.0, 10);
		Assert.assertEquals(5.090909090909091, expected, 1e-12);
	}

	@Test
	public void maxHitUsesStandardEffectiveFormula()
	{
		Assert.assertEquals(21, RollMath.maxHitFromEffective(107, 64));
	}

	/**
	 * Live-site parity anchor (2026-07-27): Luke's Dual macuahuitl set vs
	 * Dust devil on Pound/Accurate (share dps.osrs.wiki?id=
	 * ChildsControlsWarlock) - the calculator displays 5.389 DPS, which is
	 * exactly this pipeline INCLUDING the min-1-damage-on-success term in
	 * normalExpectedHit (max/2 alone gives 5.376). Pins the whole hit model
	 * against an observed official number; do not "fix" the 1/(max+1) term.
	 */
	@Test
	public void dpsPipelineMatchesLiveOfficialCalculator()
	{
		long attackRoll = 101 * (134 + 64); // eff 90+3+8, crush +134
		long defenceRoll = (40 + 9) * 64L;  // Dust devil, crush +0
		double accuracy = RollMath.normalAccuracy(attackRoll, defenceRoll);
		double dps = RollMath.normalExpectedHit(accuracy, 28) / (4 * RollMath.SECONDS_PER_TICK);
		Assert.assertEquals(5.389, dps, 5e-4);
	}
}
