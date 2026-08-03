package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.event.MouseEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The checkbox lights only where you aim, but presses anywhere on its row
 * (Luke, 2026-07-25). Those two areas are deliberately different sizes, which
 * is exactly the kind of thing that gets "tidied" back into one later.
 */
public class V2CheckboxTest
{
	@Test
	public void onlyTheBoxLights()
	{
		V2Checkbox checkbox = new V2Checkbox(OsrsTheme.STONE, "Herb run", false, null);
		Component box = checkbox.getComponent(0);

		assertFalse("starts unlit", checkbox.highlighted());

		enter(checkbox);
		assertFalse("the row must not light the box", checkbox.highlighted());

		enter(box);
		assertTrue("the box lights itself", checkbox.highlighted());

		exit(box);
		assertFalse("and unlights when the pointer leaves it", checkbox.highlighted());
	}

	@Test
	public void bothTheRowAndTheBoxToggle()
	{
		int[] toggles = {0};
		V2Checkbox checkbox = new V2Checkbox(OsrsTheme.STONE, "Herb run", false,
			() -> toggles[0]++);

		press(checkbox);
		assertEquals("the row is still the hit area", 1, toggles[0]);

		// the box has its own listener now, so it no longer forwards to the
		// row — without a press listener of its own the box would be dead
		press(checkbox.getComponent(0));
		assertEquals("and the box itself still toggles", 2, toggles[0]);
	}

	@Test
	public void aLockedBoxNeverToggles()
	{
		int[] toggles = {0};
		V2Checkbox checkbox = new V2Checkbox(OsrsTheme.STONE, "Hardwood run", false,
			() -> toggles[0]++).state(V2Checkbox.State.LOCKED);

		press(checkbox);
		press(checkbox.getComponent(0));
		assertEquals(0, toggles[0]);
	}

	private static void enter(Component c)
	{
		dispatch(c, MouseEvent.MOUSE_ENTERED);
	}

	private static void exit(Component c)
	{
		dispatch(c, MouseEvent.MOUSE_EXITED);
	}

	private static void press(Component c)
	{
		dispatch(c, MouseEvent.MOUSE_PRESSED);
	}

	private static void dispatch(Component c, int id)
	{
		c.dispatchEvent(new MouseEvent(c, id, 0L, 0, 1, 1, 1, false));
	}
}
