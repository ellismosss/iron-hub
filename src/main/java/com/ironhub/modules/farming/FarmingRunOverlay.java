package com.ironhub.modules.farming;

import com.ironhub.data.FarmRunsPack;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Farm run overlay (frame 3b, grown Quest-Helper-style over the pack):
 * title + elapsed timer, the current stop with its auto-picked teleport,
 * any teleport items you are not carrying (red), the live patch states at
 * that stop, then the remaining stop checklist. Shown only during a run;
 * display-only ("&gt;" = next, "·" elsewhere — ASCII-safe glyphs).
 * Right-click ends the run early without opening the sidebar.
 */
class FarmingRunOverlay extends OverlayPanel
{
	private static final int WIDTH = 170; // within the 250×200 budget
	private static final String MENU_TARGET = "Farm run";

	private final FarmingRunModule module;

	FarmingRunOverlay(FarmingRunModule module)
	{
		this.module = module;
		setPosition(OverlayPosition.TOP_LEFT);
		// Only reachable by right-clicking the overlay, which is drawn only
		// during a run — so the entry can live here unconditionally.
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "End run", MENU_TARGET,
			e -> module.endRun(false)); // client thread; abandoned = not recorded
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
			.left(module.runName()).leftColor(Color.WHITE)
			.right(FarmingRunModule.formatDuration(module.elapsedMs()))
			.rightColor(UiTokens.OVERLAY_VALUE)
			.build());

		FarmingRunModule.Stop next = module.nextStop();
		if (next != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("> " + next.location.name)
				.leftColor(Color.WHITE)
				.right(teleportLabel(next.teleport))
				.rightColor(UiTokens.OVERLAY_VALUE)
				.build());

			List<FarmRunsPack.Item> missing = module.missingItems(next);
			if (!missing.isEmpty())
			{
				StringJoiner names = new StringJoiner(", ");
				for (FarmRunsPack.Item item : missing)
				{
					names.add(module.itemName(item.itemId)
						+ (item.qty > 1 ? " x" + item.qty : ""));
				}
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Missing: " + names)
					.leftColor(UiTokens.CANVAS_WARNING)
					.build());
			}

			String patches = patchLine(module.patchesAt(next.location));
			if (!patches.isEmpty())
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(patches)
					.leftColor(UiTokens.CANVAS_LOCKED)
					.build());
			}
		}

		for (FarmingRunModule.Stop stop : module.stops())
		{
			if (next != null && stop == next)
			{
				continue; // already headlined above
			}
			boolean done = module.isVisited(stop.location.id);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("· " + stop.location.name)
				.leftColor(done ? UiTokens.CANVAS_OWNED : UiTokens.CANVAS_LOCKED)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(module.visitedCount() + " of " + module.stops().size() + " done")
			.leftColor(UiTokens.CANVAS_LOCKED)
			.build());
		return super.render(graphics);
	}

	/** "Explorers ring" from the pack's enum-ish id ("Explorers_ring"). */
	static String teleportLabel(FarmRunsPack.Teleport teleport)
	{
		return teleport.id.replace('_', ' ');
	}

	/** "Herb ready · Flower empty" — live states at the stop; patches the
	 *  tracker has never seen stay silent instead of shouting unknown. */
	static String patchLine(List<FarmingRunModule.StopPatch> patches)
	{
		StringJoiner joiner = new StringJoiner(" · ");
		for (FarmingRunModule.StopPatch patch : patches)
		{
			if (patch.view == FarmingRunModule.PatchView.UNKNOWN)
			{
				continue;
			}
			joiner.add(shortCategory(patch.category.getName()) + " "
				+ patch.view.name().toLowerCase(Locale.ROOT).replace('_', ' '));
		}
		return joiner.toString();
	}

	private static String shortCategory(String name)
	{
		return name.replace(" Patches", "").replace(" Patch", "");
	}
}
