package com.ironhub.modules.farming;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Run timer infobox (frame 3f): m:ss elapsed, visible during a run only.
 */
class RunTimerInfoBox extends InfoBox
{
	private final FarmingRunModule module;

	RunTimerInfoBox(BufferedImage image, Plugin plugin, FarmingRunModule module)
	{
		super(image, plugin);
		this.module = module;
	}

	@Override
	public String getText()
	{
		return FarmingRunModule.formatDuration(module.elapsedMs());
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW;
	}

	@Override
	public boolean render()
	{
		return module.running();
	}

	@Override
	public String getTooltip()
	{
		return "herb run · " + module.visitedCount() + "/" + module.patches().size()
			+ " · " + FarmingRunModule.statsLine(module.state().getHerbRunsMs());
	}
}
