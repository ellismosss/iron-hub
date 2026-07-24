package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsSkin;
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

	// ── colour ────────────────────────────────────────────────────────

	/** Section titles and hero names. Heading position only. */
	public static final Color HEADING = OsrsSkin.TITLE;
	/** The default. Row labels, prose. Deliberately not green. */
	public static final Color TEXT = OsrsSkin.MUTED;
	/** The number or state a row exists to report. */
	public static final Color STRONG = Color.WHITE;
	/** Provenance, disabled, "as of" lines. */
	public static final Color FAINT = OsrsSkin.FAINT;

	/** Done, owned, complete. Sampled from {@code ui/ticks/checkmark_small}. */
	public static final Color DONE = new Color(0x18BF1B);
	/** Actionable NOW — the game's interface orange, in status position. */
	public static final Color ACTION = OsrsSkin.TITLE;
	/** Blocked, missing, over threshold. Sampled from the cross and the padlock. */
	public static final Color BLOCKED = new Color(0xFF0000);

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

	/** Filled surface at any size: Card, Chip, Tab body, Tooltip. */
	public static NineSlice card()
	{
		return NineSlice.of("ui/buttons/enter_wilderness_teleport", SLICE_INSET);
	}

	/** Border-only recess at any size: Well, TextField, list frames. */
	public static NineSlice well()
	{
		return NineSlice.frame("ui/borders/equipment_metal_corner_%s",
			"ui/borders/equipment_edge_%s", SLICE_INSET);
	}

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
