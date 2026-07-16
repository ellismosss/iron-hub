package com.ironhub.ui.osrs;

import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * The skin's bundled art, per theme. Both sets are the game's own sprites:
 * STONE's come from the wiki files the game's Interface page itself names
 * (Combat icon, Stats icon, Inventory, Worn Equipment, ...), MYSTIC's are the
 * pack's redraws of the same tabs. Missing art returns null — callers must
 * render the absence honestly rather than substitute.
 */
public final class OsrsIcons
{
	private OsrsIcons()
	{
	}

	/** A Character Summary stat icon (18px). */
	public static Icon stat(OsrsTheme theme, String name)
	{
		return load(prefix(theme) + name);
	}

	/** A full-size side-panel tab icon, for nav stones. */
	public static Icon tab(OsrsTheme theme, String name)
	{
		return load(prefix(theme) + "tab/" + name);
	}

	private static String prefix(OsrsTheme theme)
	{
		return theme == OsrsTheme.MYSTIC ? "mystic/" : "";
	}

	private static Icon load(String path)
	{
		java.net.URL url = OsrsIcons.class.getResource("/data/icons/osrs/" + path + ".png");
		return url == null ? null : new ImageIcon(url);
	}
}
