package com.ironhub.ui.osrs;

import javax.swing.JPanel;

/**
 * A stone box: the theme's fill wearing its engraved StoneBorder. The
 * building block every skinned surface composes from.
 */
public class StonePanel extends JPanel
{
	public StonePanel()
	{
		this(OsrsSkin.STONE);
	}

	public StonePanel(OsrsTheme theme)
	{
		setOpaque(true);
		setBackground(theme.boxFill);
		setBorder(new StoneBorder(theme));
	}
}
