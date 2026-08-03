package com.ironhub.ui.components;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The live (visibility-gated) gate behaviour, exercised through the
 * package-private seam — the headless default keeps always-rebuild
 * semantics, which the render tests already cover.
 */
public class RebuildGateTest
{
	/** isShowing() flips true after the first read — the show completing
	 *  exactly between fire()'s visibility check and its dirty write, after
	 *  the hierarchy listener already saw the flag clear. */
	@Test
	public void stateChangeRacingTheTabShowStillRebuilds() throws Exception
	{
		AtomicInteger reads = new AtomicInteger();
		JPanel tab = new JPanel()
		{
			@Override
			public boolean isShowing()
			{
				return reads.getAndIncrement() > 0;
			}
		};
		int[] rebuilds = {0};
		Runnable listener = RebuildGate.install(tab, () -> rebuilds[0]++, false);

		listener.run(); // the client thread's notify, mid-show
		SwingUtilities.invokeAndWait(() ->
		{
		});
		assertEquals("the now-visible tab must not sit stale", 1, rebuilds[0]);
	}

	@Test
	public void hiddenTabOnlyMarksDirty()
	{
		JPanel tab = new JPanel()
		{
			@Override
			public boolean isShowing()
			{
				return false;
			}
		};
		int[] rebuilds = {0};
		Runnable listener = RebuildGate.install(tab, () -> rebuilds[0]++, false);

		listener.run();
		assertEquals(0, rebuilds[0]);
	}
}
