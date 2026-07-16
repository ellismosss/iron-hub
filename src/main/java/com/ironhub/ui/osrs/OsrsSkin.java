package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Font;
import net.runelite.client.ui.FontManager;

/**
 * Tokens for the OSRS-native "stonework" design system (design/OSRS-SKIN.md).
 * Every color was sampled from the wiki's native-1x screenshot of the
 * Character Summary interface (File:Character_Summary.png) — never eyeballed,
 * never adjusted. Skinned surfaces style from here only; UiTokens remains the
 * source for the classic RuneLite-look surfaces.
 */
public final class OsrsSkin
{
	private OsrsSkin()
	{
	}

	/** Interface backing between boxes (the game adds faint texture; we stay flat). */
	public static final Color BACKGROUND = new Color(0x3E3529);
	/** Stone box interior. */
	public static final Color BOX_FILL = new Color(0x554C41);
	/** Outer 1px line of the engraved box border. */
	public static final Color EDGE_DARK = new Color(0x2D2A22);
	/** Inner 1px line of the engraved box border. */
	public static final Color EDGE_LIGHT = new Color(0x726451);
	/** Darker recess (behind the game's tab strip). */
	public static final Color RECESS = new Color(0x28251E);

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
