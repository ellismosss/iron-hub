package com.ironhub.ui.components;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Green-glows the items a run's saved setup still needs, in the BANK only
 * (same colours as Loadout Lab's BankHighlightOverlay, which also glows the
 * inventory — right for its gear sets, wrong for a restock aid: once the
 * item is withdrawn the glow's job is done, and an inventory glow outlives
 * the bank interface). The id set is pre-expanded with variants; empty
 * means off.
 */
public class BankRestockOverlay extends WidgetItemOverlay
{
	private static final Color GLOW = new Color(140, 200, 140);
	private static final Color FILL = new Color(140, 200, 140, 40);

	private final Supplier<Set<Integer>> highlighted;

	public BankRestockOverlay(Supplier<Set<Integer>> highlighted)
	{
		this.highlighted = highlighted;
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Set<Integer> ids = highlighted.get();
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
