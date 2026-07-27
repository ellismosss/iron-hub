# Iron Hub — Design System V2

The contract every Iron Hub interface obeys. It exists because the panel drifted:
the same idea got drawn several ways across 27 modules, and each new surface needed
another round of tweaks to look like its neighbours. V2 replaces judgement with
rules, and rules with tests.

**Status, 2026-07-26.** The atoms are built in three themes, enforced by
`V2RulesTest`, and shown in the Design lab under three chips — **Atoms**,
**Goals**, **V1**. **Five of the six hub pages are migrated**; what remains is
Settings (the Design lab, which deliberately still shows V1) and the V1 residue
listed below.

| Hub page | Sections | Migrated |
|---|---|---|
| Goals | Goals | 2026-07-25 |
| Gear & Combat | Gear & Combat (incl. the vendored `LoadoutLabPanel`), Slayer, Loot & supplies | 2026-07-25 |
| Dailies | Dailies, Farm runs, Hunters' Rumours, Port tasks | 2026-07-26 |
| Progression | Collection log, Combat achievements, Gear (chart + library), House, Boats, Achievement diaries, Quests, Clues & STASH, QoL checklist | 2026-07-26 |
| Bank | Bank & banked XP, Bank space saver, Where's my stuff, Money making, Supplies runway, Death recovery | 2026-07-26 |
| Settings | Design lab | — (shows V1 on purpose) |

**Known V1 residue in migrated pages**, so the table above is not read as more
than it is: `GoalsHubTab` still holds a `StonePanel`, two `StoneButton`s and two
V1 bars; `SlayerTab` two `StoneMeter`s, a `StoneButton` and two
`StoneComboBoxUI` pickers; `LoadoutLabPanel` one `StoneCheckbox`. All are
one-line swaps that the passes above simply did not reach.

**The strategy changed part-way through.** The Goals hub was first rebuilt from
V2 atoms in the lab (`GoalsV2View`). It did its job — it found five missing
atoms and several wrong defaults, listed in §13 — but rebuilding screens in the
lab from sample data converged badly: every round corrected an invention of mine
rather than a real module's real problem. **The modules get rebuilt directly**,
and the lab shows one atom in every state. Luke's call, 2026-07-25.

### What the page passes changed

- **The atom pass** built the set. **The module pass** (Goals, Gear & Combat,
  Slayer, Loot & supplies) added two surfaces — Slab and Chip — rewrote the
  Dropdown, and changed three rules written before any real screen tested them:
  the Frame moved from per-view to per-hub-page (§7), surface insets stopped
  being square (§4), and controls grew (§4).
- **The page passes** (Dailies, Progression, Bank) were mostly about
  **deleting duplicates rather than converting them**. The plugin had FOUR
  hand-painted tile classes — `IconTile`, `ItemTile`, `StoneHubTile` and the
  Dailies/Farm-runs pair — plus three composites that painted the Tile surface
  and a progress bar by hand. Every generic one is deleted into `V2Tile`, which
  is now the only tile in the plugin; the composites (`CaProgressTile`,
  `ClogTabTile`, the banked-XP skill strip) keep their arrangement but paint
  `V2Surface.paintTile` and hold a METER atom. `V2Tile` and `V2ChipRow` grew to
  carry what those classes had — see §12.
- **The atom's reading wins over the module's.** Dailies and Farm runs each had
  their own status colours; on `V2Tile` green means DONE and orange ACTIONABLE
  NOW, so a claimed daily now reads green where V1 painted a *claimable* one
  green. Say so out loud when a port flips a colour — it is a decision, not a
  bug.
- Every render shown for review is **Vanilla**. `IronHubConfig.osrsTheme()`
  defaults to MYSTIC, so a test taking its theme from a bare config writes a
  grey PNG; twenty-odd module tests now pin `OsrsTheme.STONE`.

---

## 0. The one-line version

> Everything is built from Luke's curated sprites, tiled never stretched, laid out
> on a five-step spacing scale, written in five text roles, and coloured with three
> status colours sampled from those same sprites. If an atom doesn't exist for what
> you're building, you add one to the system — you do not draw it in the module.

---

## 1. Sources of truth

| What | Where | Rule |
|---|---|---|
| Art | `design/use_these_sprites` | The **only** source of pixels. Hand-curated by Luke. |
| Shipped art | `src/main/resources/data/v2/` | Written by `tools/gen_v2_sprites.py`. Never hand-copied. |
| Index | `data/v2-sprites.json` | Generated. Records size + theme behaviour per sprite. |
| Atoms | `com.ironhub.ui.v2` | The only classes that touch sprites or paint. |
| Tokens | `com.ironhub.ui.v2.V2Tokens` | The only source of sizes, spacing, fonts and colours. |

Nothing is drawn by hand. No `drawRect`, no `fillRoundRect`, no gradient, no derived
border. If the curated set can't express something, the answer is either a new
composition of existing sprites (see §9) or a sprite Luke pulls — never an invention.

**To add or change art:** drop the PNG into `design/use_these_sprites/<family>/`, run
`python3 tools/gen_v2_sprites.py`, commit both the source and the generated output.
The generator fails if a folder is unmapped, so a new family is never silently lost.

---

## 2. Tiling — the rule that isn't a preference

**Sprites tile. Sprites never scale.**

Every stone surface in the set is a noise texture. Measured 2026-07-24: no two
adjacent pixel columns of `regular_large.png` are identical. Scaling a middle region
therefore duplicates *specific* columns and turns the sprite's 1px grain into visible
2px vertical bands — the surface stops matching the button next to it, which is
exactly the "everything looks slightly different" problem.

`NineSlice` is the only class allowed to draw a sprite at a size it didn't come in.
It cuts nine regions and repeats each along its own axis, always 1:1 source-to-screen.

**A slice cuts from the sprite's INK, not its canvas.** `NineSlice` slices
`V2Sprites.trimmed()` — the alpha bounding box. `ui/buttons/button` is a 35x35
canvas whose ink runs rows 5..29 only, so slicing the canvas at 5 took two rows
of pure transparency as the top and bottom edges and tiled the whole button —
both rounded ends included — through the middle. That is why chips and the
pressed button both lost their lower half (2026-07-25). Measured: `button` and
`button_hovered` are the only slice sources with padding, so trimming is a
no-op everywhere else.

**Scaling an EMBLEM is allowed; scaling a SURFACE is not.** `V2Sprites.fitted`
resizes an icon to a given box, NEAREST NEIGHBOUR, because a row of emblems
pulled from different families arrives at different sizes and reads as unrelated
icons. A picture is not a tiled texture. Smooth interpolation is banned — it is
what made the first nav row read soft.

`NineSliceTest.redrawingASpriteAtItsOwnSizeReproducesItExactly` is the proof: redraw
a sprite at its own dimensions through the slicer and demand pixel identity. That
holds for a tiling implementation and fails for any scaling one.

The two slice families:

| Family | Source | Inset | Used for |
|---|---|---|---|
| Card | `ui/buttons/enter_wilderness_teleport` (+`_hovered`, all three themes) | 9 | Card, Chip, Tab body, Tooltip, ScrollBar thumb |
| Frame | `ui/borders/equipment_metal_corner_%s` + `equipment_edge_%s` (+`_hovered`) | 9 | Well, TextField, list recesses |
| Panel | `ui/borders/bottom_line_mode_side_panel_*` | 32 | the outer panel border |
| Button | `ui/buttons/regular_large` | 8 | the text button |

---

## 3. Themes

**A theme gets the pack's art where the pack ships it, and vanilla everywhere else.**
That is how a resource pack behaves in game: it overrides the sprites it re-draws and
leaves the rest of the interface alone.

Three themes as of 2026-07-25 — vanilla, Mystic, and **Dark Vanilla** (Luke: "I wanted
a dark-mode that looked exactly like Vanilla"). 452 sprites: 370 vanilla, 92 mystic,
160 dark. 82 have no vanilla original at all — pack-only families, shared by every
theme and flagged `packOnly` so the gallery never implies they are the game's own.

The rule lives in the generated index (`variants: ["vanilla","dark"]`), not in a
runtime filename probe. V1's mystic-falls-back-to-vanilla lookup meant "is this
themed?" could only be answered by trying, and the answer changed silently whenever
art moved.

**Coverage is partial, and that is visible rather than mysterious.** Dark Vanilla
re-skins every surface the atoms are built on (the generator fails if one goes
missing), but it ships no tab art — so tabs render vanilla brown in a dark panel. The
gallery footer states how much of the panel each theme re-skins.

**Known deviation:** nine sprites have variants of differing canvas size, because the
dark pack trims transparent padding (`..._side_panel_edge_top` is 32x20 where vanilla
is 32x32, with the ink at the same offset; `combat_style_*` is genuinely 1px smaller).
`w`/`h` follow vanilla and the generator reports every one.

**Text is not themed.** Fonts and colours are global, exactly as a resource pack
behaves in game: it re-sprites the interface and leaves the text rendering alone.

---

## 4. Spacing — five steps, no others

| Token | px | Origin | Use |
|---|---|---|---|
| `TIGHT` | 2 | the checkbox highlight band's own gap | inside a control: glyph to its text |
| `ROW` | 4 | the game's list rhythm | between rows in a list |
| `PAD` | 6 | clears the art's own bevel without doubling it | content inset inside a surface |
| `SECTION` | 12 | the game's text line pitch | between sections |
| `BLOCK` | 24 | two line pitches | between major blocks of a screen |

A surface's 9px art inset is **structural, not spacing** — `PAD` sits inside it. Content
is therefore 15px from a Card's outer edge, and that is the same on every Card in the
plugin.

Nothing uses a value that isn't on this scale. The enforcement test fails the build on
any other number in an `EmptyBorder`, strut or gap.

### Surface insets — symmetric per axis, NOT square

A surface stands its content off its own art. The horizontal figure clears the
art and adds a spacing step; the vertical figure is **tighter**, because every
one of these edges is a corner-and-edge feature and a one-line row does not need
the horizontal number repeated above and below it (Luke, 2026-07-25).

| Surface | horizontal | vertical | why |
|---|---|---|---|
| Card | `SLICE_INSET + ROW` = 13 | `SLICE_INSET + TIGHT` = 11 | the 9px bevel is structural — content inside it sits ON the art |
| Tile | `NAV_TILE_INSET + PAD` = 12 | `ROW` = 4 | the chamfer eats corners, not edges |
| Slab | `NAV_TILE_INSET + PAD` = 12 | `ROW` = 4 | same, for the engraved notch |
| Well | `V2Well.CAP + PAD` = 10 | 10 | its rows ARE its content; it stayed square |
| Well, as a LIST | `CAP + TIGHT` = 6 | 6 | a list wants to feel dense — `V2Checklist`, `V2Table`, and every results well |

`V2SurfacesRenderTest.everySurfaceInsetsItsContentSquarely` guards what still
holds: content never sits on the art, each axis is symmetric, and vertical never
exceeds horizontal.

### Fixed sizes

| Token | px | Why |
|---|---|---|
| `PANEL_WIDTH` | 225 | platform constraint, never widen |
| `CONTENT_WIDTH` | 217 | 225 minus the panel's own 4px edges |
| `ROW_HEIGHT` | 20 | 18px checkbox/tick + 1px breathing above and below |
| `CONTROL_HEIGHT` | 26 | chips, toggles, dropdown rows. Was 22 — the label sat hard against the chip's rounded ends (Luke, 2026-07-25) |
| `BUTTON_HEIGHT` | 28 | `regular_large`'s native height |
| `LINE_PITCH` | 12 | the game's own, measured — not FontMetrics height, which runs 4px/line tall |
| `ICON` | 16 | inline item sprites |
| `TILE_ICON` | 22 | tile emblems |

### The inventory's spacings

The game's own, measured 2026-07-25 and pinned by `DesignLabInventoryTest`.
They are not on the five-step scale and never will be — this is one imported
widget reproducing a fixed piece of the game's interface, not a surface the
system lays out.

| What | px | Note |
|---|---|---|
| Panel | 190 x 276 | 190 is the game's own; 276 is the width's rule applied downward. A derived size came out 180x266 and read visibly smaller than the real thing side by side |
| Cell | 36 x 32 | what `ItemManager` hands back |
| Pitch | 42 x 36 | 4 columns, 7 rows |
| Gap between columns | 6 | pitch minus cell |
| Gap between rows | 4 | pitch minus cell — narrower than the column gap, and that asymmetry is the game's |
| Frame band | 7 (6 in Mystic) | measured per theme from the sprite's opaque band, never assumed |
| Margin | 7 | one band's width, on all four sides; `7 + 7 + (3x42 + 36) + 7 + 7 = 190` corroborates the panel width, and `7 + 7 + (6x36 + 32) + 7 + 7 = 276` sets the height |

The grid is **centred** in whatever the bands leave rather than pinned to a
hardcoded origin: Mystic's 6px bands would otherwise shift every margin.

The panel's height is the one number here not taken straight from the game. At
the 261 the width's source implied, seven rows need 248px against 247 available
— the grid sits flush against both bands with no air at all. 276 gives it the
same 7px of padding above and below that it already had left and right.

---

## 5. Type — five roles, no others

| Role | Font | Colour | Use |
|---|---|---|---|
| `HEADING` | bold | `HEADING` orange | section titles, hero names. Heading position only. |
| `BODY` | regular | `TEXT` | the default. Row labels, prose. |
| `VALUE` | regular | `STRONG` white | a number or state the row exists to report |
| `DETAIL` | small | `TEXT` | secondary line under a row |
| `FAINT` | small | `FAINT` | provenance, disabled, "as of" lines |

14px is the floor; nothing smaller than the small font ships.

All text paints through the V2 `Label` atoms. A raw `JLabel` positions the pixel font
by its wrong FontMetrics and clips glyph bottoms — "Dragon defender" once rendered as
"Uraaon defender". Swing-drawn text (fields, combo boxes) needs `OsrsSkin.crisp` and
+1px top padding; the atoms do both.

---

## 6. Colour — three status colours, sampled not invented

| Token | Value | Sampled from | Means |
|---|---|---|---|
| `DONE` | `#18BF1B` | `ui/ticks/checkmark_small` ink | done, owned, complete |
| `ACTION` | `#FF981F` | the game's interface orange | actionable **now** |
| `BLOCKED` | `#FF0000` | `ui/ticks/red_cross_small` + the locked padlock | blocked, missing, over threshold |
| `HEADING` | `#FF981F` | same orange, heading position | structure |
| `TEXT` | `#B8AC9C` | — | the default. Not green. |
| `STRONG` | white | — | the value a row reports |
| `FAINT` | `#645E55` | — | provenance and disabled |

Rules:

1. **Colour is never decoration.** If a thing isn't done, actionable or blocked, it is
   `TEXT`.
2. **Orange is disambiguated by position**, not by shade: in heading position it is
   structure; in a row's status position it means actionable now. It is never used to
   emphasise something that is merely important.
3. **Green is not a value colour.** V1's stat-value green made every ordinary number
   read as an achievement — this is the "should be light not green" report.
4. **Progress bars carry no status.** The curated set has one fill, and it is green; a
   bar means progress and nothing else. Blocked or short is said in the text beside it,
   in `BLOCKED`.
5. **Unknown is never coloured.** It renders as silence or "?" in `FAINT`.

**`FAINT` was 12 levels too dark.** It documents itself as "MUTED dimmed by the
same ratio UiTokens uses from body to faint" — that ratio is `0x6B/0x8C` = 0.764,
and the constant sat at 0.545 of MUTED. Against the stone panel that is ~40
levels of separation and faint lines got lost in it (Luke, 2026-07-25). Now
`0x8D8377`, which IS the documented ratio and clears the panel by ~79. It is
`OsrsSkin.FAINT`, so this lifted every faint line in the plugin, V1 and V2.

### Derived colours

Everything above is sampled. These five are DERIVED, each from the theme's own
colours so the three packs stay in step without a table to maintain:

| Token | What | Why it is derived |
|---|---|---|
| `HIGHLIGHT` | white, alpha 20 | the pointer wash. The curated `_hovered` sprites are the PRESSED look, so hover needed something of its own |
| `SHADOW` | black, alpha 120 | the opposite: an unavailable Tile sinks. A dark ring alone is not enough — the FILL has to darken |
| `dimEdge(theme)` | `edgeLight` half way to `edgeDark` | the Frame's and the Tile's border. At full strength it competed with its own contents |
| `statusEdge(theme, c)` | 45% back toward the panel | a status ring at full strength is the loudest thing on a grid, which inverts rule 1 |
| `BAR_FILL` / `BAR_BLUE` | V1's bar greens and blues | a bar is not an achievement. `DONE` green read as one; blue means plan progress, green means possession |

**Highlight means the FILL. Border means the OUTLINE.** Luke's vocabulary, and
the two are separate arguments to every surface painter. Do not conflate them.

---

## 7. Layout

- **Every hub PAGE sits inside a Frame — one per page, not one per view.**
  `IronHubPanel.hubPage()` wraps the page's whole module stack in
  `V2Surface.frame(theme)`. Gear & Combat holds three modules and they share a
  single frame (Luke, 2026-07-25); a frame each drew three edges down a page
  that is one thing. **A module must not frame itself** — Goals and Loadout both
  did, and nested frames draw two edges.

  It is the game's own thin side-panel edge (`StoneFrame`: 1px dark over 1px
  light, 8px stepped corner chamfer), with its light line dimmed halfway to its
  dark one via `V2Tokens.dimEdge` so it frames without competing. The Tile wears
  the same dimmed edge, so a Tile inside a Frame reads as one system.

  A view inside the frame adds no horizontal inset of its own. Measure the frame
  in the **panel** render, never in a standalone tab render: a tab rendered alone
  is 225 wide, the same tab in the panel is 217, and reasoning from the standalone
  figure produced a wrong 4px gutter that had to be undone.
- **225px, one column, vertical scroll only.** Content that doesn't fit is two-lined,
  tooltipped or truncated — never widened. No nested scroll panes.
- **One left edge.** Everything in a section aligns to the same x. Indentation is one
  step of `SECTION`, maximum two levels deep.
- **Lists cap at 20 rows** with an honest `+ N more` line (Luke, 2026-07-25;
  it was 50). The Dropdown caps at 20 too — `V2Dropdown.MAX_ROWS`. This is
  a performance rule as much as a design one: rendering hundreds of sprite rows per
  rebuild was a measured freeze contributor.
- **Navigation depth ≤ 2.**
- **A row is one line of content plus at most one `DETAIL` line.** More than that is a
  Card, not a row.
- **Vertical BoxLayout children claim `LEFT_ALIGNMENT`.** One centre-aligned child
  drifts every sibling.
- **UI-less custom components override `getMinimumSize()`.** The default is the current
  size — 0×0 before layout — and BoxLayout derives row alignment from child minimums,
  which cuts children to half height in the real client while the multi-pass test
  renderer self-heals it.

---

## 8. States

**A wash clips to the art, never to the component box.** A `fillRect` lights the
transparent air outside a chamfered corner or a rounded end cap and leaves a halo
on the panel behind — caught three times (the Tile, the Value dropdown, and the
tab). Composite `SrcAtop` over a copy of the surface, or fill its silhouette.

**Hover exists only where the curated art has a `_hovered` sprite.** OSRS has no mouse
pointer, so the game never drew most of them, and inventing a tint is how two buttons
side by side end up behaving differently. `NineSlice.variant()` throws when asked for a
state the art lacks — the system cannot offer what it doesn't have.

| State | How it reads |
|---|---|
| Hover | the `HIGHLIGHT` wash, clipped to the art's own pixels (`NineSlice.highlighted`, `V2Well.paintLit`, `StoneNavButton.paintSilhouette`). NEVER the `_hovered` sprite — that art is the PRESSED look, and showing it on hover announces a press that has not happened |
| Pressed | the `_hovered` sprite, from the SAME family as the rest state. A third family borrowed for the pressed state reads as a different control mid-press |
| Selected | the `_hovered` sprite **and** the label switches to `HEADING` orange (Luke's call — the art's brighten alone is too subtle on a chip row). A Tile shows it as the brighter BEVEL plus the wash, never as a brighter fill |
| Unavailable | the `SHADOW` wash over everything, applied LAST so the border and the emblem sink with the fill |
| Disabled | the art's own disabled sprite where it exists (checkbox), else `FAINT` text and no hover |
| Locked | the locked sprite, `FAINT` label, reason in the tooltip |
| Unknown | `FAINT` "?" or nothing at all — never a zero, never an invented value |

---

## 9. Composition — which atom for which job

Four controls have no sprite of their own. They are **compositions of curated pieces**,
defined once here so they can never be re-improvised per module:

| Control | Built from |
|---|---|
| `TextField` | Frame slice (well) + `icons/search/search_1` + a painted caret |
| `Dropdown` | `Button` + `icons/chevron/gray_down_single`, popup on a Card |
| `ScrollBar` | Frame slice trough + Card slice thumb + `ui/arrows` buttons |
| `Tooltip` | RuneLite's own chrome — see below |

**The Tooltip is the one surface wearing no OSRS art.** It takes RuneLite's
`ColorScheme` — the LAF's `ToolTip.background` is `lighten(DARK_GRAY, 4%)` — with
V2's detail font and no border. A tooltip is client chrome: it floats above the
panel rather than sitting in it, and skinning it made it read as a card that had
come loose (Luke, 2026-07-25). This is a deliberate exception to §1.

**Never hand-roll an atom's job.** A row of toggles is a `ChipRow`, not three buttons
with manual selection state. A framed list is a `Well`, not a `Card` with a border. If
two modules need the same thing and no atom fits, the atom is missing — add it here
first.

---

## 10. Enforcement

`V2RulesTest` fails the build on:

- a colour literal (`new Color(...)`, `Color.RED`) anywhere in `com.ironhub.ui.v2` or a
  migrated tab — colours come from `V2Tokens`
- a raw font (`new Font`, `FontManager.*`) outside `V2Tokens`
- hand-drawn borders: `drawRect`, `fillRoundRect`, `setBorder(new LineBorder(...))`
- an `EmptyBorder`, strut or gap using a number that isn't on the spacing scale
- a raw `JLabel` in a migrated surface — text goes through the `Label` atoms
- an `ImageIO`/`getResource` call for art outside `V2Sprites`

The message names the atom or token to use instead. Scope starts at the V2 package and
widens to each tab as it migrates, so the rules can't rot ahead of the code.

---

## 11. Adding an atom

1. Check §9 — is it really missing, or is it a composition?
2. Build it in `com.ironhub.ui.v2`, sprites via `V2Sprites`, sizes and colours via
   `V2Tokens`, surfaces via `NineSlice`.
3. Support only the states the art has (§8).
4. Add it to **Design lab V2** in every state it offers.
5. Write the render test; look at the PNG in both themes before calling it done.
6. Document it in the atom table below.

---

## 12. The atoms

All in `com.ironhub.ui.v2`, all shown in **Design lab V2**.

| Class | Covers | Sprites | States |
|---|---|---|---|
| `V2Surface` | Frame, Tile, **Slab**, Card, Well, **Chip** | `StoneFrame` · `StoneNavButton.paintSlab` · **`StoneBorder`** (all hand-painted) · `enter_wilderness_teleport` · the field well · `ui/buttons/button` | plain, hovered, pressable, wash-hover (`washHoverable()` — the subtle inset wash for a surface whose hovered ART means "pressed in", paired with `setLit` while open) |
| `V2Divider` | Divider | `..._side_panel_edge_horizontal` (rows 14..19 only) | — |
| `V2Label` | Label, WrappedText | — (text) | heading, body, value, detail, faint, status |
| `V2Layout` | columns, rows, gaps | — | — |
| `V2Button` | Button | `regular_large` | plain (the art has no other) |
| `V2SpriteButton` | IconButton, UtilityButton, Stepper, ArrowButton, WikiButton | `ui/buttons_square/*`, `ui/plus_minus/*`, `ui/arrows/*`, `icons/wiki/*`, `ui/buttons/*` | whatever `_hovered` / `_selected` the art has. `fit(box)` scales an oversized emblem (§2); `letter(s)` draws a character over the art for a mark the set lacks |
| `V2Checkbox` | Checkbox | `square_bordered_checkbox` | off, on, locked, disabled, disabled-on. `labelColor` for a status the caller owns (the dailies scale); `badge(icon)` for a trailing mark; **null text = the box alone**, for a row that lays itself out |
| `V2ChipRow` | ChipRow, **action chip** (`action()`), **latching chip** (`toggle()`, optionally with an icon, a font, and stretched to its cell) | `ui/buttons/button` | unselected, selected (lit art + orange label), hovered (wash), highlighted (green label) |
| `V2Tab` | Tab | `tag_tab`, `tag_tab_active` | plain, active |
| `V2Tile` | Tile | Card slice + `checkmark_small` | plain, selected, owned, `Status` + `progress`. Takes any `java.awt.Image` (the tabs draw through `SpriteCache`, whose sprites arrive from `getScaledInstance`); `emblem(img)` swaps a late arrival in; `width(px)` for a tile wider than it is tall; `captionLines(n)` wraps and **clamps** the caption (a label paints every line it holds, so an unclamped one bled over the row beneath); `badge(n)` a corner count; `placeholder(code)` when there is no art; `onRightClick` a context menu; `captionInside()` paints the caption ON the art — bottom-anchored, bold, the emblem centres in the band above it, tile = art height (the clog page grid; Luke, 2026-07-27); `corner(s)` a detail-font note top-right, taking the owned tick's spot when both are set (the two-segment overload colours each half — the clog count grammar: obtained red 0 / orange filling / green done, "/total" orange-until-done); `captionStatus(c)` the caption in DONE/ACTION/BLOCKED (V2Label.status's guard — the clog grid's orange-until-done); `card()` wears the Card art instead of the chamfered stone (washes go flat-inset, status rings don't draw — say progress with the meter); `meter(f)` a plain 5px METER strip along the bottom, no notches (the card tile's progress readout) |
| `V2ItemSlot` | ItemSlot | `icons/equipment/slot_*` | empty, filled, selected |
| `V2Glyph` | StatusGlyph, Lock, Star, Chevron, SortArrow | `ui/ticks/*`, `icons/padlock`, `icons/star/*`, `icons/chevron/*`, `list_sorting_arrow_*` | — (display only) |
| `V2ProgressBar` | ProgressBar | `progress_bar_grey` + `progress_bar_green` | green only; NaN = empty trough |
| `V2Hero` | Hero | Card + Label + ProgressBar (value ON the bar) | — |
| `V2Inventory` | Inventory | panel edges + `inventory_background` | 4x7, the game's own 190x276 |
| `V2Checklist` | Checklist | Well + rows | hover band |
| `V2Table` | Table | — (layout) | `right()` for numeric columns |
| `V2EmptyState` | EmptyState | Well + Label | empty, unknown |
| `V2TextField` | TextField | Well + `search_1` | idle, typed. **`plain(...)` drops the magnifier** — a note, a number or a name is not a search, and the icon's column squeezes a narrow box until its digits clip |
| `V2Dropdown` | Dropdown | Well + arrow | closed (one row), open (grows in place, max 20 rows). `width(px)` pins it for a shared row — `setPreferredSize` cannot, the size overrides ignore it |
| `V2ScrollBarUI` | ScrollBar | Well trough + Card thumb + arrows | — |
| `V2Tooltip` | Tooltip | Card + Label | — |

### The six surfaces

Every one is a container; they differ only in their edge, and — except the Card
and the Chip — they share the same Card grain, so they read as one family.

| Surface | Edge | Use |
|---|---|---|
| Frame | 1px dark over 1px dimmed light, 8px stepped chamfer | one per hub page (§7) |
| Slab | engraved, corner-notched (`StoneBorder`) | the V1 stone box: stat boxes, module headers, titled blocks |
| Tile | chamfered stone (`StoneNavButton.paintSlab`) | grouping blocks, pressable rows |
| Card | sprite art, 9px bevel | the one live readout on a page |
| Well | sunken field texture | lists, results, fields |
| Chip | rounded `button.png` | transient notices; a row of choices is `V2ChipRow` |

**Both a class and a rule:** `V2Layout` is the only way V2 code makes a column,
a row or a gap. A bare `Box.createVerticalStrut` is CENTER-aligned, and BoxLayout
aligns a column by making its children's alignment points coincide — so one
centre-aligned child, even a zero-width spacer, shifts every left-aligned sibling.
That bug pushed whole tile rows 56px right in the first render of Design lab V2.

---

## 13. What the Goals rebuild found (2026-07-25)

`GoalsV2View` reconstructed the Goals hub from V2 atoms as the system's first
real workload. **It is kept as a reference, not as a destination** — the modules
get rebuilt directly from here (see the status note at the top). What it exposed
is the useful part:

### Missing atoms

1. **Row.** Glyph, label, glue, trailing controls. Eleven times in one screen,
   and in every module the plugin has. §9 says "never hand-roll an atom's job"
   and this is the job with no atom. **Build this first.**
2. **Section header.** A heading plus a gap by convention, retyped at every use,
   so the spacing above and below will drift.
3. **Disclosure.** A row that opens: chevron, click target, indent, and the
   opened content. Goals, diaries, the collection log and the bank all need it.
4. **Stat tile.** Two side by side is how every hub starts. V1 has `StatBox`;
   V2 has to hand-build it from a Tile and two labels, and the label truncates
   at 217px because nothing owns the two-line wrap `StatBox` does.
5. **A pin affordance.** The curated set has no pin sprite, so V1's pin/remove
   controls have nothing to draw with. `SQUARE_SMALL` with no emblem in it reads
   as a hole — do not use a blank square as a button.

### Rulings still needed

- **Priority has no colour.** V1 marks it with a 1px coloured left strip. §6 has
  three status colours and priority is not one of them, so the rebuild carried
  it in font weight alone, which is weaker than V1.
- **`V2Table` cannot span.** A route row is a line PLUS a meter beneath it,
  which fixed columns cannot express — so goal lists are columns of
  compositions rather than tables. Either the table grows a spanning row or the
  Row atom subsumes it.

### What the module pass answered (2026-07-25)

- **Row** — still no atom, but the need shrank: `V2Table` covers aligned rows
  (Loot's two lists), `V2Checklist` covers checkable ones (Gear & Combat's
  toggles), and a Tile covers pressable ones. Build it when something needs a
  shape none of those give.
- **Stat tile** — `V2Surface.slab` plus two labels. Goals and Slayer both do it
  by hand; still a candidate atom.
- **Pin affordance** — answered generally rather than specifically:
  `V2SpriteButton.letter(s)` draws a character over the art when the curated set
  has no sprite for a mark. Goals' wiki link is a "W" on the empty checkbox.
- **Priority has no colour** — resolved as: it takes the Tile's BEVEL, pulled
  back through `V2Tokens.statusEdge` the way every status ring is. Same pixel of
  colour V1 put down one side, traced round the chamfer instead.

---

## 14. What the passes cost

### The module pass (2026-07-25)

Four mistakes, each of which cost a round trip, and each avoidable:

1. **Hand-rolling a chip, three times.** Route, then Open DPS calc, then Save
   setup and the style row. Each was built from the chip *surface* rather than
   the chip *atom*, so none of them followed `CONTROL_HEIGHT` when it changed and
   all three looked subtly wrong beside real chips. §9 already said this. The
   fix is `V2ChipRow.action(...)`, which returns the very same `Chip` class.
2. **Guessing which component was on screen.** `sectionPlate` vs `moduleHeader`,
   and the in-view "GOALS" header vs the module plate — both converted the wrong
   one first. A pixel sample of the render answers it in one step.
3. **Measuring a tab standalone instead of in the panel.** A tab alone is 225px,
   the same tab in the hub is 217. `DesignLabPanelRenderTest` says in its own
   header that V2 changes are verified against the panel render; twice they
   weren't.
4. **Reasoning about client behaviour from a headless render.** Wiki gear
   "wasn't always visible" in a render with no slayer task set — in the client,
   a task alone filled it.

### The page passes (2026-07-26)

5. **Converting a duplicate instead of deleting it.** The first instinct on
   every hand-painted tile was "put it on the V2 surface". The right move was
   "grow `V2Tile` and delete the class" — and the atom could cover it EVERY
   time, once asked. Four tile classes died this way. **Before writing a tile,
   a chip or a bar, check whether the atom can grow to carry it.**
6. **Trusting a downscaled render.** A 4,275px tab shown at 2,000px looked like
   every row was overlapping; at full resolution it was correct. Crop and view
   at 1:1 before diagnosing a layout bug.
7. **An atom detail wrong everywhere at once.** `V2TextField` drew the search
   magnifier unconditionally, so a note, a page number and a supply target all
   wore a search glyph — and the icon's reserved column squeezed a 40px numeric
   box until its digits clipped. One atom, seven wrong call sites, four passes
   before anyone looked at a narrow one. `plain(...)` is the fix. Chip labels
   were hard-clipping at the art's edge for the same class of reason and are
   `squeezable()` now.

### Traps that bite silently

**A row holding a Dropdown must follow the dropdown's height.** It grows in
place when opened, so a row pinned to one `CONTROL_HEIGHT` clips the open list.
Override the row's `getMaximumSize()` to its preferred height.

**`V2Surface` overrides `getMaximumSize()` to full width.** `setMaximumSize(
getPreferredSize())` on one is silently ignored and it stretches to fill its row.
Use a `FlowLayout` holder when a surface must keep its own size. `V2Dropdown`
has the same override — use `width(px)`, not `setPreferredSize`.

**A wrapped label paints every line it holds.** A tile reserves height for
`captionLines` and no more, so an unclamped caption bleeds over the row beneath.
`V2Tile` clamps; anything else wrapping into a fixed band must too.

**Gradle's incremental build hides a broken file it has cached.** A stray
character in `IronHubPluginTest` — the class IntelliJ's run configuration
launches — survived four green `./gradlew build`s and only surfaced under
`clean`. **Finish a pass with `./gradlew clean build -x javadoc`.**

**A container that listens for its own hover/click goes dead over its
content.** Swing hands a mouse event to the DEEPEST interested component and
never bubbles it — and a tooltip alone makes a label interested. So a Table's
band, a Checklist's band, or a pressable Card's click works only "beside the
text" (Luke hit this three separate times, 2026-07-27). The fix is
`MouseRelay.install(root)` — descendants' press/move events re-dispatch to
the root, converted; child actions still run first. The Table, the Checklist
and `pressable()` install it themselves; any new self-listening container
must too.

---

### The process lesson

Rebuilding a screen in the lab from sample data converged badly: each round
corrected an invention of mine (a hero bar V1 never had, a blank square where
V1 had a painted pin) rather than a real problem with a real module. **Rebuild
the module itself**, against its real data and its real V1 for comparison. The
lab's job is one atom in every state — it is good at that and bad at this.
