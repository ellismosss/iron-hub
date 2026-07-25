package com.ironhub.modules.designlab;

import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Inventory;
import java.awt.image.BufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The inventory actually receives its item images.
 *
 * <p>This exists because the first two attempts did not, and neither the
 * render test nor the rules test could tell: with no client there is no
 * ItemManager, so 28 empty slots look exactly like 28 correct ones. The bug
 * was that {@code SpriteCache.get} returns null on a miss and fetches in the
 * background, while the arrival callback only repainted — so the slots held
 * the same 28 nulls forever. A repaint is not a re-ask.
 */
public class DesignLabInventoryTest
{
	@Test
	public void everySlotGetsAnImageOnceItemManagerHasThem() throws Exception
	{
		// an ItemManager whose images are "already loaded": onLoaded runs its
		// callback immediately, which is what the real one does for a cached
		// item and is the path SpriteCache is built around
		ItemManager items = mock(ItemManager.class);
		when(items.getImage(anyInt())).thenAnswer(call ->
		{
			// a ClientThread that runs whatever Runnable it is handed, inline.
			// A bare mock() swallows it, and then AsyncBufferedImage's loaded
			// callback never fires — which looks exactly like the bug under
			// test rather than like a broken fixture.
			net.runelite.client.callback.ClientThread clientThread = mock(
				net.runelite.client.callback.ClientThread.class, onClientThread ->
				{
					for (Object argument : onClientThread.getArguments())
					{
						if (argument instanceof Runnable)
						{
							((Runnable) argument).run();
						}
					}
					return null;
				});
			AsyncBufferedImage image = new AsyncBufferedImage(clientThread, 36, 32,
				BufferedImage.TYPE_INT_ARGB);
			// a solid 36x32 block, not a blank one: the diagnostic render is
			// meant to show where the cells LAND, and transparent images make
			// a correct grid and a broken one look identical
			java.awt.Graphics2D cell = image.createGraphics();
			cell.setColor(java.awt.Color.WHITE);
			cell.fillRect(0, 0, 36, 32);
			cell.dispose();
			image.loaded();
			return image;
		});

		DesignLabV2Tab[] built = new DesignLabV2Tab[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			built[0] = new DesignLabV2Tab(OsrsTheme.STONE, items));
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});

		V2Inventory inventory = find(built[0]);
		assertNotNull("the gallery must contain the inventory", inventory);
		for (int slot = 0; slot < V2Inventory.SLOTS; slot++)
		{
			assertNotNull("slot " + slot + " has no item image", inventory.item(slot));
		}

		// a filled inventory, rendered — the only way to see the grid, since
		// headless it is otherwise 28 empty slots
		inventory.setSize(inventory.getPreferredSize());
		BufferedImage shot = com.ironhub.ui.SwingRender.render(inventory);
		java.io.File out = new java.io.File("build/reports/v2-inventory-filled.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(shot, "png", out);
	}

	/**
	 * Every slot's item sits inside the frame. The bottom row used to hang
	 * over the frame's own bottom band (Luke, 2026-07-25: "falling off the
	 * low-edge"), which a render with empty slots can never show.
	 */
	@Test
	public void theGridIsCentredInTheGamesOwnPanel() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			V2Inventory inventory = new V2Inventory(theme);
			assertEquals("the panel is the game's own size", 190, V2Inventory.WIDTH);
			assertEquals(276, V2Inventory.HEIGHT);

			// margins, measured from the frame's own inner edge — the grid is
			// centred, so opposite sides match within the odd pixel. Luke's
			// "falling off the low-edge" was a 2px top against a 3px OVERHANG.
			int leftGap = inventory.slotX(0)
				- (V2Inventory.WIDTH - inventory.innerRight());
			int rightGap = inventory.innerRight()
				- (inventory.slotX(V2Inventory.COLUMNS - 1) + V2Inventory.CELL_W);
			int topGap = inventory.slotY(0)
				- (V2Inventory.HEIGHT - inventory.innerBottom());
			int bottomGap = inventory.innerBottom()
				- (inventory.slotY(V2Inventory.SLOTS - 1) + V2Inventory.CELL_H);

			assertTrue(theme + " sides differ: " + leftGap + " vs " + rightGap,
				Math.abs(leftGap - rightGap) <= 1);
			assertTrue(theme + " top/bottom differ: " + topGap + " vs " + bottomGap,
				Math.abs(topGap - bottomGap) <= 1);
			// there must be actual AIR, not merely no overhang
			assertTrue(theme + " has no padding above the grid: " + topGap, topGap >= 4);
			assertTrue(theme + " has no padding below the grid: " + bottomGap, bottomGap >= 4);
		}
	}

	/**
	 * The spacings, pinned. These are the game's own and the reason the grid
	 * looks the way it does; a change to any of them is a change to something
	 * measured, not a tweak.
	 */
	@Test
	public void theSpacingsAreTheGamesOwn()
	{
		V2Inventory inventory = new V2Inventory(OsrsTheme.STONE);
		assertEquals("cell width", 36, V2Inventory.CELL_W);
		assertEquals("cell height", 32, V2Inventory.CELL_H);
		// pitch minus cell — the gap a player actually sees between items
		assertEquals("gap between columns", 6, inventory.slotX(1) - inventory.slotX(0)
			- V2Inventory.CELL_W);
		assertEquals("gap between rows", 4,
			inventory.slotY(V2Inventory.COLUMNS) - inventory.slotY(0) - V2Inventory.CELL_H);
	}

	/** With no client there is no ItemManager, and the page must still build. */
	@Test
	public void headlessLeavesTheSlotsEmptyWithoutFailing() throws Exception
	{
		DesignLabV2Tab[] built = new DesignLabV2Tab[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
			built[0] = new DesignLabV2Tab(OsrsTheme.STONE, null));
		assertEquals(null, find(built[0]).item(0));
	}

	private static V2Inventory find(java.awt.Container root)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof V2Inventory)
			{
				return (V2Inventory) child;
			}
			if (child instanceof java.awt.Container)
			{
				V2Inventory found = find((java.awt.Container) child);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}
}
