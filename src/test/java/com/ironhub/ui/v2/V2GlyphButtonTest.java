package com.ironhub.ui.v2;

import java.awt.event.MouseEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the unified letter-glyph atom (2026-08-03, Luke's word): presses
 * fire on LEFT press only and consume the event, so a row that relays
 * clicks to itself can never also fire under the glyph.
 */
public class V2GlyphButtonTest
{
	@Test
	public void firesOnLeftPressOnlyAndConsumes()
	{
		int[] fired = {0};
		V2GlyphButton glyph = new V2GlyphButton("×", "Remove", () -> fired[0]++);
		assertEquals("×", glyph.text());

		MouseEvent left = new MouseEvent(glyph, MouseEvent.MOUSE_PRESSED, 0, 0,
			1, 1, 1, false, MouseEvent.BUTTON1);
		press(glyph, left);
		assertEquals(1, fired[0]);
		assertTrue("consumed so a relayed row never also fires", left.isConsumed());

		MouseEvent right = new MouseEvent(glyph, MouseEvent.MOUSE_PRESSED, 0, 0,
			1, 1, 1, false, MouseEvent.BUTTON3);
		press(glyph, right);
		assertEquals("right press must not fire", 1, fired[0]);
	}

	@Test
	public void nullActionIsInert()
	{
		V2GlyphButton glyph = new V2GlyphButton("+", "Track", null);
		press(glyph, new MouseEvent(glyph,
			MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
	}

	/** The tooltip registers ToolTipManager as a listener too — dispatch to
	 *  every listener, as Swing itself would. */
	private static void press(V2GlyphButton glyph, MouseEvent e)
	{
		for (java.awt.event.MouseListener l : glyph.getMouseListeners())
		{
			l.mousePressed(e);
		}
	}
}
