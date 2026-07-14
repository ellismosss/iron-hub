# Iron Hub — UI Mockup Handover

**For: Claude Design** · Task: mock up the plugin interface (side panel) + in-game overlays for the Iron Hub RuneLite plugin.
Companion doc: `DESIGN.md` (full feature spec — read §3 for module details, §4 for UI intent).

---

## 1. What you're designing

Iron Hub is an all-in-one progression companion for Ironman accounts in Old School RuneScape, built as a RuneLite plugin. The UI has exactly two surfaces:

1. **A side panel** — the hub itself (dashboard + module tabs), docked in RuneLite's sidebar.
2. **In-game overlays & infoboxes** — lightweight HUD elements drawn over the game canvas.

There is no other UI. No modals over the game, no web views, no fullscreen screens.

---

## 2. Hard constraints (RuneLite platform)

### 2.1 Side panel

- **Fixed width: 225 px content area.** Never wider. Vertical scrolling only — no horizontal scroll, no horizontal overflow. Design every panel screen as a 225 px-wide column of arbitrary height.
- Opened via a single icon in RuneLite's right-hand icon toolbar; one plugin = one panel. All navigation happens *inside* our 225 px column (tabs, back buttons, accordions — our responsibility).
- **Swing-based rendering.** Practical implications: flat rectangles, solid fills, 1 px borders, simple icons (16–24 px), no blur/gradients/shadows/animation. Rounded corners sparingly. Think "dense dark dashboard", not modern web app.
- **Dark theme, RuneLite palette** (use these; exact constants live in RuneLite's `ColorScheme`):
  - Panel background: very dark gray `#1E1E1E`–`#282828`
  - Card/section background: slightly lighter dark gray (e.g. `#2B2B2B`)
  - Primary text: light gray `#C8C8C8`–white; secondary text: mid gray
  - Accent / interactive highlight: RuneLite brand orange `#DC8A00`
  - Semantic status colors (ours, used consistently everywhere): **Owned/Complete = green**, **Available/Actionable = amber/orange**, **Locked/Missing = gray**, **Warning = red**
- Typography: standard small sans-serif (≈11–12 px). Bold + color for hierarchy, not size jumps. No custom display fonts in the panel.
- Standard building blocks available: buttons, icon buttons, text fields (search), dropdowns, progress bars, collapsible sections, scroll panes, tooltips. Item/skill/NPC icons come from the game sprite cache (32×32 max, usually shown 16–24 px).

### 2.2 Overlays (on the game canvas)

- Small movable panels; default style is a **semi-transparent dark rectangle** with RuneScape-style text (yellow/white with black shadow). Users can drag-reposition; overlays snap to canvas corners.
- Must stay small — an overlay that covers gameplay gets disabled. Budget roughly ≤ 250×200 px per overlay, most far smaller.
- Text lines + tiny icons + simple progress bars only. No interactivity beyond right-click menu entries (assume display-only for mockups).
- **Infoboxes** are a special fixed format: a 32×32 icon with an optional short text overlay (e.g. a count or timer) and a hover tooltip. They stack automatically in a row/grid managed by RuneLite — design only the single-box content, not their layout.

### 2.3 General

- Mock against a game canvas of ~1400×800 for overlay placement shots.
- All features must degrade to panel-only; overlays are optional enhancements (off by default except farming runs).

---

## 3. Screens to mock (priority order)

### P0 — the core loop

1. **Dashboard (panel home)** — the money screen. Stacked strips, top to bottom:
   - Account score header (composite % + tiny trend sparkline)
   - **"What now?"** block: time-available selector (5m / 30m / 1h / 2h+ as segmented chips) + 3 ranked suggestion cards, each with a one-line "why" ("feeds Bowfa goal", "resets in 3h")
   - Active goal strip (goal name, progress bar, next step)
   - Alert chips row: dailies outstanding (4), patches ready (7), runway warnings (1)
   - "Next best upgrades" (3 item rows)
2. **Module navigation** — how users reach the other ~15 modules from the dashboard: search field + scrollable icon list, or category accordion. Your call; must scale to ~18 entries in 225 px.
3. **Goal Planner — goal detail** — tree view of a goal (e.g. Bowfa) collapsed to missing steps, with a flattened checklist toggle. Show mixed step states (done ✓ auto-detected / available / locked) and per-row `[Path]` `[Wiki]` icon buttons.
4. **Shared list-row pattern** — the atom used everywhere: `status icon · name · blocking requirement (if locked) · [Path] [Wiki]`. Show owned/available/locked variants once; all other screens reuse it.

### P1 — flagship modules

5. **Gear progression** — style tabs (Melee/Range/Mage) + slot ladder; per-slot horizontal progression of items with owned/available/locked states; "current → next upgrade" emphasis.
6. **Bank brain** — bank search box + results; Banked XP summary (per-skill rows: skill icon, "2.4M", progress-to-target bar); "Best-in-bank loadout" result card with equipment-slot grid (standard OSRS equipment layout) + `[Export to DPS calc]` `[Save as bank tag]` buttons.
7. **Farming runs (panel)** — patch group list with per-patch state dots, "Start run" button, historical stats line ("avg 5:40 · best 4:55").
8. **Slayer optimizer** — current task card (monster icon, count remaining, points), "readiness" section (generated loadout summary, supplies estimate), block/skip advisor list.
9. **Supplies runway** — per-consumable rows: item icon, stock count, runway bar ("14 h"), red state for shortfalls; planning-mode input ("100 × Zulrah" → supply bill).

### P2 — overlays & infoboxes (one canvas shot each, plus close-ups)

10. **Farming run overlay** — active run: ordered patch checklist (done/next/pending), elapsed timer, next-patch name. The one overlay that's on by default.
11. **Active goal overlay** — 2 lines: goal name + current step, progress bar.
12. **Supplies-per-trip HUD** — 3–4 icon+count rows showing consumption this trip.
13. **Clue step helper overlay** — current step requirement vs owned, nearest STASH state.
14. **Infoboxes** (design 4): dailies outstanding (count), patches ready (count), run timer (mm:ss), slayer task (count remaining). Each: 32×32 icon + overlay text + tooltip content spec.

### P3 — nice to have

15. Dailies checklist panel, Clues & STASH readiness panel ("Hard: 84%" per-tier bars), Collection log dry-streak card, Death recovery card, Dashboard "share snapshot" export image (this one is free-form — it's a rendered PNG, not Swing, so richer styling allowed).

---

## 4. Interaction notes

- Navigation depth ≤ 2: dashboard → module → detail. Persistent back arrow + module title header on every non-home screen.
- Every locatable entity row gets a `[Path]` button (sends route to Shortest Path plugin) — use a consistent small footpath/flag icon.
- Status language is sacred: green = owned/done, amber = actionable now, gray = locked, red = warning. Same meaning in panel and overlays.
- Suggestions and plans must always show *why* (one muted line under the title). Explainability is a product principle.
- Assume mouse-only, no keyboard shortcuts, no drag-and-drop in the panel.

## 5. Deliverables

- Mockups as static images or a single HTML page; panel screens in exact 225 px-wide frames, overlay shots composited on a game screenshot-style dark canvas (placeholder art fine).
- A one-pager defining the shared visual system: colors, row pattern, status icons, spacing scale, button styles.
- Flag anything that won't fit 225 px rather than widening it — we'll cut content, not the constraint.
