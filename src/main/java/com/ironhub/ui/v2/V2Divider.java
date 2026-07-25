package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;

/**
 * A 1px rule between sections.
 *
 * <p>This is one of two places the system draws rather than blits, and it is
 * deliberate: the closest sprite is the game's 6px stone bar, which marks a
 * PANEL boundary and reads far too heavy between two sections of a list.
 * A divider's whole job is to be the least it can be.
 */
public class V2Divider extends JComponent
{
	/** The whole atom. v2-exempt: a 1px rule has no sprite. */
	static final int BAR_HEIGHT = 1;
	/** Where the panel-frame art puts its bar, which the Inventory frame
	 *  still needs to clear its content by. */
	static final int BAR_TOP = 14;

	private final OsrsTheme theme;

	public V2Divider(OsrsTheme theme)
	{
		this.theme = theme;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(theme.edgeDark);
		g.fillRect(0, 0, getWidth(), BAR_HEIGHT);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, BAR_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, BAR_HEIGHT);
	}

	/** A UI-less JComponent's default minimum is its current size — 0x0 before
	 *  layout — and BoxLayout derives row alignment from child minimums. */
	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, BAR_HEIGHT);
	}
}
