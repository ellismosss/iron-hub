package com.ironhub.ui.osrs;

import com.ironhub.ui.UiTokens;
import java.awt.Color;

/**
 * One stone "clothing" for the skin: the surface colors plus the corner notch
 * stamp (themes differ in geometry, not just palette — vanilla's notch is 7x7,
 * Mystic's 6x6). Text colors and fonts stay global on OsrsSkin: resource packs
 * re-sprite the interface, but the game's text rendering is unchanged.
 *
 * <p>Every value is sampled from source art at native 1x — the wiki's
 * File:Character_Summary.png and File:Settings_interface.png for STONE, the
 * Mystic pack's own sprite PNGs for MYSTIC (design/OSRS-SKIN.md records which
 * file each came from). The three exceptions are noted per-field: the game has
 * no pointer, so hover/press states have no art to sample and are derived.
 */
public enum OsrsTheme
{
	/** The vanilla fixed-mode stone of the Character Summary. */
	STONE("OSRS stone",
		new Color(0x3E3529),  // background: interface backing
		new Color(0x554C41),  // boxFill: stone box interior
		new Color(0x2D2A22),  // edgeDark: outer engraved line
		new Color(0x726451),  // edgeLight: inner engraved line
		new Color(0x28251E),  // recess: behind the tab strip / bar troughs
		new Color(0x5D5449),  // hoverFill: DERIVED (boxFill +8)
		new Color(0x4D4439),  // pressFill: DERIVED (boxFill -8)
		new Color(0x6A6053),  // selectFill: DERIVED (boxFill +21, the tab-strip lift)
		new Color(0x877F6C),  // selectEdge: outer window frame's brightest stone
		new Color(0x00FE00),  // checkMark: Settings interface checkbox green
		new String[]{
			"BBBBBDD",
			"BBBBBDL",
			"BBBBBDL",
			"BBBBDDL",
			"BBBDDLL",
			"DDDDLLF",
			"DLLLLFF",
		}),

	/**
	 * The Mystic resource pack's grey re-skin (Drunken Monk;
	 * licenses/mystic-pack-LICENSE) — sampled from the pack's own sprites at
	 * the pinned commit. The backing is the pack's resizable-mode translucent
	 * overlay composited over the RuneLite panel the skin sits on, the same
	 * way the game composites it over the world.
	 */
	MYSTIC("Mystic (resource pack)",
		overlay(0xBA, new Color(0x1A1A1A), UiTokens.PANEL_BG), // background -> #1D1D1D
		new Color(0x222222),  // boxFill: button/middle.png
		new Color(0x141414),  // edgeDark: button/edge_top.png row 0
		new Color(0x383838),  // edgeLight: button/edge_top.png row 1
		new Color(0x141414),  // recess: overrides.toml progress border outer
		new Color(0x2A2A2A),  // hoverFill: tab/small_middle_hovered.png (gradient top, flattened)
		new Color(0x1B1B1B),  // pressFill: DERIVED (boxFill -7, mirrors the hover lift)
		new Color(0x2F2F2F),  // selectFill: resizeable_mode/tab_stone_middle_selected.png
		new Color(0x7C7C7C),  // selectEdge: tab_stone_middle_selected.png bevel
		new Color(0x65C772),  // checkMark: options/square_check_box_checked.png
		new String[]{
			"BBBBDD",
			"BBBDLL",
			"BBBDLF",
			"BDDLLF",
			"DLLLFF",
			"DLFFFF",
		});

	private final String displayName;
	/** Interface backing between boxes. */
	public final Color background;
	/** Stone box interior. */
	public final Color boxFill;
	/** Outer 1px line of the engraved box border. */
	public final Color edgeDark;
	/** Inner 1px line of the engraved box border. */
	public final Color edgeLight;
	/** Sunken surface: bar troughs, unselected tabs, checkbox interiors. */
	public final Color recess;
	/** Box interior under the pointer. */
	public final Color hoverFill;
	/** Box interior while held down. */
	public final Color pressFill;
	/** Box interior when selected. */
	public final Color selectFill;
	/** Bevel line when selected — the game's "this one is active" signal. */
	public final Color selectEdge;
	/** Checkbox tick. */
	public final Color checkMark;
	/**
	 * Top-left corner pixel stamp, mirrored to the other three corners.
	 * B = outside, D = dark line, L = light line, F = box fill.
	 */
	public final String[] cornerStamp;

	OsrsTheme(String displayName, Color background, Color boxFill, Color edgeDark, Color edgeLight,
		Color recess, Color hoverFill, Color pressFill, Color selectFill, Color selectEdge,
		Color checkMark, String[] cornerStamp)
	{
		this.displayName = displayName;
		this.background = background;
		this.boxFill = boxFill;
		this.edgeDark = edgeDark;
		this.edgeLight = edgeLight;
		this.recess = recess;
		this.hoverFill = hoverFill;
		this.pressFill = pressFill;
		this.selectFill = selectFill;
		this.selectEdge = selectEdge;
		this.checkMark = checkMark;
		this.cornerStamp = cornerStamp;
	}

	/** Flatten a translucent overlay onto what it sits over (alpha 0-255). */
	private static Color overlay(int alpha, Color over, Color under)
	{
		return new Color(
			(over.getRed() * alpha + under.getRed() * (255 - alpha)) / 255,
			(over.getGreen() * alpha + under.getGreen() * (255 - alpha)) / 255,
			(over.getBlue() * alpha + under.getBlue() * (255 - alpha)) / 255);
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
