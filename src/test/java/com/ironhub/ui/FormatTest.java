package com.ironhub.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FormatTest
{
	@Test
	public void minutesCarryIntoHoursAtTheBoundary()
	{
		// 59.5-60 minutes round up — they must carry into the hour form,
		// never render as "60m" beside neighbours reading "1h"
		assertEquals("1h", Format.hours(0.999));
		assertEquals("59m", Format.hours(59.4 / 60.0));
		assertEquals("1h 1m", Format.hours(61.0 / 60.0));
		assertEquals("45s", Format.hours(45.0 / 3600.0));
		assertEquals("?", Format.hours(Double.NaN));
	}
}
