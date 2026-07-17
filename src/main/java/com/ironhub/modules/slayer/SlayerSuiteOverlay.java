package com.ironhub.modules.slayer;

import com.ironhub.IronHubConfig;
import com.ironhub.data.SlayerTasksPack;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * The slayer task overlay (frame 3f grown into the suite): task + remaining,
 * kill progress, master/area, live task stats, a superior-spawn flash, the
 * Turael kill spot with teleports (hidden once you stand in it) or the
 * preferred location, required items you are not carrying, and the skip-list
 * advisory. Display-only, inside the 250x200 budget.
 */
class SlayerSuiteOverlay extends OverlayPanel
{
	private static final int WIDTH = 190;
	private static final int MAX_TELEPORTS = 2;

	private final SlayerOptimizerModule module;
	private final IronHubConfig config;
	private final Client client;

	SlayerSuiteOverlay(SlayerOptimizerModule module, IronHubConfig config, Client client)
	{
		this.module = module;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		int remaining = module.remaining();
		if (!config.slayerOverlay() || remaining <= 0)
		{
			return null;
		}
		panelComponent.getChildren().clear();
		panelComponent.setBackgroundColor(UiTokens.OVERLAY_BG);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));

		String task = module.taskName();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(task.isEmpty() ? "Slayer task" : task).leftColor(Color.WHITE)
			.right(remaining + " left").rightColor(UiTokens.OVERLAY_VALUE)
			.build());

		int assigned = module.initialAmount();
		String master = module.masterName();
		String area = module.areaName();
		String meta = (master.isEmpty() ? "" : master)
			+ (area.isEmpty() ? "" : (master.isEmpty() ? "" : " · ") + area);
		if (!meta.isEmpty() || assigned >= remaining)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(meta).leftColor(UiTokens.CANVAS_LOCKED)
				.right(assigned >= remaining && assigned > 0
					? (assigned - remaining) + "/" + assigned : "")
				.rightColor(UiTokens.CANVAS_LOCKED)
				.build());
		}

		PersistedState.SlayerTaskRecord active = module.activeRecord();
		if (active != null && (active.xpGained > 0 || active.lootValue > 0))
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(SlayerTab.taskStatsLine(active, System.currentTimeMillis()))
				.leftColor(UiTokens.CANVAS_LOCKED)
				.build());
		}

		if (module.superiorRecentlySeen())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Superior foe spawned!").leftColor(UiTokens.STATUS_WARNING)
				.build());
		}

		locationLines(task);

		List<String> missing = module.missingBring();
		if (!missing.isEmpty())
		{
			// red is earned: these are required items verified not carried
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Bring: " + String.join(", ", missing))
				.leftColor(UiTokens.STATUS_WARNING)
				.build());
		}

		if (module.onSkipList())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("On your skip list — 30 pts").leftColor(UiTokens.CANVAS_AVAILABLE)
				.build());
		}
		return super.render(graphics);
	}

	/** Turael spot + teleports (suppressed once in the kill area), else the
	 *  preferred/first pack location by name. */
	private void locationLines(String task)
	{
		SlayerTasksPack pack = module.pack();
		SlayerTasksPack.Task entry = pack == null ? null : pack.task(task);
		if (entry == null)
		{
			return;
		}
		if (entry.turael != null)
		{
			if (module.inTuraelArea())
			{
				return; // you're there — stop navigating
			}
			for (SlayerTasksPack.TuraelLocation location : entry.turael.locations)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(location.name).leftColor(Color.YELLOW)
					.build());
				int shown = 0;
				for (String teleport : location.teleports)
				{
					if (shown++ >= MAX_TELEPORTS)
					{
						break;
					}
					panelComponent.getChildren().add(LineComponent.builder()
						.left("- " + teleport).leftColor(Color.WHITE)
						.build());
				}
			}
			if (entry.turael.note != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(entry.turael.note).leftColor(UiTokens.CANVAS_AVAILABLE)
					.build());
			}
			return;
		}
		if (entry.locations == null || entry.locations.isEmpty())
		{
			return;
		}
		String preferred = module.preferredLocationName(entry);
		if (preferred != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(preferred).leftColor(Color.YELLOW)
				.build());
		}
	}
}
