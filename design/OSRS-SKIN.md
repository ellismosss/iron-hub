# OSRS Skin — the stonework design system

A second design system for Iron Hub that makes the sidebar read as an extension of the
game's own interface — the Character Summary look: stone boxes with notched corners,
engraved borders, the game's fonts with their black drop shadows, orange labels and
green values, icon-led stats. **Development stage only**: atoms + a Design lab module to
prove them in the real sidebar. No existing module is migrated until Luke says so.

Where this doc and DESIGN-PACKAGE.md disagree, DESIGN-PACKAGE.md still governs the
*existing* modules; this system deliberately breaks two of its rules (text shadows,
non-trivial corners) because matching the game is the point.

## Ground truth (never eyeball; these are the sources)

- **Palette + geometry**: the wiki's native-1x screenshot of the exact interface —
  `File:Character_Summary.png` (204×275, fixed-mode opaque stone). Every color below was
  sampled from it programmatically; the corner stamp is its literal pixels.
- **Fonts**: RuneLite bundles the game's own fonts — `FontManager.getRunescapeFont()`
  (16px regular), `getRunescapeBoldFont()`, `getRunescapeSmallFont()`. Using them at
  native size is pixel-identical to in-game text.
- **Title orange**: `JagexColors.DARK_ORANGE_INTERFACE_TEXT` = `#FF981F` — RuneLite
  already ships it; verified equal to the sampled title pixels.
- **Sprite provenance** (upgrade path, not used yet): the game draws these boxes from
  cache sprites — `SpriteID.Combatboxes` (whole pre-drawn boxes),
  `SpriteID.V2StoneBorders` (9-slice corners/edges/intersections),
  `SpriteID.V2StoneButton*` (button variants incl. hover/green). A runtime
  `SpriteManager` path could 9-slice the real art in-client, but headless renders and
  arbitrary Swing sizes make the painted recreation the base implementation.

## Palette (sampled, exact)

| Token | Hex | Role in the game art |
|---|---|---|
| `BACKGROUND` | `#3E3529` | interface backing between boxes (subtle texture in-game; flat here) |
| `BOX_FILL` | `#554C41` | stone box interior |
| `EDGE_DARK` | `#2D2A22` | outer 1px line of every box border |
| `EDGE_LIGHT` | `#726451` | inner 1px line (the "engraving" highlight) |
| `RECESS` | `#28251E` | darker recess behind the tab strip |
| `TITLE` | `#FF981F` | headers (== `JagexColors.DARK_ORANGE_INTERFACE_TEXT`) |
| `LABEL` | `#FF9933` | stat labels ("Combat Level:") |
| `VALUE` | `#0DC10D` | stat values (the interface green) |
| `TEXT_SHADOW` | `#000000` | every string, offset +1,+1 |
| frame greys | `#4F4836 #575040 #5D5848 #60594A` | outer window frame mottle (phase 2) |

## The stone box (core atom)

Anatomy, from a mid-edge slice: background → **1px `EDGE_DARK`** → **1px `EDGE_LIGHT`**
→ `BOX_FILL`. Same order on all four edges — it is an engraved groove, not a lit/shadow
bevel.

The corners are **stepped concave notches**, not arcs. The exact 7×7 stamp (top-left;
the other three are mirrors), `B`=background `D`=dark `L`=light `F`=fill:

```
BBBBBDD
BBBBBDL
BBBBBDL
BBBBDDL
BBBDDLL
DDDDLLF
DLLLLFF
```

Painted in Java2D this is pixel-identical to the sprite at any box size — the game
itself stretches the same art 9-slice-style, so size freedom is native to the design.
Border insets: 7px minimum so content clears the notch (game text sits ~8px in).

## Themes — the same grammar in different clothes

The atoms are theme-parameterised (`OsrsTheme`: the surface colors + the corner stamp,
since geometry differs too). Text colors and fonts stay global — resource packs re-sprite
the interface but the game's text rendering is unchanged. `OsrsTheme` is an enum so the
**`osrsTheme` config item IS the theme**; both dress every atom. Two themes exist:

**`OsrsTheme.STONE`** — the vanilla fixed-mode stone above.

**`OsrsTheme.MYSTIC`** — Luke's daily look: the [Mystic resource pack]
(https://github.com/melkypie/resource-packs/tree/pack-mystic-pack) by Drunken Monk
(BSD-2-Clause via the resource-packs repo; `licenses/mystic-pack-LICENSE`, pinned commit
`339ac716`). Ground truth is the pack's own sprite PNGs — its `button/` 9-slice
(`corner_top_left` 6×6, `edge_*`, `middle` fill tile) is the re-skin of the exact
`V2StoneButton` art the Character Summary boxes use:

| Token | Mystic | Sampled from |
|---|---|---|
| `background` | `#1D1D1D` | the pack's resizable-mode overlay (`overrides.toml`: `0xBA1A1A1A`) composited over `UiTokens.PANEL_BG`, as the game composites it over the world — per Luke, for definition; the fixed-mode `side_panel_background.png` (`#232323`) is near-identical to the fill and left boxes flat |
| `boxFill` | `#222222` | `button/middle.png` (subtle ±1 dither; we stay flat) |
| `edgeDark` | `#141414` | `button/edge_top.png` row 0 |
| `edgeLight` | `#383838` | `button/edge_top.png` row 1 |

Corner stamp 6×6 (the notch is literally transparent in the sprite — it composites over
the backing, which is exactly what our `B` token paints):

```
BBBBDD
BBBDLL
BBBDLF
BDDLLF
DLLLFF
DLFFFF
```

Mystic's Character Summary icons are the pack's own 18×18 redraws (`quests_tab/`),
bundled under `data/icons/osrs/mystic/`. The pack also ships hover/selected/disabled
9-slice variants — the phase-2 hover states come free.

## Typography

- Labels/values/body: RuneScape regular, 16px native, `LABEL`/`VALUE` color.
- Headers: RuneScape bold, `TITLE` color, centered.
- **Every string gets the +1,+1 black shadow** — this, more than anything, is what makes
  the text read as OSRS.
- RuneScape *small* (`OsrsSkin.smallFont()`) is used **inside progress bars only**, where
  Luke asked for smaller text; the 14px panel floor still governs every classic surface.
  Its caps ink is 8 rows (baseline−10..−3) against the regular font's 11.
- Glyph safety unchanged: ASCII plus `· … — ×`.

## Iconography

Wiki images only (standing rule), and the wiki hosts this interface's exact icons at
native 18×18: `Character_Summary_-_*.png` (combat, total level, total XP, combat tasks,
collections) plus the quest/diary spirals. Bundled under `data/icons/osrs/`. The stat
pattern is always **icon + green value** on one line under an orange label.

## Atom inventory (v1 — what the Design lab proves)

| Atom | What it is |
|---|---|
| `OsrsSkin` | global text colors + fonts; `OsrsTheme` carries the surfaces (both are the only style source for skinned surfaces) |
| `StoneBorder` | Swing `Border` painting the engraved edge + corner stamps; insets 4/6 (game pad) |
| `StonePanel` | theme-fill panel wearing a `StoneBorder` |
| `OsrsLabel` | shadowed RuneScape-font text, multi-line on `\n`; factories: `title` `label` `value` |
| `StatBox` | orange label line(s) + centered icon+value line in a `StonePanel` |
| `StoneFrame` | the thin resizable-mode side-panel frame (1px dark/light + 8×8 stepped chamfer) |
| `StoneButton` | **the default button**: the notched box with hover/press fills |
| `StoneNavButton` | the square stone — **navigation only**; selected lifts the fill + brightens the bevel |
| `StoneProgressBar` | tall bar with left/center/right labels inside |
| `StoneMeter` | the thin bar, 5px, no text |
| `StoneCheckbox` / `StoneChecklist` | the game's tick box; rows grouped inside ONE notched frame |
| `StoneScrollBarUI` | 16px track + bevelled thumb + stone arrow buttons |
| `StoneTextField` | the sunken input, with placeholder |
| `StoneComboBoxUI` | the dropdown: sunken value + stone arrow + skinned popup list |

**Two button idioms exist in the game** and it matters which we copy: the Settings
interface's buttons are a *raised* black-outline bevel (`#000000` outline, `#8A7757`
bevel, `#463C31` fill), while the Character Summary's stat boxes are the *engraved*
groove. Per Luke the notched engraved box is Iron Hub's default button — square stone
(`StoneNavButton`) is reserved for navigation, so it never becomes wallpaper.

**State art.** The game has no pointer, so hover/press have no sprite anywhere: MYSTIC's
hover is sampled from the pack's own `tab/small_middle_hovered.png` (a top-lit gradient,
flattened to its lit tone since our surfaces are flat), STONE's is derived from its
sampled fill (±8). Everything else is sampled: selected fill/bevel from
`tab_stone_middle_selected.png`, checkbox anatomy from `File:Settings_interface.png`
(black outline, `#8A7757` bevel, `#1A1A1A` interior, `#00FE00` tick) and
`options/square_check_box_checked.png` (`#65C772` tick), tab recess/selected from the
wiki strip (`#28251E` → `#3E3529`).

**The bars are a synthesis, and say so:** the game draws no text-inside-a-bar, so the
layout follows Luke's reference — RuneLite's own `ProgressBar` (16px tall; white
left/center/right labels; fill colour supplied by the caller) — painted in skin tokens
with the game's own **small** font, which is what holds the bar near the reference's
16px (18px here: Luke wanted a breathing row above and below the ink, and 6px of side
padding so the trough does not hug the text). The **fill is semantic and belongs to the
caller**, and the trough is `OsrsSkin.BAR_TROUGH` `#3E3830` — Luke's value, and
theme-independent like the rest of the bar, since each theme's sunken `recess` swallowed
it against the backing (RuneLite's own XP-tracker trough is `#3D3831`).

**The checklist highlight is a band, not a bleed** (Luke walked it to the pixel): it sits
1px inside the engraved edge, mirrored right, and wraps the checkbox with a 1px gap on
every side. The stat-box padding left an ugly gap; full-bleed stretched too far.

**Tabs were tried and cut** (2026-07-16): a `StoneTabStrip` was built beside the nav
stones so the two could be judged in-client, and Luke picked the stones. The strip is
deleted rather than left to rot — it is in git history if tabs ever earn their place.

**Nav stones carry the game's full-size tab icons**, not the 18px Character Summary set,
which reads as toys at that weight. The lab keeps a small-icon row beneath the large one
so both weights stay judgeable side by side (Luke). Both sets are the game's own: STONE's are the wiki
files the Interface page itself names (`Combat icon`, `Stats icon`, `Inventory`,
`Worn Equipment`, `Prayer tab icon`, ...), MYSTIC's are the pack's redraws of the same
tabs; `OsrsIcons` resolves per theme and returns null rather than substituting.

Implementation notes proven by the render: text uses the game's tight **12px line
pitch**, not Swing's FontMetrics height (16) — FontMetrics-driven layout runs 4px/line
taller than the game and the boxes stop matching. The TTF's ink placement is measured,
not assumed: glyphs float high in the em box (caps/digits span baseline−12..baseline−2,
descenders reach baseline+1), and the game centers the visible INK in its boxes (wiki
1x: box, icon and text centers all equal) — so OsrsLabel's line block is 12n+4 with the
first baseline at 14, which lands the caps+shadow mass dead-center and keeps descender
ink un-clipped (positioning by nominal baseline read 2px high in-client). Antialiasing
must be explicitly OFF or the pixel font smears. The quests/achievements spiral icons
were cropped 1:1 from the wiki screenshot (opaque on `BOX_FILL`, which blends invisibly
on our identical boxes).

Proven in-client (2026-07-16, halved captions + off-center XP text): **a UI-less custom
JComponent must override `getMinimumSize()`** — the default is its *current size*, 0×0
before first layout, and BoxLayout derives a row's cross-axis alignment from child
*minimums* (`SizeRequirements.getAlignedSizeRequirements`), so an all-zero-minimum row
degenerates to alignment 0.0 and cuts every child to half its maximum, top-anchored.
Multi-pass render harnesses (SwingRender) self-heal this — stale sizes leak into the
minimums — so the regression test mounts the tab like the client and lays out ONCE.
OsrsLabel also centers its text block in whatever height it is given, so any future
allocation quirk shifts text instead of clipping glyphs.

**Phase 3 — inputs (done).** All three are sampled the same way: the scrollbar from
`File:Settings_interface.png` (thumb + arrows over a `#252019` track) and the pack's
`scrollbar/*.png` (`#141414` outer, `#383838` bevel, `#2B2B2B` fill); the field from the
same screenshot's search box (`#261D11` outline, `#31281C` inner, `#372E22` interior,
`#FF981F` text) and the pack's `overrides.toml` (`item_search.background #141414`); the
dropdown from that screenshot's "Exact Value" control and `dropdown.border.inner
#232323`. Vanilla's thumb carries a gradient, flattened to its mean (flat fills are the
house rule; the bevel is what reads).

`fieldEdge` earns its own token because **a field is a sunken well, not an engraved
box**: the game steps dark outline → mid tone → interior, and MYSTIC's interior *is* its
`edgeDark`, so reusing the box tokens left its fields borderless.

**Typed text is `MUTED`** — body copy, matching the section captions (Luke's call; the
game's own field types in orange, the sidebar's does not), with placeholders in the
dimmer `FAINT` so the two never read alike. `FAINT` is derived, not sampled: the game has
no placeholders, so it dims `MUTED` by the same ratio UiTokens uses from body to faint.

**Swing-laid-out text needs +1px of top padding.** Swing centres text by the font's
**em box**, and the RuneScape font's ink floats high inside it, so every em-centred
control reads a pixel or two high (measured: field 1px, its placeholder 2px, dropdown
1px). Paying that pixel in asymmetric padding centres the INK, which is what the eye
reads — the same principle as OsrsLabel's measured-ink baseline and the bars', now on the
surfaces Swing lays out. Placeholders draw at the field's own `getBaseline(w, h)` rather
than a hand-guessed offset, so they land exactly where the text they stand in for will.

**Swing text needs `OsrsSkin.crisp(component)`.** Setting the antialias hint on the
Graphics is not enough — Swing re-applies the look-and-feel's own setting over it, which
smeared the pixel font with LCD subpixel fringes (measured: red `#581313` / blue
`#13134C` dabs around every glyph, and only 27 solid glyph pixels where there should be
194). The per-component client property is the one Swing honours. Any surface that lets
SWING draw its text — fields, combo boxes, cell renderers — must call it; atoms that
paint their own strings set the Graphics hint instead. `DesignLabRenderTest` pins this by
asserting every pixel of a filled field is one of the field's palette colours: a blend
fails the test.

`StoneScrollBarUI` and `StoneComboBoxUI` skin the **real Swing components** rather than
replacing them — five modules already use `JComboBox` and several use `JTextField`, so a
migration is a styling swap, not a rewrite. The lab demos the scrollbar as a bare
`JScrollBar`, never a nested `JScrollPane`: the shell owns the one scroll surface, and
wiring the skin into it is a phase-4 decision.

**Phase 4 — the migration pilot is live: Dailies (New).** The Dailies tab re-clothed in
the skin, kept BESIDE the classic one (nav row "Dailies (New)", `dailiesNew` toggle) so
the two can be compared in-client; when Luke signs off, `DailiesNewTab` replaces
`DailiesTab` and the scaffold module goes. Architecture that must survive the swap: the
pilot owns NO brain — `DailiesModule` keeps all detection, run state, overlays and
persistence, and the skinned tab talks to it through the same package-private seam the
classic tab uses, plus a `tabListeners` hook on `rebuildTab()` so run events reach both
tabs. Two atoms were added for it: `StoneTile` (a status tile in the checkbox's flat
grammar — a tile reports, it is not a button, so it must not read like the chamfered nav
stones; dim = sunken recess + ghosted icon) and a richer `StoneChecklist.row(...)`
overload (caller-owned status colour, tooltip, badge icon, toggle callback — the dailies
scale: green claimable / orange short / warm-faint done / neutral-grey locked / muted "?"
unknown, unticked = FAINT). Renders: `dailies-new-{stone,mystic}.png` + `dailies-new-run.png`
beside the classic `dailies-tab.png`.

Still open: the fixed-mode mottled outer frame if anyone wants it, and the rest of the
migration after the pilot verdict.

## Fidelity assessment — how close can the sidebar get?

**Pixel-exact:** fonts (the real ones ship with RuneLite), all colors, the box border at
any size, text shadows, icons, the two-column stat rhythm (game content is 184px wide;
our panel is 225px — the same layout with slightly wider boxes, which the 9-slice design
absorbs).

**Close approximation:** the flat `BACKGROUND` vs the game's faintly textured backing
(~95% read; a texture tile cropped from the wiki image is possible if wanted); the outer
window frame and tab strip (recreatable, but their mottled stone texture wants bundled
art rather than paint — phase 2).

**Stays RuneLite:** scrollbars, tooltips, the nav shell around the skinned content, and
anything at less than 1px — until/unless those are skinned deliberately.

**Variant note:** Luke's screenshot is the resizable-mode look (darker, translucent box
fills over the game world). This system is built from the fixed-mode opaque stone — the
canonical "stonework" — and the fill is a single token, so a darker variant is a palette
swap, not a redesign.

## Test surface

`com.ironhub.modules.designlab` — nav row **Design lab**, config toggle `designLab`.
Its tab is every atom, built purely from the design system (sample data, labelled as
such — honesty rule), inside the thin `StoneFrame`. It renders in whichever clothing the
**`osrsTheme` setting** picks (plugin settings, deliberately not a sidebar control —
Luke's call, since it is a preference, not a per-session view); flipping the setting
rebuilds the tab on the EDT via `ConfigChanged`, which is how the two are compared.
Renders: `build/reports/designlab-{stone,mystic}.png`.
