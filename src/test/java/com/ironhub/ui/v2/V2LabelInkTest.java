package com.ironhub.ui.v2;

import java.awt.Font;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class V2LabelInkTest
{
	/** Live progress bars feed value strings ("1,482 / 1,706") through the
	 *  ink probe — one cache entry each, so an unbounded map grew for the
	 *  life of the client. */
	@Test
	public void inkCacheStaysBounded()
	{
		Font font = new Font(Font.DIALOG, Font.PLAIN, 14);
		for (int i = 0; i < 2_000; i++)
		{
			V2Label.ink(font, i + " / 2,000");
		}
		assertTrue("ink cache must stay bounded, held " + V2Label.INK.size(),
			V2Label.INK.size() <= 513);
	}
}
