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
		java.net.URL url = OsrsIcons.class.getResource("/data/icons/osrs/" + path + ".png");
		return url == null ? null : new ImageIcon(url);
	}
}
