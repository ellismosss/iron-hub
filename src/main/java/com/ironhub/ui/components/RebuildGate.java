package com.ironhub.ui.components;

import java.awt.GraphicsEnvironment;
import java.awt.event.HierarchyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Coalesced, visibility-gated tab refresh. AccountState fires listeners on
 * the client thread for every meaningful change, and module tabs used to
 * queue a full EDT rebuild each time whether or not anyone could see them —
 * after browsing every hub, ONE state change rebuilt ~16 tabs (2026-07-17
 * freeze audit). Wrapped in this gate, a hidden tab just marks itself dirty
 * and rebuilds once when next shown, and a visible tab coalesces a burst of
 * notifications into a single queued rebuild.
 *
 * <p>Headless (render tests) there is no showing window at all, so the gate
 * keeps the old always-rebuild behaviour — tests mutate state and assert the
 * tab caught up after an EDT flush, exactly as before.
 */
public final class RebuildGate
{
	private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

	private final JComponent tab;
	private final Runnable rebuild;
	private final AtomicBoolean queued = new AtomicBoolean();
	private volatile boolean dirtyWhileHidden;

	private RebuildGate(JComponent tab, Runnable rebuild)
	{
		this.tab = tab;
		this.rebuild = rebuild;
	}

	/**
	 * Returns the listener to hand to {@code AccountState.addListener};
	 * also hooks the tab's hierarchy so a dirty tab rebuilds when shown.
	 * Callable from a field initializer — it only stores references.
	 */
	public static Runnable install(JComponent tab, Runnable rebuild)
	{
		RebuildGate gate = new RebuildGate(tab, rebuild);
		tab.addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
				&& tab.isShowing() && gate.dirtyWhileHidden)
			{
				gate.dirtyWhileHidden = false;
				// never rebuild mid-hierarchy-change — queue it
				SwingUtilities.invokeLater(gate::runIfShowing);
			}
		});
		return gate::fire;
	}

	private void fire()
	{
		if (!HEADLESS && !tab.isShowing())
		{
			dirtyWhileHidden = true;
			return;
		}
		if (queued.compareAndSet(false, true))
		{
			SwingUtilities.invokeLater(() ->
			{
				queued.set(false);
				runIfShowing();
			});
		}
	}

	private void runIfShowing()
	{
		if (HEADLESS || tab.isShowing())
		{
			rebuild.run();
		}
		else
		{
			dirtyWhileHidden = true;
		}
	}
}
