package com.ironhub.modules.farming;

import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Farm run overlay (frame 3b): the essentials only — run + timer, the
 * current stop with its teleport and any teleport items you're missing,
 * that stop's patch state (this run's category only, e.g. just the herb
 * patch), a progress + XP/herbs line, then the ordered stop checklist.
 * Shown only during a run; display-only ("&gt;" = next, "·" done/upcoming).
 */
class FarmingRunOverlay extends OverlayPanel
{
	private static final int WIDTH = 165; // within the 250×200 budget

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
			.left(module.runName()).leftColor(Color.WHITE)
			.right(FarmingRunModule.formatDuration(module.elapsedMs()))
			.rightColor(UiTokens.OVERLAY_VALUE)
			.build());

		FarmingRunModule.Stop next = module.nextStop();
		if (next != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("> " + module.stopLabel(next))
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

			String patch = patchState(module.patchesAt(next.location),
				FarmingRunModule.categoryTab(next.location.category));
			if (patch != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Patch").leftColor(UiTokens.CANVAS_LOCKED)
					.right(patch).rightColor(UiTokens.CANVAS_LOCKED)
					.build());
			}
		}

		StringJoiner progress = new StringJoiner(" · ");
		progress.add(module.visitedCount() + "/" + module.stops().size());
		if (module.farmingXpGained() > 0)
		{
			progress.add("+" + String.format(Locale.ROOT, "%,d", module.farmingXpGained()) + " xp");
		}
		if (module.herbsHarvested() > 0)
		{
			progress.add(module.herbsHarvested() + " herbs");
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(progress.toString()).leftColor(UiTokens.OVERLAY_VALUE)
			.build());

		for (FarmingRunModule.Stop stop : module.stops())
		{
			if (next != null && stop == next)
			{
				continue; // already headlined above
			}
			boolean done = module.isVisited(stop.location.id);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("· " + module.stopLabel(stop))
				.leftColor(done ? UiTokens.CANVAS_OWNED : UiTokens.CANVAS_LOCKED)
				.build());
		}
		return super.render(graphics);
	}

	/** "Explorers ring" from the pack's enum-ish id ("Explorers_ring"). */
	static String teleportLabel(FarmRunsPack.Teleport teleport)
	{
		return teleport.id.replace('_', ' ');
	}

	/** This run's patch state at a stop (e.g. "growing") — only the run's
	 *  own category, so a herb run never lists flower/allotment patches.
	 *  Null when unseen or the category isn't tracked here. */
	static String patchState(List<FarmingRunModule.StopPatch> patches, Tab category)
	{
		if (category == null)
		{
			return null;
		}
		for (FarmingRunModule.StopPatch patch : patches)
		{
			if (patch.category == category && patch.view != FarmingRunModule.PatchView.UNKNOWN)
			{
				return patch.view.name().toLowerCase(Locale.ROOT).replace('_', ' ');
			}
		}
		return null;
	}
}
