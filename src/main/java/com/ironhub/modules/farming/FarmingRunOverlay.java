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
	private static final int WIDTH = 190; // within the 250×200 budget; fits mixed-run labels
	/** Cap the upcoming-stop list so a long run stays within the 200px budget. */
	private static final int MAX_UPCOMING = 6;

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
			// standing near the patch already: the teleport suggestion is noise
			// (walking wins), so say so instead of naming a teleport
			panelComponent.getChildren().add(LineComponent.builder()
				.left("> " + module.stopLabel(next))
				.leftColor(Color.WHITE)
				.right(module.nearStop(next) ? "nearby" : teleportLabel(next.teleport))
				.rightColor(module.nearStop(next) ? UiTokens.CANVAS_LOCKED : UiTokens.OVERLAY_VALUE)
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

		// Upcoming stops only (done ones are counted in the progress line), and
		// only the next few — a long run (the combo tree run is 18 stops) would
		// otherwise overflow the 200px overlay budget. The sidebar tab carries
		// the full, scrollable checklist.
		int shown = 0;
		int remaining = 0;
		for (FarmingRunModule.Stop stop : module.stops())
		{
			if (module.isVisited(stop.location.id) || stop == next)
			{
				continue; // done, or already headlined above
			}
			if (shown < MAX_UPCOMING)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("· " + module.stopLabel(stop))
					.leftColor(UiTokens.CANVAS_LOCKED)
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
				.left("  …+" + remaining + " more")
				.leftColor(UiTokens.CANVAS_LOCKED)
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
