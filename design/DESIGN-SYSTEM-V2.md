# Iron Hub — Design System V2

The contract every Iron Hub interface obeys. It exists because the panel drifted:
the same idea got drawn several ways across 27 modules, and each new surface needed
another round of tweaks to look like its neighbours. V2 replaces judgement with
rules, and rules with tests.

**Status (2026-07-25, end of the atom pass):** the atoms are built in three
themes, enforced by `V2RulesTest`, and shown in the Design lab under three
chips — **Atoms**, **Goals**, **V1**.

**The strategy changed at the end of this session.** The Goals hub was rebuilt
from V2 atoms in the lab (`GoalsV2View`) as the system's first real workload.
It did its job — it found five missing atoms and several wrong defaults, listed
in §13 — but rebuilding screens in the lab from sample data turned out to be a
poor way to converge: every round corrected an invention of mine rather than a
real module's real problem. **From here the modules get rebuilt directly**, and
the lab goes back to being what it is good at: showing one atom in every state.
Luke's call, 2026-07-25.

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

### Fixed sizes

| Token | px | Why |
|---|---|---|
| `PANEL_WIDTH` | 225 | platform constraint, never widen |
| `CONTENT_WIDTH` | 217 | 225 minus the panel's own 4px edges |
| `ROW_HEIGHT` | 20 | 18px checkbox/tick + 1px breathing above and below |
| `CONTROL_HEIGHT` | 22 | chips, toggles — proved to 9-slice cleanly at this height |
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

- **Every view sits inside a Frame.** `V2Surface.frame(theme)`, wrapping the
  whole view, edge to edge in the 225px panel — the arrangement Design lab V2
  wears, and the one every migrated tab adopts (Luke, 2026-07-25). It is the
  game's own thin side-panel edge (`StoneFrame`: 1px dark over 1px light, 8px
  stepped corner chamfer), with its light line dimmed halfway to its dark one
  via `V2Tokens.dimEdge` so it frames without competing. The Tile wears the
  same dimmed edge, so a Tile inside a Frame reads as one system.

  The view must not add a horizontal inset of its own: the Frame carries the
  edge, and a second inset is what made the lab's frame 209px where the hub's
  is 217 (measured 2026-07-25). Vertical padding is still the view's.
- **225px, one column, vertical scroll only.** Content that doesn't fit is two-lined,
  tooltipped or truncated — never widened. No nested scroll panes.
- **One left edge.** Everything in a section aligns to the same x. Indentation is one
  step of `SECTION`, maximum two levels deep.
- **Lists cap at 50 rows** with an honest `+ N more — refine your search` line. This is
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
| `V2Surface` | Frame, Tile, Card, Well | `StoneFrame` (hand-painted) · `StoneNavButton.paintSlab` (hand-painted) · `enter_wilderness_teleport` · the field well | plain, hovered |
| `V2Divider` | Divider | `..._side_panel_edge_horizontal` (rows 14..19 only) | — |
| `V2Label` | Label, WrappedText | — (text) | heading, body, value, detail, faint, status |
| `V2Layout` | columns, rows, gaps | — | — |
| `V2Button` | Button | `regular_large` | plain (the art has no other) |
| `V2SpriteButton` | IconButton, UtilityButton, Stepper, ArrowButton, WikiButton | `ui/buttons_square/*`, `ui/plus_minus/*`, `ui/arrows/*`, `icons/wiki/*`, `ui/buttons/*` | whatever `_hovered` / `_selected` the art has |
| `V2Checkbox` | Checkbox | `square_bordered_checkbox` | off, on, locked, disabled, disabled-on |
| `V2ChipRow` | ChipRow | Card slice | unselected, selected (lit art + orange label) |
| `V2Tab` | Tab | `tag_tab`, `tag_tab_active` | plain, active |
| `V2Tile` | Tile | Card slice + `checkmark_small` | plain, selected, owned |
| `V2ItemSlot` | ItemSlot | `icons/equipment/slot_*` | empty, filled, selected |
| `V2Glyph` | StatusGlyph, Lock, Star, Chevron, SortArrow | `ui/ticks/*`, `icons/padlock`, `icons/star/*`, `icons/chevron/*`, `list_sorting_arrow_*` | — (display only) |
| `V2ProgressBar` | ProgressBar | `progress_bar_grey` + `progress_bar_green` | green only; NaN = empty trough |
| `V2Hero` | Hero | Card + Label + ProgressBar (value ON the bar) | — |
| `V2Inventory` | Inventory | panel edges + `inventory_background` | 4x7, the game's own 190x276 |
| `V2Checklist` | Checklist | Well + rows | hover band |
| `V2Table` | Table | — (layout) | `right()` for numeric columns |
| `V2EmptyState` | EmptyState | Well + Label | empty, unknown |
| `V2TextField` | TextField | Well + `search_1` | idle, typed |
| `V2Dropdown` | Dropdown | Card + chevron | closed, open |
| `V2ScrollBarUI` | ScrollBar | Well trough + Card thumb + arrows | — |
| `V2Tooltip` | Tooltip | Card + Label | — |

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

### The process lesson

Rebuilding a screen in the lab from sample data converged badly: each round
corrected an invention of mine (a hero bar V1 never had, a blank square where
V1 had a painted pin) rather than a real problem with a real module. **Rebuild
the module itself**, against its real data and its real V1 for comparison. The
lab's job is one atom in every state — it is good at that and bad at this.
