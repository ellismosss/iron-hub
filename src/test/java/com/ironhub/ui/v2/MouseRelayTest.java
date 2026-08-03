package com.ironhub.ui.v2;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MouseRelayTest
{
	/**
	 * A root that clears its hover band in its own mouseExited (the Table,
	 * the Checklist) only hears the pointer leave if exits from children
	 * are relayed when they land outside the root — but child-to-child
	 * crossings must stay unrelayed or every hop reads as leaving.
	 */
	@Test
	public void exitBeyondTheRootRelaysButChildCrossingDoesNot() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			JPanel root = new JPanel(null);
			root.setSize(100, 50);
			JLabel child = new JLabel("x");
			child.setBounds(10, 10, 30, 20);
			root.add(child);
			MouseRelay.install(root);
			List<Integer> rootExits = new ArrayList<>();
			root.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseExited(MouseEvent e)
				{
					rootExits.add(e.getID());
				}
			});

			// leaving the child toward the root's interior: not a departure
			child.dispatchEvent(new MouseEvent(child, MouseEvent.MOUSE_EXITED,
				0, 0, 35, 10, 0, false));
			assertTrue("child-to-child crossing must not relay", rootExits.isEmpty());

			// leaving the child past the root's edge: the hover band must hear it
			child.dispatchEvent(new MouseEvent(child, MouseEvent.MOUSE_EXITED,
				0, 0, -25, 0, 0, false));
			assertEquals(1, rootExits.size());
		});
	}

	/** The uniform row height is table-wide by design — each layout query
	 *  must scan every cell once, not once per row (50 rows x 4 cells was
	 *  ~10,000 getPreferredSize calls per query on the EDT). */
	@Test
	public void tableLayoutQueriesScanEachCellOncePerPass() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			java.util.concurrent.atomic.AtomicInteger calls =
				new java.util.concurrent.atomic.AtomicInteger();
			V2Table table = new V2Table(0);
			for (int r = 0; r < 50; r++)
			{
				JLabel[] cells = new JLabel[4];
				for (int c = 0; c < 4; c++)
				{
					cells[c] = new JLabel("cell")
					{
						@Override
						public java.awt.Dimension getPreferredSize()
						{
							calls.incrementAndGet();
							return super.getPreferredSize();
						}
					};
				}
				table.row(cells);
			}
			calls.set(0);
			table.getPreferredSize();
			assertTrue("each cell measured once per layout query, saw " + calls.get(),
				calls.get() <= 50 * 4 + 8);
		});
	}
}
