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
	/** Secondary/annotation text, and typed input (not sampled — a muted stone tone). */
	public static final Color MUTED = new Color(0xB8AC9C);
	/**
	 * Placeholder text. DERIVED: the game has no placeholders to sample, so
	 * this dims MUTED by the same ratio UiTokens uses from body to faint —
	 * it must stay distinguishable from typed text, which is MUTED.
	 */
	public static final Color FAINT = new Color(0x645E55);
	/** Text drawn over a progress fill, per RuneLite's own ProgressBar. */
	public static final Color BAR_TEXT = Color.WHITE;
	/**
	 * Bar trough — Luke's value, and theme-independent like the rest of the
	 * bar: these follow RuneLite's ProgressBar rather than game art (the game
	 * draws no text-in-bar), and its own XP-tracker trough is #3D3831. Each
	 * theme's sunken `recess` swallowed the bar against the backing.
	 */
	public static final Color BAR_TROUGH = new Color(0x3E3830);

	/**
	 * Stop a Swing text component antialiasing the pixel font.
	 *
	 * <p>Setting the hint on the Graphics is NOT enough: Swing's own text
	 * painting re-applies the look-and-feel's antialias setting over
	 * whatever the Graphics carries, which smeared the RuneScape font with
	 * LCD subpixel fringes (measured: red #581313 / blue #13134C dabs around
	 * every glyph). This client property is the one Swing reads per
	 * component, so it wins. Every skinned surface that lets SWING draw its
	 * text — fields, combo boxes, renderers — must call this; atoms that
	 * paint their own strings set the Graphics hint instead.
	 */
	public static <T extends javax.swing.JComponent> T crisp(T component)
	{
		component.putClientProperty(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		return component;
	}

	/**
	 * A 1px rectangle outline as four FILLED strips — never drawRect.
	 *
	 * <p>drawRect strokes a path, and a stroke is centred ON the path: under
	 * a scaling transform (macOS Retina paints Swing at 2x) the top/left
	 * lines render half off-canvas at 1 device pixel while the bottom/right
	 * lines sit a device pixel short of the edge — the whole border reads as
	 * shifted up-left (Luke, in-client 2026-07-16: "needs to move down 1px
	 * and right 1px"). Filled areas cover exact pixels at any scale.
	 */
	public static void outline(java.awt.Graphics g, Color color, int x, int y, int w, int h)
	{
		g.setColor(color);
		g.fillRect(x, y, w, 1);
		g.fillRect(x, y + h - 1, w, 1);
		g.fillRect(x, y + 1, 1, h - 2);
		g.fillRect(x + w - 1, y + 1, 1, h - 2);
	}

	/** The game's own font at its native 16px — pixel-identical to in-game text. */
	public static Font font()
	{
		return FontManager.getRunescapeFont();
	}

	public static Font boldFont()
	{
		return FontManager.getRunescapeBoldFont();
	}

	/**
	 * The game's small font — its own, used in-game for tight spaces. Only
	 * for surfaces where Luke asked for smaller text (bar interiors); the
	 * 14px panel floor still governs every classic surface.
	 */
	public static Font smallFont()
	{
		return FontManager.getRunescapeSmallFont();
	}
}
