package com.ironhub.ui.components;

import java.awt.Image;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Item sprites for panel icons, fetched exactly once per item id and size.
 *
 * <p>Panels must not ask {@link ItemManager} for a sprite on every rebuild.
 * ItemManager hands back one shared {@link AsyncBufferedImage} per item, and
 * {@code onLoaded} either appends to an unbounded listener list (while the
 * sprite is unresolved) or queues a runnable on the client thread (once it is).
 * A tab that re-requests on each rebuild therefore piles work onto the client
 * thread — and at the login screen, where sprites cannot resolve at all
 * (ItemManager bails until the game state reaches LOGIN_SCREEN and the item
 * sprite exists), the listeners simply stack up. The instant you log in, every
 * one of them runs at once, on the client thread, mid-login: exactly the wrong
 * moment for a burst of slow SCALE_SMOOTH work.
 *
 * <p>So: one request per icon for the life of the tab, the scaled result kept,
 * and a repaint when something new arrives. Null-safe for headless tests, where
 * there is no ItemManager and icons simply never appear.
 */
public class SpriteCache
{
	private final ItemManager itemManager; // null in headless tests
	private final Runnable onArrived;

	private final Map<Long, Image> scaled = new HashMap<>();
	private final Set<Long> pending = new HashSet<>();

	/**
	 * @param onArrived run on the EDT when a newly loaded sprite is cached —
	 *                  the owning tab's rebuild/repaint, so it can pick it up.
	 */
	public SpriteCache(ItemManager itemManager, Runnable onArrived)
	{
		this.itemManager = itemManager;
		this.onArrived = onArrived;
	}

	/**
	 * The sprite scaled to {@code size}x{@code size}, or null if it isn't
	 * ready — in which case it is requested once and {@code onArrived} fires
	 * when it lands. Call from the EDT.
	 */
	public Image get(int itemId, int size)
	{
		return get(itemId, size, size);
	}

	/** Scaled to FIT a box x box square preserving aspect (the money-making
	 *  icon rule: never height-only, wide sprites shrink to the box). */
	public Image getBox(int itemId, int box)
	{
		return get(itemId, BOX_FIT, box);
	}

	private static final int BOX_FIT = 0xFFFE; // width sentinel for getBox

	/** As above with explicit dimensions; width -1 preserves the sprite's
	 *  36x32 aspect (the row-icon convention across the module tabs). */
	public Image get(int itemId, int width, int height)
	{
		if (itemManager == null || itemId <= 0)
		{
			return null;
		}
		long key = (long) itemId << 32 | (width & 0xFFFFL) << 16 | (height & 0xFFFFL);
		Image ready = scaled.get(key);
		if (ready != null || !pending.add(key))
		{
			return ready; // cached, or already waiting — never stack a second listener
		}
		AsyncBufferedImage image = itemManager.getImage(itemId);
		if (image == null)
		{
			pending.remove(key);
			return null;
		}
		image.onLoaded(() ->
		{
			// runs on the client thread — scale once, here, and never again
			Image result;
			if (width == BOX_FIT)
			{
				int w = Math.max(1, image.getWidth());
				int h = Math.max(1, image.getHeight());
				double s = height / (double) Math.max(w, h);
				result = image.getScaledInstance(
					Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s)),
					Image.SCALE_SMOOTH);
			}
			else
			{
				result = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
			}
			SwingUtilities.invokeLater(() ->
			{
				scaled.put(key, result);
				pending.remove(key);
				onArrived.run();
			});
		});
		return null;
	}
}
