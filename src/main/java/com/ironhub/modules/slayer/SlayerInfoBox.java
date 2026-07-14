package com.ironhub.modules.slayer;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Slayer task infobox (frame 3f): remaining count, visible while on task.
 */
class SlayerInfoBox extends InfoBox
{
	private final SlayerOptimizerModule module;

	SlayerInfoBox(BufferedImage image, Plugin plugin, SlayerOptimizerModule module)
	{
		super(image, plugin);
		this.module = module;
	}

	@Override
	public String getText()
	{
		return String.valueOf(module.remaining());
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW;
	}

	@Override
	public boolean render()
	{
		return module.remaining() > 0;
	}

	@Override
	public String getTooltip()
	{
		String name = module.taskName();
		return (name.isEmpty() ? "Slayer task" : name) + " · " + module.remaining()
			+ " left · streak " + module.streak() + " · " + module.points() + " pts";
	}
}
