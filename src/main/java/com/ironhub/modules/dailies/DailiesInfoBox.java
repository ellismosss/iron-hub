package com.ironhub.modules.dailies;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.IntSupplier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Dailies-outstanding infobox (frame 3f): count of dailies available and
 * not yet ticked; hidden at zero. Click-through lands on the panel later.
 */
class DailiesInfoBox extends InfoBox
{
	private final IntSupplier outstanding;

	DailiesInfoBox(BufferedImage image, Plugin plugin, IntSupplier outstanding)
	{
		super(image, plugin);
		this.outstanding = outstanding;
		setTooltip("Dailies outstanding · resets 00:00 UTC");
	}

	@Override
	public String getText()
	{
		return String.valueOf(outstanding.getAsInt());
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW; // canvas convention: short yellow overlay text
	}

	@Override
	public boolean render()
	{
		return outstanding.getAsInt() > 0;
	}
}
