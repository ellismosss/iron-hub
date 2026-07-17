package com.ironhub.ui.osrs;

import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * The skin's bundled art, per theme. Both sets are the game's own sprites:
 * STONE's come from the wiki files the game's Interface page itself names
 * (Combat icon, Stats icon, Inventory, Worn Equipment, ...), MYSTIC's are the
 * pack's redraws of the same art.
 *
 * <p>A themed lookup FALLS BACK to the vanilla file when the pack has no
 * redraw — which is exactly how a resource pack behaves in-game: it overrides
 * the sprites it ships and everything else renders vanilla. Only when neither
 * exists does this return null, and callers must render that absence honestly.
 */
public final class OsrsIcons
{
	/**
	 * Loaded art, keyed by resource path ("mystic/tab/combat"). The set is
	 * small and fixed (bundled skin sprites), and re-decoding PNGs per call
	 * was a measured cost — every home rebuild and panel repaint used to
	 * hit ImageIO (2026-07-17 freeze audit). Optional.empty() caches the
	 * misses too, so the mystic→vanilla fallback probe stays one lookup.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String,
		java.util.Optional<java.awt.image.BufferedImage>> CACHE =
		new java.util.concurrent.ConcurrentHashMap<>();

	private OsrsIcons()
	{
	}

	/** A Character Summary stat icon (18px). */
	public static Icon stat(OsrsTheme theme, String name)
	{
		return themed(theme, name);
	}

	/** A full-size side-panel tab icon, for nav stones. */
	public static Icon tab(OsrsTheme theme, String name)
	{
		return themed(theme, "tab/" + name);
	}

	/** A dashboard nav-block icon (goals, combat, dailies, task, ...). */
	public static Icon nav(OsrsTheme theme, String name)
	{
		return themed(theme, "nav/" + name);
	}

	/**
	 * A themed sprite as a raw image, for custom painting (equipment slot
	 * tiles, panel backings). Same mystic-falls-back-to-vanilla rule; null
	 * when neither theme ships the file — callers render the absence
	 * honestly (e.g. fall back to the game's sprite cache at runtime).
	 */
	public static java.awt.image.BufferedImage image(OsrsTheme theme, String path)
	{
		if (theme == OsrsTheme.MYSTIC)
		{
			java.awt.image.BufferedImage redraw = loadImage("mystic/" + path);
			if (redraw != null)
			{
				return redraw;
			}
		}
		return loadImage(path);
	}

	private static java.awt.image.BufferedImage loadImage(String path)
	{
		return CACHE.computeIfAbsent(path, key ->
		{
			java.net.URL url = OsrsIcons.class.getResource("/data/icons/osrs/" + key + ".png");
			if (url == null)
			{
				return java.util.Optional.empty();
			}
			try
			{
				return java.util.Optional.ofNullable(javax.imageio.ImageIO.read(url));
			}
			catch (java.io.IOException e)
			{
				return java.util.Optional.empty();
			}
		}).orElse(null);
	}

	private static Icon themed(OsrsTheme theme, String path)
	{
		if (theme == OsrsTheme.MYSTIC)
		{
			Icon redraw = load("mystic/" + path);
			if (redraw != null)
			{
				return redraw;
			}
		}
		return load(path);
	}

	private static Icon load(String path)
	{
		java.awt.image.BufferedImage image = loadImage(path);
		return image == null ? null : new ImageIcon(image);
	}
}
