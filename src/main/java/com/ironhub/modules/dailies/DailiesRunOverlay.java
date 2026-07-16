package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Daily run overlay, the farm run's twin (frame 3b): run + timer, the current
 * stop with how to get there and what to bring, progress, then the upcoming
 * stops. Shown only during a run; display-only ("&gt;" = next, "·" upcoming).
 */
class DailiesRunOverlay extends OverlayPanel
{
	private static final int WIDTH = 190; // inside the 250x200 budget
	/** Cap the upcoming list so a full 10-stop run stays inside 200 px. */
	private static final int MAX_UPCOMING = 5;

	private final DailiesModule module;

	DailiesRunOverlay(DailiesModule module)
	{
		this.module = module;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!module.running())
		{
			return null;
		}
		panelComponent.getChildren().clear();
		panelComponent.setBackgroundColor(UiTokens.OVERLAY_BG);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Daily run").leftColor(Color.WHITE)
			.right(DailiesModule.formatDuration(module.elapsedMs()))
			.rightColor(UiTokens.OVERLAY_VALUE)
			.build());

		DailiesPack.Daily next = module.nextStop();
		if (next != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("> " + next.name).leftColor(Color.WHITE)
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left(next.where).leftColor(UiTokens.CANVAS_LOCKED)
				.right(next.travel == null ? "" : next.travel)
				.rightColor(UiTokens.OVERLAY_VALUE)
				.build());

			String bring = module.bringLine(next);
			if (!bring.isEmpty())
			{
				// Amber (actionable), not red: red is a verified shortfall, and
				// we never checked your bank — this is a reminder, not a warning.
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Bring: " + bring)
					.leftColor(UiTokens.CANVAS_AVAILABLE)
					.build());
			}
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(module.visitedCount() + "/" + module.stops().size())
			.leftColor(UiTokens.OVERLAY_VALUE)
			.build());

		// Upcoming only — done stops are counted in the progress line, and a
		// long run would otherwise overflow the overlay budget. The sidebar
		// carries the full list.
		int shown = 0;
		int remaining = 0;
		for (DailiesPack.Daily daily : module.stops())
		{
			if (module.isVisited(daily.id) || daily == next)
			{
				continue; // done, or already headlined above
			}
			if (shown < MAX_UPCOMING)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("· " + daily.name).leftColor(UiTokens.CANVAS_LOCKED)
					.build());
				shown++;
			}
			else
			{
				remaining++;
			}
		}
		if (remaining > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  …+" + remaining + " more").leftColor(UiTokens.CANVAS_LOCKED)
				.build());
		}
		return super.render(graphics);
	}
}
