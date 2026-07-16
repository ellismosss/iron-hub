package com.ironhub.modules.dailies;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Dailies-outstanding infobox (frame 3f): how many ticked events are claimable
 * right now; hidden at zero, and hidden during a run (the run box takes over).
 */
class DailiesInfoBox extends InfoBox
{
	private final DailiesModule module;

	DailiesInfoBox(BufferedImage image, Plugin plugin, DailiesModule module)
	{
		super(image, plugin);
		this.module = module;
		setTooltip("Dailies outstanding · resets 00:00 UTC");
	}

	@Override
	public String getText()
	{
		return String.valueOf(module.outstanding());
	}

	@Override
	public Color getTextColor()
	{
		// Reminder parity: green means "go and get it".
		return Color.GREEN;
	}

	@Override
	public boolean render()
	{
		return !module.running() && module.outstanding() > 0;
	}
}
