package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Font;
import net.runelite.client.ui.FontManager;

/**
 * Global tokens for the OSRS-native "stonework" design system
 * (design/OSRS-SKIN.md): text colors and fonts, which resource packs do not
 * touch — the game renders interface text identically under any pack. Surface
 * colors live per-clothing on {@link OsrsTheme}.
 *
 * <p>Every color was sampled from the wiki's native-1x screenshot of the
 * Character Summary interface (File:Character_Summary.png) — never eyeballed,
 * never adjusted. Skinned surfaces style from here only; UiTokens remains the
 * source for the classic RuneLite-look surfaces.
 */
public final class OsrsSkin
{
	private OsrsSkin()
	{
	}

	/** Header orange — equal to JagexColors.DARK_ORANGE_INTERFACE_TEXT. */
	public static final Color TITLE = new Color(0xFF981F);
	/** Stat label orange ("Combat Level:"). */
	public static final Color LABEL = new Color(0xFF9933);
	/** Stat value green. */
	public static final Color VALUE = new Color(0x0DC10D);
	/** Every string is drawn twice: this at +1,+1, then its color on top. */
	public static final Color TEXT_SHADOW = Color.BLACK;
	/** Secondary/annotation text (not sampled — a muted stone tone). */
	public static final Color MUTED = new Color(0xB8AC9C);
	/** Text drawn over a progress fill, per RuneLite's own ProgressBar. */
	public static final Color BAR_TEXT = Color.WHITE;

	/** The game's own font at its native 16px — pixel-identical to in-game text. */
	public static Font font()
	{
		return FontManager.getRunescapeFont();
	}

	public static Font boldFont()
	{
		return FontManager.getRunescapeBoldFont();
	}
}
