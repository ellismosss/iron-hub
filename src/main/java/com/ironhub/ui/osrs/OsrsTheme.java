package com.ironhub.ui.osrs;

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
		new Color(0x6A5C43),  // scrollThumb: Settings scrollbar (its gradient, flattened)
		new Color(0x252019),  // scrollTrough: Settings scrollbar track
		new Color(0x372E22),  // fieldFill: Settings search field interior
		new Color(0x31281C),  // fieldEdge: Settings search field inner line
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
	 * the pinned commit. The backing was originally the pack's translucent
	 * overlay flattened over the RuneLite panel (#1D1D1D); Luke lightened it
	 * to #282828 (2026-07-17) so buttons and stone slabs pop.
	 */
	MYSTIC("Mystic (resource pack)",
		// Luke's pick (2026-07-17): a shade lighter than the pack's #1D1D1D
		// translucent-overlay result, so buttons and stone slabs pop
		new Color(0x282828),  // background
		new Color(0x222222),  // boxFill: button/middle.png
		new Color(0x141414),  // edgeDark: button/edge_top.png row 0
		new Color(0x383838),  // edgeLight: button/edge_top.png row 1
		new Color(0x141414),  // recess: overrides.toml progress border outer
		new Color(0x2A2A2A),  // hoverFill: tab/small_middle_hovered.png (gradient top, flattened)
		new Color(0x1B1B1B),  // pressFill: DERIVED (boxFill -7, mirrors the hover lift)
		new Color(0x2F2F2F),  // selectFill: resizeable_mode/tab_stone_middle_selected.png
		new Color(0x7C7C7C),  // selectEdge: tab_stone_middle_selected.png bevel
		new Color(0x65C772),  // checkMark: options/square_check_box_checked.png
		new Color(0x2B2B2B),  // scrollThumb: scrollbar/thumb_middle.png
		new Color(0x141414),  // scrollTrough: overrides.toml's universal dark backing
		new Color(0x141414),  // fieldFill: overrides.toml item_search.background
		new Color(0x232323),  // fieldEdge: overrides.toml dropdown.border.inner
		new String[]{
			"BBBBDD",
			"BBBDLL",
			"BBBDLF",
			"BDDLLF",
			"DLLLFF",
			"DLFFFF",
		}),

	/**
	 * Dark Vanilla — vanilla's own shapes in dark grey (Luke curated the pack
	 * into the sprite folder on 2026-07-25: "I wanted a dark-mode that looked
	 * exactly like Vanilla"). It keeps vanilla's 7x7 corner geometry, which is
	 * why the stamp below is STONE's: the pack re-colours the art, it does not
	 * redraw it.
	 *
	 * <p>Colours are SAMPLED from the pack's own sprites where it ships one.
	 * Where it doesn't, they are derived by the pack's OWN measured transform
	 * rather than picked: across the 88 sprites that exist in both vanilla and
	 * dark, the pack keeps a median 0.645 of vanilla's luminance and strips
	 * chroma almost entirely (mean 20.7 -> 3.9). Each derived line below is
	 * vanilla's token greyscaled and scaled by that factor, and says so.
	 *
	 * <p>Two of the pack's choices are worth not "fixing": hover is DARKER
	 * than rest (#2E2E2E under #424242), the opposite of vanilla's lift, and
	 * the checkbox tick stays vanilla's #00FF00 green — measured pixel for
	 * pixel against the vanilla sprite.
	 */
	DARK("Dark vanilla (resource pack)",
		new Color(0x232323),  // background: DERIVED from vanilla #3E3529
		new Color(0x424242),  // boxFill: buttons/button_dark.png interior
		new Color(0x000000),  // edgeDark: button_dark.png outer line
		new Color(0x595959),  // edgeLight: button_dark.png inner line
		new Color(0x181818),  // recess: DERIVED from vanilla #28251E
		new Color(0x2E2E2E),  // hoverFill: button_hovered_dark.png (the pack darkens)
		new Color(0x262626),  // pressFill: DERIVED past hover, in the pack's own direction
		new Color(0x575757),  // selectFill: DERIVED (boxFill +21, vanilla's relationship)
		new Color(0x525252),  // selectEdge: DERIVED from vanilla #877F6C
		new Color(0x00FE00),  // checkMark: square_bordered_checkbox_checked_dark.png
		new Color(0x3C3C3C),  // scrollThumb: DERIVED from vanilla #6A5C43
		new Color(0x151515),  // scrollTrough: DERIVED from vanilla #252019
		new Color(0x1F1F1F),  // fieldFill: DERIVED from vanilla #372E22
		new Color(0x1B1B1B),  // fieldEdge: DERIVED from vanilla #31281C
		new String[]{
			"BBBBBDD",
			"BBBBBDL",
			"BBBBBDL",
			"BBBBDDL",
			"BBBDDLL",
			"DDDDLLF",
			"DLLLLFF",
		});

	private final String displayName;

	/**
	 * The filename variant this theme's V2 art carries — {@code "vanilla"}
	 * for the base sprite, otherwise the {@code _<name>} suffix beside it.
	 * Derived from the enum name so a new theme needs no extra field, and
	 * consumed by {@code V2Sprites}, which falls back to vanilla for any
	 * sprite the pack doesn't re-skin — exactly how a resource pack behaves
	 * in game.
	 */
	public String spriteVariant()
	{
		return this == STONE ? "vanilla" : name().toLowerCase(java.util.Locale.ROOT);
	}

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
	/** Scrollbar thumb interior. */
	public final Color scrollThumb;
	/** Scrollbar track. */
	public final Color scrollTrough;
	/** Text field / dropdown interior — sunken, like the game's own. */
	public final Color fieldFill;
	/**
	 * The field's inner line. Its own token because a field is a sunken well,
	 * not an engraved box: the game steps from a dark outline through a mid
	 * tone into the interior, and MYSTIC's interior IS its edgeDark, so
	 * reusing the box tokens would leave its fields borderless.
	 */
	public final Color fieldEdge;
	/**
	 * Top-left corner pixel stamp, mirrored to the other three corners.
	 * B = outside, D = dark line, L = light line, F = box fill.
	 */
	public final String[] cornerStamp;

	OsrsTheme(String displayName, Color background, Color boxFill, Color edgeDark, Color edgeLight,
		Color recess, Color hoverFill, Color pressFill, Color selectFill, Color selectEdge,
		Color checkMark, Color scrollThumb, Color scrollTrough, Color fieldFill, Color fieldEdge,
		String[] cornerStamp)
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
		this.scrollThumb = scrollThumb;
		this.scrollTrough = scrollTrough;
		this.fieldFill = fieldFill;
		this.fieldEdge = fieldEdge;
		this.cornerStamp = cornerStamp;
	}


	@Override
	public String toString()
	{
		return displayName;
	}
}
