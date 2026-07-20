package com.ironhub.modules.bankspace;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Amber-glows the bank items that could live in a dedicated storage —
 * the BankRestockOverlay grammar (bank only, tick-cached supplier), in
 * amber rather than green: green means "withdraw this", amber means
 * "this could move out". Empty set = off.
 */
class BankSpaceOverlay extends WidgetItemOverlay
{
	private static final Color GLOW = new Color(224, 162, 60);
	private static final Color FILL = new Color(224, 162, 60, 40);

	private final Supplier<Set<Integer>> flagged;
	private Set<Integer> cached = Set.of();
	private long cachedAtMs;

	BankSpaceOverlay(Supplier<Set<Integer>> flagged)
	{
		this.flagged = flagged;
		showOnBank();
	}

	/** renderItemOverlay runs per bank item per FRAME; the answer only
	 *  moves on tick-quantized changes — resolve once per game tick
	 *  (the BankRestockOverlay lesson from the 2026-07-17 freeze audit). */
	private Set<Integer> ids()
	{
		long now = System.currentTimeMillis();
		if (now - cachedAtMs >= 600)
		{
			cachedAtMs = now;
			cached = flagged.get();
		}
		return cached;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Set<Integer> ids = ids();
		if (ids == null || !ids.contains(itemId))
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		graphics.setColor(FILL);
		graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
		graphics.setColor(GLOW);
		graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
	}
}
