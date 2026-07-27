package com.ironhub.ui.v2;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Re-dispatches every descendant's mouse events to a root component, with
 * the coordinates converted into the root's space.
 *
 * <p>This is THE fix for a whole class of "it only reacts beside the text"
 * bugs (Luke, 2026-07-27, after the third one): Swing delivers a mouse event
 * to the DEEPEST component that is interested — and a tooltip is enough to
 * make a label interested, because ToolTipManager registers listeners on it.
 * So any container that implements its own hover band or click (the Table,
 * the Checklist, a pressable card) goes dead exactly where its content is,
 * which is where the pointer usually sits. Events do not bubble in AWT;
 * this relay makes them, for one root, by re-dispatching.
 *
 * <p>The child's own listeners still run first — a "+" glyph keeps its
 * action, a label keeps its popup — the root just SEES the event too.
 * Enter/exit are deliberately not relayed: crossing between two children
 * would read as leaving the root. Watches descendants added later via
 * container events, the {@code listenForHover} approach generalised.
 */
public final class MouseRelay
{
	private MouseRelay()
	{
	}

	/** Forward every descendant's press/release/click/move/drag to root. */
	public static void install(JComponent root)
	{
		watch(root, root);
	}

	private static void watch(Component component, JComponent root)
	{
		if (component != root)
		{
			MouseAdapter forward = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					relay(e, root);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					relay(e, root);
				}

				@Override
				public void mouseClicked(MouseEvent e)
				{
					relay(e, root);
				}

				@Override
				public void mouseMoved(MouseEvent e)
				{
					relay(e, root);
				}

				@Override
				public void mouseDragged(MouseEvent e)
				{
					relay(e, root);
				}
			};
			component.addMouseListener(forward);
			component.addMouseMotionListener(forward);
		}
		if (component instanceof Container)
		{
			((Container) component).addContainerListener(new ContainerAdapter()
			{
				@Override
				public void componentAdded(ContainerEvent e)
				{
					watch(e.getChild(), root);
				}
			});
			for (Component child : ((Container) component).getComponents())
			{
				watch(child, root);
			}
		}
	}

	private static void relay(MouseEvent e, JComponent root)
	{
		root.dispatchEvent(SwingUtilities.convertMouseEvent(
			(Component) e.getSource(), e, root));
	}
}
