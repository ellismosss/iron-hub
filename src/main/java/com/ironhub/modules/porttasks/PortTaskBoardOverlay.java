package com.ironhub.modules.porttasks;

import com.ironhub.IronHubConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The noticeboard advisor's in-game surface: while a port task board is
 * open, the top courier picks by Sailing XP per tile added to the
 * player's current route (display-only, {@code portBoardAdvice} toggle).
 */
class PortTaskBoardOverlay extends Overlay
{
	private static final int MAX_PICKS = 3;

	private final PortTasksModule module;
	private final IronHubConfig config;
	private final PanelComponent panel = new PanelComponent();

	/** rankOffers is an exact Held-Karp tour per courier offer — recompute
	 *  only when its inputs change, never per frame (2026-07-20 audit). */
	private List<PortTasksModule.Advice> ranked = List.of();
	private Object rankedKey;

	PortTaskBoardOverlay(PortTasksModule module, IronHubConfig config)
	{
		this.module = module;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.portBoardAdvice() || !module.boardOpen())
		{
			return null;
		}
		panel.getChildren().clear();
		panel.getChildren().add(TitleComponent.builder().text("Best picks").build());
		Object key = module.adviceKey();
		if (!key.equals(rankedKey))
		{
			rankedKey = key;
			ranked = module.rankOffers();
		}
		if (ranked.isEmpty())
		{
			panel.getChildren().add(LineComponent.builder()
				.left("Task data not loaded yet").build());
			return panel.render(graphics);
		}
		int shown = 0;
		for (PortTasksModule.Advice advice : ranked)
		{
			if (advice.courier == null || advice.levelGated || advice.alreadyTaken)
			{
				continue; // couriers only — bounty kill time is unknowable
			}
			String tiles = Double.isNaN(advice.marginalTiles) ? "?"
				: "+" + Math.round(advice.marginalTiles);
			panel.getChildren().add(LineComponent.builder()
				.left((shown + 1) + ". " + advice.label)
				.right(advice.xp + " xp · " + tiles)
				.build());
			if (++shown >= MAX_PICKS)
			{
				break;
			}
		}
		if (shown == 0)
		{
			panel.getChildren().add(LineComponent.builder()
				.left("No courier offers you can take").build());
		}
		else
		{
			panel.getChildren().add(LineComponent.builder()
				.left("xp · tiles added to your route").build());
		}
		return panel.render(graphics);
	}
}
