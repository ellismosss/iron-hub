package com.ironhub.ui.osrs;

import java.awt.Color;
import javax.swing.JPanel;

/**
 * A stone box: the theme's fill wearing its engraved StoneBorder. The
 * building block every skinned surface composes from.
 */
public class StonePanel extends JPanel
{
	protected final OsrsTheme theme;

	public StonePanel(OsrsTheme theme)
	{
		this(theme, theme.background);
	}

	/** Nested on some other surface — the corner notch cuts through to it. */
	public StonePanel(OsrsTheme theme, Color outside)
	{
		this.theme = theme;
		setOpaque(true);
		setBackground(theme.boxFill);
		setBorder(new StoneBorder(theme, outside));
	}
}
