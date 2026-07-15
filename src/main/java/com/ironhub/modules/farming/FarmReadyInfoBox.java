package com.ironhub.modules.farming;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * One ready-reminder infobox (frame 3f, Time Tracking Reminder parity):
 * the category's item icon, visible only while that category is ready —
 * herb patches grown, bird houses done, contract waiting.
 */
class FarmReadyInfoBox extends InfoBox
{
	private final Supplier<Boolean> ready;

	FarmReadyInfoBox(BufferedImage image, Plugin plugin, String tooltip, Supplier<Boolean> ready)
	{
		super(image, plugin);
		this.ready = ready;
		setTooltip(tooltip);
	}

	@Override
	public String getText()
	{
		return "";
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW;
	}

	@Override
	public boolean render()
	{
		return Boolean.TRUE.equals(ready.get());
	}
}
