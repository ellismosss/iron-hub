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
		return get(itemId, 1, width, height);
	}

	/**
	 * The stack-count variant: the quantity is baked onto the sprite exactly
	 * as the game draws it (the loot-grid convention). Fits a box x box
	 * square preserving aspect.
	 */
	public Image getStacked(int itemId, int quantity, int box)
	{
		return get(itemId, Math.max(1, quantity), BOX_FIT, box);
	}

	private Image get(int itemId, int quantity, int width, int height)
	{
		if (itemManager == null || itemId <= 0)
		{
			return null;
		}
		long key = (long) itemId << 44 | (long) (quantity & 0xFFFFFFF) << 16
			| (width & 0xFFL) << 8 | (height & 0xFFL);
		Image ready = scaled.get(key);
		if (ready != null || !pending.add(key))
		{
			return ready; // cached, or already waiting — never stack a second listener
		}
		AsyncBufferedImage image = quantity > 1
			? itemManager.getImage(itemId, quantity, true)
			: itemManager.getImage(itemId);
		if (image == null)
		{
			pending.remove(key);
			return null;
		}
		image.onLoaded(() ->
		{
			// runs on the client thread — scale once, here, and never again.
			// Rendered into a real BufferedImage, NOT getScaledInstance: that
			// returns a lazily-produced image, and the panels draw with a null
			// observer, so its first paint started production, drew nothing,
			// and nobody repainted when the pixels landed — blank icons until
			// an unrelated repaint. Drawing a loaded BufferedImage source is
			// synchronous and complete.
			int[] dims = scaledDims(image.getWidth(), image.getHeight(), width, height);
			int w = dims[0];
			int h = dims[1];
			java.awt.image.BufferedImage result = new java.awt.image.BufferedImage(
				w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = result.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, 0, 0, w, h, null);
			g.dispose();
			SwingUtilities.invokeLater(() ->
			{
				scaled.put(key, result);
				pending.remove(key);
				onArrived.run();
			});
		});
		return null;
	}

	/**
	 * Target dimensions for a source sprite: BOX_FIT fits the square box,
	 * a negative width preserves the source aspect at the given height
	 * (getScaledInstance's -1 convention, which the pre-BufferedImage code
	 * inherited for free — regressed 2026-08-03 to 1px-wide strips), and
	 * explicit dimensions pass through.
	 */
	static int[] scaledDims(int srcW, int srcH, int width, int height)
	{
		int sw = Math.max(1, srcW);
		int sh = Math.max(1, srcH);
		int w;
		int h;
		if (width == BOX_FIT)
		{
			double s = height / (double) Math.max(sw, sh);
			w = Math.max(1, (int) Math.round(sw * s));
			h = Math.max(1, (int) Math.round(sh * s));
		}
		else if (width < 0)
		{
			h = Math.max(1, height);
			w = Math.max(1, (int) Math.round(sw * (h / (double) sh)));
		}
		else
		{
			w = Math.max(1, width);
			h = Math.max(1, height);
		}
		return new int[]{w, h};
	}
}
