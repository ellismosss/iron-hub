package com.ironhub.ui.components;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class SpriteScalingTest
{
	/** 2026-08-03 regression: width -1 (the row-icon convention) must derive
	 *  the width from the aspect, never clamp to a 1px strip. */
	@Test
	public void widthMinusOnePreservesAspect()
	{
		// the standard 36x32 item sprite at row height 16 -> 18x16
		assertArrayEquals(new int[]{18, 16}, SpriteCache.scaledDims(36, 32, -1, 16));
		assertArrayEquals(new int[]{36, 32}, SpriteCache.scaledDims(36, 32, -1, 32));
	}

	@Test
	public void boxFitShrinksTheLongSide()
	{
		// wide sprite fits the box on its long side
		assertArrayEquals(new int[]{16, 14}, SpriteCache.scaledDims(36, 32, 0xFFFE, 16));
		// square passes through
		assertArrayEquals(new int[]{16, 16}, SpriteCache.scaledDims(32, 32, 0xFFFE, 16));
	}

	@Test
	public void explicitDimensionsPassThrough()
	{
		assertArrayEquals(new int[]{20, 10}, SpriteCache.scaledDims(36, 32, 20, 10));
	}
}
