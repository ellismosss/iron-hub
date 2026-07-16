package com.ironhub.modules.dailies;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Daily-run progress infobox (frame 3f): "3/7" while a run is active, hidden
 * otherwise — the farm run timer's twin.
 */
class DailyRunInfoBox extends InfoBox
{
	private final DailiesModule module;

	DailyRunInfoBox(BufferedImage image, Plugin plugin, DailiesModule module)
	{
		super(image, plugin);
		this.module = module;
	}

	@Override
	public String getText()
	{
		return module.visitedCount() + "/" + module.stops().size();
	}

	@Override
	public Color getTextColor()
	{
		return Color.YELLOW; // canvas convention: short yellow overlay text
	}

	@Override
	public String getTooltip()
	{
		return "Daily run · " + DailiesModule.formatDuration(module.elapsedMs())
			+ (module.nextStop() == null ? "" : " · next: " + module.nextStop().name);
	}

	@Override
	public boolean render()
	{
		return module.running();
	}
}
