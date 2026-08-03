package com.ironhub.ui;

import com.ironhub.ui.components.SpriteCache;
import java.awt.image.BufferedImage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNull;

public class SpriteCacheTest
{
	private AsyncBufferedImage image()
	{
		return new AsyncBufferedImage(Mockito.mock(ClientThread.class), 36, 32,
			BufferedImage.TYPE_INT_ARGB);
	}

	/**
	 * The invariant that keeps the client thread out of trouble at login.
	 *
	 * <p>ItemManager returns ONE shared AsyncBufferedImage per item, and every
	 * onLoaded() call on an unresolved one appends to an unbounded listener
	 * list. Sprites cannot resolve at the login screen at all, so a panel that
	 * re-requests on every rebuild stacks listeners there for as long as it
	 * sits — and they all run at once, on the client thread, the moment the
	 * game state advances. Ask once, however many times the tab rebuilds.
	 */
	@Test
	public void asksForEachSpriteOnceHoweverManyRebuilds()
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		Mockito.when(itemManager.getImage(995)).thenReturn(image());
		Mockito.when(itemManager.getImage(1391)).thenReturn(image());
		SpriteCache cache = new SpriteCache(itemManager, () ->
		{
		});

		for (int rebuild = 0; rebuild < 50; rebuild++)
		{
			assertNull("unresolved sprite reads as absent", cache.get(995, 24));
			assertNull(cache.get(1391, 24));
		}

		Mockito.verify(itemManager, Mockito.times(1)).getImage(995);
		Mockito.verify(itemManager, Mockito.times(1)).getImage(1391);
		Mockito.verifyNoMoreInteractions(itemManager);
	}

	/**
	 * The cached sprite must be a fully realised BufferedImage. The old
	 * getScaledInstance result was a lazily-produced image: every consumer
	 * draws with a null observer, so the first paint started production,
	 * drew nothing, and no repaint fired when the pixels landed — blank
	 * icons until an unrelated repaint.
	 */
	@Test
	public void cachedSpriteIsAFullyRealisedImage() throws Exception
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		AsyncBufferedImage image = image();
		Mockito.when(itemManager.getImage(995)).thenReturn(image);
		SpriteCache cache = new SpriteCache(itemManager, () ->
		{
		});

		assertNull(cache.get(995, 24));
		image.loaded(); // resolves — the cache scales eagerly, here
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});

		java.awt.Image cached = cache.get(995, 24);
		org.junit.Assert.assertTrue("scaled sprite must be a real BufferedImage",
			cached instanceof BufferedImage);
		org.junit.Assert.assertEquals(24, ((BufferedImage) cached).getWidth());
		org.junit.Assert.assertEquals(24, ((BufferedImage) cached).getHeight());
	}

	/** Headless tests and a client with no ItemManager: no icon, no crash. */
	@Test
	public void toleratesNoItemManagerAndNoIcon()
	{
		assertNull(new SpriteCache(null, () ->
		{
		}).get(995, 24));

		ItemManager itemManager = Mockito.mock(ItemManager.class);
		SpriteCache cache = new SpriteCache(itemManager, () ->
		{
		});
		assertNull("id 0 means we have no icon — never ask for one", cache.get(0, 24));
		Mockito.verifyNoInteractions(itemManager);
	}
}
