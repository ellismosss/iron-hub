package com.ironhub.modules.farming;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Patches-ready infobox (frame 3f): herb patches ready or predicted
 * ready; hidden at zero.
 */
class PatchesReadyInfoBox extends InfoBox
{
	private final FarmingRunModule module;

	PatchesReadyInfoBox(BufferedImage image, Plugin plugin, FarmingRunModule module)
	{
		super(image, plugin);
		this.module = module;
		setTooltip("Herb patches ready (observed or predicted)");
	}

	@Override
	public String getText()
	{
		return String.valueOf(module.readyCount());
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW;
	}

	@Override
	public boolean render()
	{
		return module.readyCount() > 0;
	}
}
