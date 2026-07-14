package com.ironhub.modules.sync;

import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExternalSyncTest
{
	@Test
	public void milestoneDetection()
	{
		assertEquals(Optional.empty(), ExternalSyncModule.milestone(41, 43));
		assertEquals(Optional.of(50), ExternalSyncModule.milestone(49, 50));
		assertEquals(Optional.of(80), ExternalSyncModule.milestone(78, 81)); // crossed 80
		assertEquals(Optional.of(99), ExternalSyncModule.milestone(98, 99));
		assertEquals(Optional.empty(), ExternalSyncModule.milestone(50, 50)); // no gain
	}
}
