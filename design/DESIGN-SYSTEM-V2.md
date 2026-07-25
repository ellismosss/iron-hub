# Iron Hub — Design System V2

The contract every Iron Hub interface obeys. It exists because the panel drifted:
the same idea got drawn several ways across 27 modules, and each new surface needed
another round of tweaks to look like its neighbours. V2 replaces judgement with
rules, and rules with tests.

**Status (2026-07-25):** all 31 atoms are built in three themes, enforced by
`V2RulesTest`, and presented in **Design lab V2** (the Design lab's first view; a chip switches back
to the V1 atoms). *No module is migrated until Luke says he is 100% happy with the
atoms* — his gate, 2026-07-24.

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

---

## 7. Layout

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

**Hover exists only where the curated art has a `_hovered` sprite.** OSRS has no mouse
pointer, so the game never drew most of them, and inventing a tint is how two buttons
side by side end up behaving differently. `NineSlice.variant()` throws when asked for a
state the art lacks — the system cannot offer what it doesn't have.

| State | How it reads |
|---|---|
| Hover | the `_hovered` sprite, where one exists; otherwise no change |
| Selected | the `_hovered` sprite **and** the label switches to `HEADING` orange (Luke's call — the art's brighten alone is too subtle on a chip row) |
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
| `Tooltip` | Card slice + `DETAIL` label |

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
| `V2Surface` | Card, Well, Frame | `enter_wilderness_teleport` · `equipment_metal_corner_*` + `equipment_edge_*` · `bottom_line_mode_side_panel_*` | plain, hovered |
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
| `V2Hero` | Hero | Card + Label + ProgressBar | — |
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
