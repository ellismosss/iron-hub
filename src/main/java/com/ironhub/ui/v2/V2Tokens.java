package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Color;
import java.awt.Font;

/**
 * Every size, space, font and colour the V2 system uses — and the only place
 * any of them may be written. design/DESIGN-SYSTEM-V2.md is the prose; this
 * is the machine-readable half, and {@code V2RulesTest} fails the build on a
 * literal that bypasses it.
 *
 * <p>The status colours are SAMPLED from Luke's own curated sprites rather
 * than picked: green is the ink of {@code ui/ticks/checkmark_small}, red is
 * the ink of {@code ui/ticks/red_cross_small} and of the locked padlock. A
 * status colour that comes out of the art can never drift away from it.
 */
public final class V2Tokens
{
	private V2Tokens()
	{
	}

	// ── spacing: five steps, nothing else ─────────────────────────────

	/** Inside a control — glyph to its own text. */
	public static final int TIGHT = 2;
	/** Between rows of a list. */
	public static final int ROW = 4;
	/** Content inset inside a surface, on top of the art's own 9px bevel. */
	public static final int PAD = 6;
	/** Between sections — the game's own text line pitch. */
	public static final int SECTION = 12;
	/** Between major blocks of a screen — two line pitches. */
	public static final int BLOCK = 24;

	/** The spacing scale, for the enforcement test and for assertions. */
	public static final int[] SPACING = {0, TIGHT, ROW, PAD, SECTION, BLOCK};

	// ── fixed sizes ───────────────────────────────────────────────────

	/** Platform constraint. Never widen. */
	public static final int PANEL_WIDTH = 225;
	/** 225 minus the panel's own 4px edges. */
	public static final int CONTENT_WIDTH = 217;
	/** 18px checkbox or tick, plus a pixel of air above and below. */
	public static final int ROW_HEIGHT = 20;
	/** Chips and toggles — proved to 9-slice cleanly at this height. */
	public static final int CONTROL_HEIGHT = 22;
	/** {@code regular_large}'s native height. */
	public static final int BUTTON_HEIGHT = 28;
	/**
	 * The game's own line pitch, measured. NOT FontMetrics height, which runs
	 * 4px per line tall for this font and makes every stacked layout drift.
	 */
	public static final int LINE_PITCH = 12;
	/** Inline item sprites. */
	public static final int ICON = 16;
	/** Tile emblems. */
	public static final int TILE_ICON = 22;
	/** The corner size of the Card and Frame slices — structural, not spacing. */
	public static final int SLICE_INSET = 9;
	/** The tan frame's corner sprites are 9px, mostly transparent margin. */
	public static final int SLAB_INSET = 9;
	/**
	 * {@code button.png}'s rounded corner is exactly 5px — measured from its
	 * alpha. Slicing at 8 cut past it and tiled the light inner bevel across
	 * the whole face, which is the doubled-border glitch Luke screenshotted.
	 */
	public static final int CHIP_INSET = 5;
	/** The field well's end caps are 4px wide. */
	public static final int FIELD_INSET = 4;
	/** The bar frame's corner sprites are 9px, but only ~2px of that is ink —
	 *  the rest is the transparent margin the game leaves around a bar. */
	public static final int BAR_FRAME_INSET = 9;
	/**
	 * The panel frame's content inset. Its OWN constant: this used to be
	 * derived from the divider's bar geometry, so shrinking the divider to
	 * 1px silently shrank the inventory frame's padding from 20 to 15 and
	 * broke it (Luke, 2026-07-25). Atoms do not borrow each other's numbers.
	 */
	public static final int PANEL_FRAME_INSET = 20;
	/** The nav stone's chamfer. */
	public static final int NAV_STONE_INSET = 8;
	/** {@code StoneFrame}'s own insets — 1px dark over 1px light, plus the two
	 *  pixels its 8px corner chamfer needs to land on. */
	public static final int STONE_FRAME_INSET = 4;
	/** Utility buttons all render in one cell so a row of them lines up
	 *  (Luke, 2026-07-25) — the art itself varies from 16px to 21px. */
	public static final int UTILITY_CELL = 22;

	// ── colour ────────────────────────────────────────────────────────

	/** Section titles and hero names. Heading position only. */
	public static final Color HEADING = OsrsSkin.TITLE;
	/** The default. Row labels, prose. Deliberately not green. */
	public static final Color TEXT = OsrsSkin.MUTED;
	/** The number or state a row exists to report. */
	public static final Color STRONG = Color.WHITE;
	/** Provenance, disabled, "as of" lines. */
	public static final Color FAINT = OsrsSkin.FAINT;

	/**
	 * A progress bar's fill. V1's own bar green, and deliberately NOT
	 * {@link #DONE}: the status green is the checkmark's ink and reads as an
	 * achievement, which is far too loud for a bar that just says how far
	 * along something is (Luke, 2026-07-25 — V1's bars "look nicer"). §6 rule 4
	 * still holds: a bar carries no status, so it needs a colour of its own
	 * rather than borrowing one that means something.
	 */
	public static final Color BAR_FILL = OsrsSkin.VALUE.darker();

	/**
	 * The route/task progress blue — V1's {@code PROGRESS_BLUE}, kept because
	 * Goals uses it to mean "how far along a plan is" as distinct from the
	 * green "how much of a thing you have" (Luke, 2026-07-25).
	 */
	public static final Color BAR_BLUE = OsrsSkin.PROGRESS_BLUE;

	/** Done, owned, complete. Sampled from {@code ui/ticks/checkmark_small}. */
	public static final Color DONE = new Color(0x18BF1B);
	/** Actionable NOW — the game's interface orange, in status position. */
	public static final Color ACTION = OsrsSkin.TITLE;
	/** Blocked, missing, over threshold. Sampled from the cross and the padlock. */
	public static final Color BLOCKED = new Color(0xFF0000);

	/**
	 * The pointer highlight — a translucent white wash over whatever art a
	 * control is wearing.
	 *
	 * <p>This is the system's ONE derived effect, and it is Luke's call
	 * (2026-07-25): the curated {@code _hovered} sprites are the PRESSED look,
	 * so hover needed something of its own, and a uniform wash is the only
	 * treatment that works on every sprite in the set regardless of its
	 * colour. Everything else is still art.
	 */
	public static final Color HIGHLIGHT = new Color(255, 255, 255, 20);

	/**
	 * The opposite of {@link #HIGHLIGHT} — a translucent dark wash, for a
	 * surface that is present but not available. An unavailable tile needs a
	 * dark HIGHLIGHT and not merely a dark ring (Luke, 2026-07-25; highlight
	 * being the internal fill in his vocabulary): the whole tile has to sink,
	 * or a greyed edge just reads as a quieter status.
	 */
	public static final Color SHADOW = new Color(0, 0, 0, 120);

	/**
	 * The Frame's light line, dimmed halfway to its dark one (Luke,
	 * 2026-07-25: "less light, so it doesn't pop as much"). Derived from the
	 * theme's own two edge colours rather than picked per theme, so the three
	 * packs stay in step without a table to keep in sync.
	 */
	public static Color dimEdge(OsrsTheme theme)
	{
		return blend(theme.edgeLight, theme.edgeDark, 0.5);
	}

	/**
	 * A status colour as a tile's EDGE — pulled back toward the panel so it
	 * outlines rather than shouts (Luke, 2026-07-25: the status borders "need
	 * to be less bright"). At full strength a red or green ring is the loudest
	 * thing on a grid, which inverts §6: the tile's content is the message and
	 * the edge is the annotation.
	 */
	public static Color statusEdge(OsrsTheme theme, Color status)
	{
		return blend(status, theme.background, 0.4);
	}

	/**
	 * Tooltip colours — RuneLite's own, not the skin's (Luke, 2026-07-25:
	 * "Tooltips need to be RuneLite's default style, but with Detail font").
	 *
	 * <p>A tooltip is chrome, not part of the panel: it floats above whatever
	 * it explains and belongs to the client rather than to the skin, which is
	 * why it is the one surface here that does not wear OSRS art. Taken from
	 * {@code ColorScheme} so it follows RuneLite rather than drifting from it —
	 * the LAF resolves {@code ToolTip.background} to {@code lighten(DARK_GRAY,
	 * 4%)}, which is what the blend reproduces.
	 */
	public static final Color TOOLTIP_BG =
		blend(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR, Color.WHITE, 0.04);
	public static final Color TOOLTIP_TEXT = net.runelite.client.ui.ColorScheme.TEXT_COLOR;

	/** {@code amount} of the way from {@code from} to {@code to}. */
	private static Color blend(Color from, Color to, double amount)
	{
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}

	// ── type: five roles ──────────────────────────────────────────────

	/** Section titles, hero names. */
	public static Font headingFont()
	{
		return OsrsSkin.boldFont();
	}

	/** The default body font. */
	public static Font bodyFont()
	{
		return OsrsSkin.font();
	}

	/** Secondary lines, provenance. 14px floor — nothing smaller ships. */
	public static Font detailFont()
	{
		return OsrsSkin.smallFont();
	}

	// ── the slice families ────────────────────────────────────────────

	/**
	 * The game's notched slab — its corners, edges and middle as separate
	 * sprites. This is what a NON-CLICKABLE surface wears (Luke, 2026-07-25:
	 * "use the stone slab sprites currently used for the Design lab header"),
	 * so a static heading can never be mistaken for something to press.
	 */
	public static NineSlice slab()
	{
		// The thin tan frame, not the options-button rail: that rail is the
		// grey speckled border Luke called ugly, and its middle was the grey
		// tiled background. This is a 1px line — the Design lab header look.
		return NineSlice.frame("ui/borders/tan_border_corner_%s",
			"ui/borders/tan_border_%s", SLAB_INSET);
	}

	/** Filled surface at any size: Card, Tab body, Tooltip. */
	public static NineSlice card()
	{
		return NineSlice.of("ui/buttons/enter_wilderness_teleport", SLICE_INSET);
	}

	/**
	 * The chip / small-button surface — {@code button.png} sliced, since it is
	 * a rounded rectangle rather than a stone square and reads wrong beside
	 * the square tiles (Luke's list). Sliced rather than used at its native
	 * 35x35 so a chip can be any width.
	 */
	public static NineSlice chip()
	{
		return NineSlice.of("ui/buttons/button", CHIP_INSET);
	}

	/**
	 * The game's own sunken well — the texture behind every Filters dropdown
	 * and search box, and now behind every recessed surface in the system:
	 * fields, dropdowns, tables and list frames all share it (Luke,
	 * 2026-07-25).
	 */
	public static V2Well well()
	{
		return new V2Well();
	}

	/** @deprecated the well IS the field surface now. */
	public static V2Well field()
	{
		return new V2Well();
	}

	/** The thin frame the game draws around a progress bar — the same tan
	 *  line the slab wears, which is how they read as one system. */
	public static NineSlice barFrame()
	{
		return slab();
	}

	/**
	 * The riveted metal frame. It is the BUTTON's resting state now — the
	 * recessed surfaces all moved to the field well, which left this family
	 * doing one job well instead of two badly.
	 */
	public static NineSlice metal()
	{
		return NineSlice.frame("ui/borders/equipment_metal_corner_%s",
			"ui/borders/equipment_edge_%s", SLICE_INSET);
	}

	/**
	 * The dark stone the status tiles wear, sliced so a tile can be any size.
	 *
	 * <p><b>Not the Iron Hub nav bar's art</b>, despite the name — that is
	 * {@code tab_stone_middle}, RuneLite's dark grey tab stone. The real nav
	 * tile is hand-painted by {@code StoneNavButton} from theme colours and
	 * has no sprite in the curated set at all. Renamed in comment only, since
	 * the tiles look right as they are (Luke, 2026-07-25).
	 */
	public static NineSlice navStone()
	{
		return NineSlice.of("ui/tabs/tab_stone_middle", NAV_STONE_INSET);
	}

	/**
	 * The nav tile's chamfer (4px) plus its two bevel rings — what content
	 * must clear on a slab wearing that stone.
	 */
	public static final int NAV_TILE_INSET = 6;

	/** The outer panel border. */
	public static NineSlice panel()
	{
		return NineSlice.frame("ui/borders/bottom_line_mode_side_panel_corner_%s",
			"ui/borders/bottom_line_mode_side_panel_edge_%s", 32);
	}

	/** The text button. */
	public static NineSlice button()
	{
		return NineSlice.of("ui/buttons/regular_large", 8);
	}
}
