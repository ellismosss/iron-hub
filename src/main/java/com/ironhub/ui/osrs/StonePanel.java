package com.ironhub.ui.osrs;

import javax.swing.JPanel;

/**
 * A stone box: BOX_FILL interior wearing the engraved StoneBorder. The
 * building block every skinned surface composes from.
 */
public class StonePanel extends JPanel
{
	public StonePanel()
	{
		setOpaque(true);
		setBackground(OsrsSkin.BOX_FILL);
		setBorder(new StoneBorder());
	}
}
