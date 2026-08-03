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
	private final com.ironhub.IronHubConfig config;

	/** Missing-bring for the current stop, refreshed at most every 600 ms —
	 *  it walks containers per entry and render() runs per frame (the
	 *  memoize rule, 2026-07-20 audit; the farm overlay's pattern). */
	private long cachedAtMs;
	private String cachedStopId;
	private java.util.List<String> cachedMissing = java.util.List.of();
	private String cachedUnverified = "";

	DailiesRunOverlay(DailiesModule module, com.ironhub.IronHubConfig config)
	{
		this.module = module;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.dailiesOverlay() || !module.running())
		{
			return null;
		}
		panelComponent.getChildren().clear();
		// the STANDARD RuneLite overlay background, exactly like the goals
		// planner — PanelComponent defaults to it, so never override
		// (Luke, 2026-08-03 round 3)
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
				.build());
			// Travel gets its own full-width line. As a right-hand value it
			// wrapped to four lines beside a one-line left, and the panel grew a
			// hole three lines deep next to "Bert, Yanille".
			if (next.travel != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(next.travel).leftColor(UiTokens.OVERLAY_VALUE)
					.build());
			}

			long now = System.currentTimeMillis();
			if (!next.id.equals(cachedStopId) || now - cachedAtMs >= 600)
			{
				cachedAtMs = now;
				cachedStopId = next.id;
				cachedMissing = module.missingBring(next);
				cachedUnverified = module.unverifiedBringLine(next);
			}
			// Only what you are verifiably SHORT of shows, in red — red is
			// earned, these are checked against inventory + worn (the
			// only-missing rule, X2 2026-08-03). Entries the pack cannot
			// verify (no item ids) stay an amber reminder: we never checked.
			if (!cachedMissing.isEmpty())
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Missing: " + String.join(" · ", cachedMissing))
					.leftColor(UiTokens.STATUS_WARNING)
					.build());
			}
			if (!cachedUnverified.isEmpty())
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Bring: " + cachedUnverified)
					.leftColor(UiTokens.CANVAS_AVAILABLE)
					.build());
			}
			for (String line : module.stopAdvice(next))
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(line).leftColor(UiTokens.CANVAS_AVAILABLE)
					.build());
			}
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(module.visitedCount() + "/" + module.stops().size())
			.leftColor(UiTokens.OVERLAY_VALUE)
			.build());
		// the countable target wears the overlay bar, slayer-grammar style
		// (X2 2026-08-03)
		if (!module.stops().isEmpty())
		{
			panelComponent.getChildren().add(new com.ironhub.ui.components.OverlayStoneBar(
				module.visitedCount() / (double) module.stops().size(),
				config.osrsTheme(), WIDTH - 8));
		}

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
