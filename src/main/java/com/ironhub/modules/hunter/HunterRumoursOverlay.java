package com.ironhub.modules.hunter;

import com.ironhub.IronHubConfig;
import com.ironhub.data.HunterRumoursPack;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Rumour overlay (the slayer overlay's twin): target + catch progress vs
 * the pity rate, trap, the assigning hunter, the preferred hunting area
 * with its fairy code (hidden once you are standing there), the trap item
 * when it is not carried, and the piece-found return banner. Display-only,
 * inside the 250x200 budget.
 */
class HunterRumoursOverlay extends OverlayPanel
{
	private static final int WIDTH = 190;

	private final HunterRumoursModule module;
	private final IronHubConfig config;
	private final Client client;

	HunterRumoursOverlay(HunterRumoursModule module, IronHubConfig config, Client client)
	{
		this.module = module;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** Derived state resolved at most once per game tick — the outfit
	 *  canonicalStock expansions, the 55-location preferred scan and the
	 *  carried-trap sweep were re-run per rendered frame (2026-07-20
	 *  audit; the BankRestockOverlay memoize-per-tick rule). */
	private int snapshotTick = -1;
	private HunterRumoursPack.Rumour rumour;
	private PersistedState.RumourRecord active;
	private int pity;
	private boolean nearPreferred;
	private HunterRumoursPack.Location preferred;
	private String missingTrap;

	private void snapshot()
	{
		int tick = client == null ? -1 : client.getTickCount();
		if (tick == snapshotTick && tick != -1)
		{
			return;
		}
		snapshotTick = tick;
		rumour = module.currentRumour();
		active = module.active();
		pity = rumour == null ? 0 : rumour.pityFor(module.outfitPieces());
		nearPreferred = module.nearPreferredLocation();
		preferred = rumour == null ? null : module.preferredLocation(rumour);
		missingTrap = module.missingTrapItem();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		snapshot();
		if (!config.hunterOverlay() || rumour == null)
		{
			return null;
		}
		panelComponent.getChildren().clear();
		panelComponent.setBackgroundColor(UiTokens.OVERLAY_BG);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));

		int caught = active == null ? 0 : active.caught;
		boolean pieceFound = active != null && active.pieceFound;
		panelComponent.getChildren().add(LineComponent.builder()
			.left(rumour.name).leftColor(Color.WHITE)
			.right(pieceFound ? "done" : caught + "/" + pity)
			.rightColor(pieceFound ? UiTokens.OVERLAY_VALUE : UiTokens.CANVAS_LOCKED)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(rumour.trap).leftColor(UiTokens.CANVAS_LOCKED)
			.right("Lv " + rumour.level).rightColor(UiTokens.CANVAS_LOCKED)
			.build());

		if (pieceFound)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Piece found — return to the Hunter Guild!")
				.leftColor(UiTokens.OVERLAY_VALUE)
				.build());
		}
		else if (!nearPreferred)
		{
			if (preferred != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(preferred.name).leftColor(Color.YELLOW)
					.right(preferred.fairyRing == null ? "" : preferred.fairyRing)
					.rightColor(Color.YELLOW)
					.build());
			}
		}

		if (missingTrap != null && !pieceFound)
		{
			// red is earned: the trap item is verified not carried
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Bring: " + missingTrap).leftColor(UiTokens.STATUS_WARNING)
				.build());
		}
		return super.render(graphics);
	}
}
