package com.ironhub.modules.farming;

import com.ironhub.data.HerbPatchesPack;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Herb run overlay (frame 3b): title + elapsed timer, per-patch checklist,
 * footer count. Shown only during a run; display-only. Marker glyphs stay
 * ASCII-safe for the RuneScape font ("&gt;" = next, "·" elsewhere).
 */
class FarmingRunOverlay extends OverlayPanel
{
	private static final int WIDTH = 150; // within the 250×200 budget

	private final FarmingRunModule module;

	FarmingRunOverlay(FarmingRunModule module)
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
			.left("Herb run").leftColor(Color.WHITE)
			.right(FarmingRunModule.formatDuration(module.elapsedMs()))
			.rightColor(UiTokens.OVERLAY_VALUE)
			.build());

		HerbPatchesPack.Patch next = module.nextPatch();
		for (HerbPatchesPack.Patch patch : module.patches())
		{
			boolean done = module.isVisited(patch.getId());
			boolean isNext = !done && next != null && next.getId().equals(patch.getId());
			panelComponent.getChildren().add(LineComponent.builder()
				.left((isNext ? "> " : "· ") + patch.getName())
				.leftColor(done ? UiTokens.CANVAS_OWNED
					: isNext ? Color.WHITE : UiTokens.CANVAS_LOCKED)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(module.visitedCount() + " of " + module.patches().size() + " done")
			.leftColor(UiTokens.CANVAS_LOCKED)
			.build());
		return super.render(graphics);
	}
}
