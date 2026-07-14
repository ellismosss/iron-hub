package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;

/**
 * Vertical-only scroll pane for panel content, matching what other
 * RuneLite plugins do: content anchored north (natural height), LAF-themed
 * scrollbar, and a sane wheel increment — the client's default wrapped
 * panel never sets one, which is why it scrolls a pixel at a time.
 * Headers stay outside this pane so back navigation never scrolls away.
 */
public class HubScrollPane extends JScrollPane
{
	public HubScrollPane(JComponent content)
	{
		super(northAnchor(content), VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
		setBorder(null);
		setBackground(UiTokens.PANEL_BG);
		getViewport().setBackground(UiTokens.PANEL_BG);
		getVerticalScrollBar().setUnitIncrement(UiTokens.SCROLL_UNIT);
	}

	private static JPanel northAnchor(JComponent content)
	{
		JPanel anchor = new WidthTrackingPanel();
		anchor.setBackground(UiTokens.PANEL_BG);
		anchor.add(content, BorderLayout.NORTH);
		return anchor;
	}

	/**
	 * The view always matches the viewport width (vertical scroll only —
	 * a plain panel gets its preferred width, which lets wide WrapLayout
	 * rows push content past the 225 px sidebar instead of wrapping).
	 */
	private static class WidthTrackingPanel extends JPanel implements Scrollable
	{
		WidthTrackingPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return UiTokens.SCROLL_UNIT;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
