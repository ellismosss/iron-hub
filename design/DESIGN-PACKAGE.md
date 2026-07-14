# Handoff: Iron Hub — RuneLite Plugin UI

## Overview
UI design for **Iron Hub**, an all-in-one progression companion RuneLite plugin for OSRS Ironman accounts. Two surfaces only: a **225 px side panel** (dashboard + ~18 module tabs) and **in-game overlays/infoboxes** on the game canvas. This bundle covers the shared visual system, the P0 core-loop screens, seven P1 module tabs, and all P2 overlays/infoboxes.

Companion specs in the source repo: `DESIGN.md` (feature spec) and `DESIGN-HANDOVER.md` (platform constraints). The target codebase is the existing Java plugin at `src/main/java/com/ironhub/` — implement these designs there.

## About the Design Files
`Iron Hub P0 Mockups.dc.html` is a **design reference created in HTML** — it shows intended look and behavior; it is NOT production code. The task is to **recreate these designs in Java Swing inside the RuneLite plugin environment** (`PluginPanel`, `JPanel`, RuneLite `ColorScheme`, and the `Overlay`/`InfoBox` APIs) using the plugin's existing module structure. Open the HTML in a browser to inspect; every frame is drawn at the exact 225 px panel width.

The page is organized newest-first in three groups, each frame carrying a visible id badge:
- **3a–3f** Overlays & infoboxes (canvas composite + close-ups)
- **2a–2g** Module tabs (grid-first)
- **1a–1d** Visual system + P0 screens

## Fidelity
**High-fidelity** for layout, hierarchy, spacing, color, and copy — recreate faithfully. Two deliberate placeholders:
- **Two-letter squares** (e.g. `RW`, `ZU`) stand in for game sprite icons (item/NPC/skill icons from the sprite cache, drawn 16–24 px, 22–30 px cells).
- The 1400×800 canvas in 3a is a placeholder for a real game screenshot; minimap/chatbox rectangles are placement references only.

## Hard Platform Constraints (non-negotiable)
- Panel content is **exactly 225 px wide**, vertical scroll only. Never widen; cut content instead.
- Swing rendering: flat fills, 1 px borders, border radius ≤ 2 px, **no gradients/shadows/blur/animation**.
- Navigation depth ≤ 2: dashboard → module → detail. Back arrow + title header on every non-home screen.
- Overlays ≤ 250×200 px, display-only, semi-transparent dark, draggable/corner-snapped (RuneLite handles this).
- Infoboxes: 32×32 icon + short yellow overlay text + tooltip. RuneLite manages stacking.

## Design Tokens

### Colors — panel (map to RuneLite `ColorScheme` where equivalents exist)
| Token | Hex | Use |
|---|---|---|
| Panel background | `#262626` | panel base |
| Card background | `#2B2B2B` | rows, cards, section headers |
| Inset/field | `#1F1F1F` | text fields, progress-bar troughs |
| Border | `#3A3A3A` | frame borders (row borders `#333333`, button borders `#454545`) |
| Text primary | `#DCDCDC` | titles, emphasized names (bold) |
| Text body | `#C4C4C4` | default text |
| Text muted | `#8C8C8C` | secondary, "why" lines, section labels |
| Text faint | `#6B6B6B` | hints, timestamps |
| Accent | `#DC8A00` | interaction/selection ONLY (selected segment, primary button, progress fill, hover borders, active-goal outline). Hover tint `#F0A226` |

### Status colors — semantic, identical meaning everywhere, never repurposed
| Meaning | Panel | Canvas (brightened) | Glyph |
|---|---|---|---|
| Owned / complete / done | `#4DAE58` | `#52E06A` | ✓ |
| Available / actionable now | `#E0A23C` | `#FFB83F` | ● (▶ for "next" in run checklist) |
| Locked / missing | `#808080` (row text muted) | `#B0B0B0` | ○ (✗ for missing clue items, canvas red) |
| Warning / shortfall | `#D95757` | `#FF5C5C` | ! |

Amber status (`#E0A23C`) ≠ accent (`#DC8A00`): a state vs. an interaction color.

### Typography (RuneLite default fonts — the RuneScape pixel font, drawn at 16 px)
**Revised 2026-07-14 (field rule): HARD FLOOR 14 px — no text below it anywhere.** The
pixel font turns illegible scaled down; any smaller size elsewhere in this document is
superseded. Long text ellipsizes or wraps, never shrinks. `UiTokens` is authoritative.
- 14 px bold `#DCDCDC` — titles, item names, card headers
- 14 px regular `#C4C4C4` — body
- 14 px `#8C8C8C` — secondary
- 14 px bold, letter-spacing ~0.08em, `#8C8C8C` — SECTION LABELS (all caps)
- Screen/target titles may go larger (15 px bold); dashboard score figure 17 px.
- Hierarchy via bold + color, not size jumps.

### Spacing
4 px inside rows / glyph gaps · 8 px panel padding + card gaps · 12 px between sections. Row heights: 26 px standard, 24 px dense tree, 30 px panel header. Sections separated by 1 px `#333333` rules.

## Shared Atoms

### List row (the atom — used by every module)
`status glyph (14 px) · name (flex, ellipsize) · optional right value · [⚑ Path] [W Wiki] 18×18 icon buttons`
- Row: 26 px high, bg `#2B2B2B`, 1 px `#333333` border, radius 2.
- **Locked rows are two-line**: line 2 = `needs: <blocking requirement>` 10 px muted, indented 20 px (inline requirement does not fit at 225 px).
- Warning rows: red glyph `!`, red right-value (e.g. "6 h left"), border tinted `#4A3030`.
- Icon buttons: 18×18, bg `#333333`, border `#454545`, glyph `#9A9A9A`; hover → accent border + glyph. ⚑ = send route to Shortest Path plugin; W = open wiki. Tooltips on both.
- Names truncate at ~120 px with both buttons present — always set full name as tooltip.

### Grid tile (grid-first module views)
- **Icon cell** 30×30: bg `#2B2B2B`, status conveyed by 1 px border color (green owned / accent "next upgrade" / `#3F3F3F` + dimmed content for locked / amber ready / red diseased). Equipped/highlighted item = brighter glyph `#DCDCDC`. Tooltip = name + state.
- **Labeled tile** (3-col grids): icon 22 px + name 9 px (ellipsize at 58 px) + count/value 9 px bold in status color + optional 48×3 px mini-bar. 3 columns fit 225 px with 8 px padding and 5 px gaps.
- Every gridded section has a **⊞/☰ toggle** (segmented, 20 px high, selected = accent bg, dark text). ☰ list view = same data as standard rows.

### Other controls
- Primary button: accent bg, `#1E1E1E` bold 11 px text, 22–24 px high, radius 2.
- Secondary: bg `#333333`, border `#454545`, text `#C4C4C4`.
- Segmented control: 1 px `#454545` frame; selected segment accent bg + dark bold text.
- Alert chip: 10 px bold colored text + matching 1 px border on `#2B2B2B`, padding 3×7.
- Progress bar: 10 px, trough `#1F1F1F` + `#3A3A3A` border, accent fill (green when complete). Mini-bars 3–4 px.
- Search field: 24 px, bg `#1F1F1F`, border `#3A3A3A` (accent when focused), placeholder `#6B6B6B`.
- Tree indent: 13 px per level with 1 px `#333333` left guide, 2 levels max (deeper nests flatten).

## Screens / Views

### 1b Dashboard (panel home)
Stacked strips top-to-bottom, separated by 1 px rules:
1. Header 30 px: "IRON HUB" 11 px bold letterspaced + ⋮ settings.
2. Account score: label, `61%` 17 px bold + green `▲ 1.2 this wk`, 64×20 sparkline (single green polyline, no axes), one faint component line ("quests 74 · diaries 58 · …").
3. **What now?**: 4 segmented time chips (5m/30m/1h/2h+ — 4 is the max that fits); 3 ranked suggestion cards: accent rank number, bold title + right duration, one muted 10 px "why" line (ellipsized, full in tooltip), chevron. Card hover → accent border.
4. Active goal strip: goal name + accent %, progress bar, `● Next: <step>` + ⚑.
5. Alert chips row: amber "4 dailies", amber "7 patches ready", red "! runway 6 h".
6. Next best upgrades: 3 shared rows (one locked two-line example).
7. "All modules ›" footer row.

### 1c Module navigation
Back header · search field ("typing flattens accordion to filtered list") · category accordion: PROGRESSION (6), PLANNING (3), DAILY LOOP (4), ACCOUNT (5, shown collapsed). Rows 26 px: 16 px icon square + name + optional right badge (amber count "7", red "!", muted "87 left") + ›. Category headers 24 px on `#2B2B2B` with ▾/▸ and count.

### 1d Goal Planner — goal detail
Back header · goal card (name, accent %, bar, "est. ~86 h remaining", ACTIVE chip) · Tree/Checklist segmented toggle + "missing only ▾" filter · dependency tree (dense 24 px rows, indent guides): mixed states — done rows keep ✓ + strikethrough + green "auto" tag; available rows show live progress "68/70" amber; locked roots ○ muted with `needs:` line; dedupe note "feeds 3 of your goals" 10 px faint under shared steps; per-row ⚑/W where locatable. Footer note: "✓ steps auto-complete from account state".

### 2a Gear progression
Melee/Range/Mage full-width segmented tabs · "Context: General ▾" + ⊞/☰ · per-slot ladders: slot label (10 px caps) + right amber "next: <item>", then a wrapping row of 30 px icon cells ordered by progression (owned green → next accent → locked dim). Ladders longer than 6 wrap to a 2nd row — never horizontal scroll. Bottom card: "current → next upgrade" emphasis row with why-line + ⚑.

### 2b Combat achievements
Tier card (Hard tier, `214/425 pts`, bar, "next reward: …") · filter chips ("Possible now" selected accent, "All") + ⊞/☰ · 3-col boss tile grid (count colored by state; complete = green border + ✓; zero-progress dimmed) · EASIEST NEXT: 2 rows with readiness why-lines.

### 2c Collection log
Overall card `247/1,568 slots · 22%` + bar · sort/filter line + ⊞/☰ (both views designed; the HTML toggles live via the Tweaks panel) · grid = 3-col activity tiles, list = rows with 40×4 mini-bars · DRY STREAK card: "Vorkath's head · 2.1× rate (red) · 486 KC since last unique" — factual framing, no pressure copy.

### 2d Goal planner (main tab)
Header with accent "+ Goal" button · ACTIVE goal card (accent border) with "tree ›" link · OTHER GOALS rows (mini-bar + % + pin-as-active ▲ button) · dedupe note card · BROWSE TARGETS 3-col tile grid of popular capstones + ⊞/☰.

### 2e Farming runs
Full-width accent "Start herb run" + stats line "avg 5:40 · best 4:55 · 62 runs logged" · HERB PATCHES: "7 of 9 ready" + ⊞/☰ + 30 px patch tile grid (amber ready / gray growing, timer in tooltip / red diseased) + warning line (seed stock) · OTHER GROUPS rows: state glyph + name + right status/timer + ⚑.

### 2f Bank & banked XP
Search field (focused state shown, accent border) + result rows (icon + name + `×143` bold count) + faint "snapshot from last bank visit · 2 h ago" · BANKED XP: ⊞/☰ + 3-col skill tiles (icon, name, amber XP value; method in tooltip) · BEST IN BANK ("vs Zulrah · Ranged ▾"): **standard OSRS equipment-slot grid** — 3×30 px columns, rows: [—, head, —] / [cape, amulet, ammo] / [weapon, body, shield] / [—, legs, —] / [gloves, boots, ring], all green-bordered (owned by definition) · two half-width buttons "DPS calc ↗" / "Bank tag" (full labels don't fit — keep in tooltips: "Export to DPS calculator", "Save as bank tag").

### 2g Loot & supplies
Source header "Zulrah ▾ · 234 kills" · Per kill/Per hour/Session segmented · LOOT: "2 uniques" green + ⊞/☰ + icon-cell grid, **uniques = green border + green code**, counts 8 px UNDER cells (don't overlay text on 30 px sprites) · SUPPLIES USED rows (avg per kill; warning row red with runway) · NET · IN ITEMS: one line ("Scales self-sustaining ✓ · food −1.2 sharks/kill") + faint "GP value de-emphasized".

## Overlays (P2) — canvas surface
Shared style: bg `rgba(0,0,0,0.55)`, radius 2, padding 6×8, 12 px text with 1 px black text-shadow (RuneScape style), white bold titles, yellow `#FFFF00` values, canvas status colors (table above). Display-only; right-click menu only.

- **3b Farming run** (~150×130, ON by default during a run): title "Herb run" + yellow elapsed timer; patch checklist — ✓ done green, ▶ next (white bold name), · pending gray, ! diseased red; footer "1 of 7 done". Done rows collapse after 3 ("+3 done") to cap at 7 lines. Right-click: skip patch / end run.
- **3c Active goal** (190×~52, fixed 2 lines + 6 px bar): name + yellow %, accent bar on `rgba(255,255,255,0.15)` trough, amber `➜ <current step>`. Step line flashes green once on auto-complete.
- **3d Supplies-per-trip HUD** (118 px, max 5 rows, highest-usage first): "This trip" + rows of 16 px icon + yellow count ("−4", "−2 doses"); red count = inside runway warning threshold. Resets on bank/trip end.
- **3e Clue step helper** (~185 px, auto-shows with clue in inventory): "Hard clue · step 3", gray step text, ✓/✗ requirement rows (✗ red = missing), STASH state line. Missing-item acquisition info lives in the panel, not the overlay.

### 3f Infoboxes (32×32 icon + overlay text bottom-right, yellow, shadowed)
| Infobox | Text | Tooltip | Visibility |
|---|---|---|---|
| Dailies outstanding | count | list of outstanding + "resets 00:00 UTC" | hidden at 0 |
| Patches ready | count | per-group counts | hidden at 0 |
| Run timer | m:ss | "herb run · 1/7 · avg 5:40 best 4:55" | during run only |
| Slayer task | remaining | "Nechryael · 87 left · streak 43 · 1,240 pts" | while on task |

Timer text at 4–5 characters is the practical width limit.

## Interactions & Behavior
- Hover states: accent border and/or accent text only — no fills, no elevation.
- Suggestion/tile/row click → module detail (depth ≤ 2). Alert chips and infoboxes click through to their module.
- ⚑ publishes `PluginMessage("shortestpath", "path", …)`; fall back to wiki map when Shortest Path is absent.
- Goal steps auto-complete from AccountState events — no manual ticking for detectable steps.
- Every suggestion/plan step shows a one-line muted "why" (explainability principle).
- Mouse-only; no keyboard shortcuts, no drag-and-drop in the panel.
- ⊞/☰ preference should persist per module (profile-scoped config).

## State Management
Per existing architecture (`DESIGN.md` §2): modules subscribe to `AccountState`; panel views re-render on relevant events only (never per-tick). UI-local state: selected time chip, style tab, grid/list preference, accordion expansion, active view toggle.

## Assets
No bundled assets. All icons come from the game sprite cache at runtime; the two-letter placeholder squares mark every point where a sprite is required. Status glyphs (✓ ● ○ !) should ship as tiny painted icons or vector glyphs, not font emoji.

## Files
- `Iron Hub P0 Mockups.dc.html` — all mockups (open in a browser; self-contained page, frames grouped 3x/2x/1x newest-first with id badges matching this doc).