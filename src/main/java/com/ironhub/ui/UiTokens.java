package com.ironhub.ui;

import java.awt.Color;

/**
 * Design tokens from the Iron Hub UI handoff (design/DESIGN-PACKAGE.md).
 * Single source of truth for panel + overlay styling — never hardcode these
 * values in views. Mockups: design/iron-hub-mockups.html (frames 1a–3f).
 *
 * Where a RuneLite ColorScheme constant is equivalent, prefer it and note the
 * mapping here.
 */
public final class UiTokens
{
	private UiTokens()
	{
	}

	// ── Panel surfaces ────────────────────────────────────────────────
	public static final Color PANEL_BG = new Color(0x26, 0x26, 0x26);
	public static final Color CARD_BG = new Color(0x2B, 0x2B, 0x2B);
	public static final Color INSET_BG = new Color(0x1F, 0x1F, 0x1F); // fields, bar troughs
	public static final Color BORDER = new Color(0x3A, 0x3A, 0x3A);
	public static final Color BORDER_ROW = new Color(0x33, 0x33, 0x33);
	public static final Color BORDER_BUTTON = new Color(0x45, 0x45, 0x45);
	public static final Color BORDER_WARNING_ROW = new Color(0x4A, 0x30, 0x30);

	// ── Text ──────────────────────────────────────────────────────────
	public static final Color TEXT_PRIMARY = new Color(0xDC, 0xDC, 0xDC); // titles, bold names
	public static final Color TEXT_BODY = new Color(0xC4, 0xC4, 0xC4);
	public static final Color TEXT_MUTED = new Color(0x8C, 0x8C, 0x8C);  // "why" lines, section labels
	public static final Color TEXT_FAINT = new Color(0x6B, 0x6B, 0x6B);  // hints, timestamps
	public static final Color GLYPH_MUTED = new Color(0x9A, 0x9A, 0x9A); // icon-button glyphs, unselected segments

	// ── Controls & tiles (from mockup frames 1a/1b/2a) ────────────────
	public static final Color ICON_BUTTON_BG = new Color(0x33, 0x33, 0x33);
	public static final Color TILE_BG_LOCKED = new Color(0x25, 0x25, 0x25);   // dimmed locked tile fill
	public static final Color BORDER_DIM = new Color(0x3F, 0x3F, 0x3F);       // locked tiles, unselected chips
	public static final Color NAV_ROW_HOVER_BG = new Color(0x2F, 0x2F, 0x2F); // borderless nav rows (1c)

	// ── Accent (interaction/selection ONLY — never a status color) ────
	public static final Color ACCENT = new Color(0xDC, 0x8A, 0x00);
	public static final Color ACCENT_HOVER = new Color(0xF0, 0xA2, 0x26);
	public static final Color ACCENT_TEXT_ON = new Color(0x1E, 0x1E, 0x1E); // text on accent bg

	// ── Status colors (semantic; identical meaning everywhere) ────────
	// Panel variants
	public static final Color STATUS_OWNED = new Color(0x4D, 0xAE, 0x58);     // ✓ owned/complete/done
	public static final Color STATUS_AVAILABLE = new Color(0xE0, 0xA2, 0x3C); // ● actionable now
	public static final Color STATUS_LOCKED = new Color(0x80, 0x80, 0x80);    // ○ locked/missing
	public static final Color STATUS_WARNING = new Color(0xD9, 0x57, 0x57);   // ! warning/shortfall
	// Canvas (overlay) variants — brightened for readability over the game
	public static final Color CANVAS_OWNED = new Color(0x52, 0xE0, 0x6A);
	public static final Color CANVAS_AVAILABLE = new Color(0xFF, 0xB8, 0x3F);
	public static final Color CANVAS_LOCKED = new Color(0xB0, 0xB0, 0xB0);
	public static final Color CANVAS_WARNING = new Color(0xFF, 0x5C, 0x5C);

	// ── Overlay surface ───────────────────────────────────────────────
	public static final Color OVERLAY_BG = new Color(0, 0, 0, 140);        // rgba(0,0,0,0.55)
	public static final Color OVERLAY_VALUE = new Color(0xFF, 0xFF, 0x00); // yellow values, shadowed
	public static final Color OVERLAY_BAR_TROUGH = new Color(255, 255, 255, 38); // rgba(255,255,255,0.15)

	// ── Spacing & metrics (px) ────────────────────────────────────────
	public static final int PANEL_WIDTH = 225;      // hard platform constraint — never widen
	public static final int PAD_TIGHT = 4;          // inside rows, glyph gaps
	public static final int PAD = 8;                // panel padding, card gaps
	public static final int PAD_SECTION = 12;       // between sections
	public static final int ROW_HEIGHT = 26;        // standard list row
	public static final int ROW_HEIGHT_DENSE = 24;  // dense tree rows
	public static final int HEADER_HEIGHT = 30;     // panel header
	public static final int CATEGORY_HEADER_HEIGHT = 24;
	public static final int ICON_BUTTON_SIZE = 18;  // ⚑ Path / W Wiki buttons
	public static final int STATUS_GLYPH_SIZE = 14;
	public static final int ICON_CELL_SIZE = 30;    // grid icon cells
	public static final int TILE_ICON_SIZE = 22;    // labeled 3-col tiles
	public static final int SEGMENTED_HEIGHT = 20;  // ⊞/☰ and chip toggles
	public static final int BUTTON_HEIGHT = 24;     // primary/secondary buttons (22–24)
	public static final int PROGRESS_BAR_HEIGHT = 10;
	public static final int MINI_BAR_HEIGHT = 4;    // 3–4 px mini-bars
	public static final int TREE_INDENT = 13;       // per level, max 2 levels
	public static final int SEARCH_FIELD_HEIGHT = 24;
	public static final int BORDER_RADIUS = 2;      // maximum radius anywhere

	// ── Typography (px; hierarchy via bold + color, not size jumps) ───
	public static final float FONT_SIZE_SCORE = 17f;     // dashboard account-score figure only
	public static final float FONT_SIZE_BODY = 12f;      // titles + body (titles bold)
	public static final float FONT_SIZE_SECONDARY = 11f; // secondary, "why" lines
	public static final float FONT_SIZE_LABEL = 10f;     // SECTION LABELS, needs: lines, chips
	public static final float FONT_SIZE_TILE_LABEL = 9f; // labeled-tile names/values (panel minimum)
	public static final float FONT_SIZE_TILE_CODE = 8f;  // two-letter sprite placeholder codes
	// Loadout Lab type scale — the RuneScape pixel font renders too small
	// below ~13px in-client (field request 2026-07-14), so the lab runs a
	// step larger than the panel-wide sizes above.
	public static final float FONT_SIZE_LAB_TEXT = 14f;  // body, buttons, card headers
	public static final float FONT_SIZE_LAB_SMALL = 13f; // captions, info lines
	public static final float FONT_SIZE_LAB_LABEL = 12f; // SECTION LABELS
	public static final float LETTER_SPACING_LABEL = 0.08f; // section labels (~0.08em tracking)
	public static final float LETTER_SPACING_TITLE = 0.12f; // "IRON HUB" panel title

	// ── Row internals (mockup frames 1a–1c) ───────────────────────────
	public static final int ROW_GAP = 6;
	public static final int GRID_GAP = 5;      // between 30 px tiles
	public static final int CHIP_GAP = 3;      // between time/filter chips
	public static final int NAV_ICON_SIZE = 16; // module icon squares in nav rows
	public static final int FOOTER_ROW_HEIGHT = 28; // "All modules ›" row
	public static final int SPARKLINE_WIDTH = 64;   // score sparkline cap (trend only)
	public static final int SPARKLINE_HEIGHT = 20;
	public static final int SCROLL_UNIT = 16;       // wheel scroll px per unit

	// ── Overlay budgets ───────────────────────────────────────────────
	public static final int OVERLAY_MAX_WIDTH = 250;
	public static final int OVERLAY_MAX_HEIGHT = 200;
}
