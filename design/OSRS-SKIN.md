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

## Typography

- Labels/values/body: RuneScape regular, 16px native, `LABEL`/`VALUE` color.
- Headers: RuneScape bold, `TITLE` color, centered.
- **Every string gets the +1,+1 black shadow** — this, more than anything, is what makes
  the text read as OSRS.
- RuneScape *small* exists as a token but is not used by default (Luke's 14px floor —
  its cap height reads smaller; his call whether the game's own small font is exempt).
- Glyph safety unchanged: ASCII plus `· … — ×`.

## Iconography

Wiki images only (standing rule), and the wiki hosts this interface's exact icons at
native 18×18: `Character_Summary_-_*.png` (combat, total level, total XP, combat tasks,
collections) plus the quest/diary spirals. Bundled under `data/icons/osrs/`. The stat
pattern is always **icon + green value** on one line under an orange label.

## Atom inventory (v1 — what the Design lab proves)

| Atom | What it is |
|---|---|
| `OsrsSkin` | the tokens above + fonts; the only style source for skinned surfaces |
| `StoneBorder` | Swing `Border` painting the engraved edge + corner stamps; insets 4/6 (game pad) |
| `StonePanel` | `BOX_FILL` panel wearing a `StoneBorder` |
| `OsrsLabel` | shadowed RuneScape-font text, multi-line on `\n`; factories: `title` `label` `value` |
| `StatBox` | orange label line(s) + centered icon+value line in a `StonePanel` |

Implementation notes proven by the render: text uses the game's tight **12px line
pitch**, not Swing's FontMetrics height (16) — the RuneScape font's real ink is 12px
above / 1px below baseline, so FontMetrics-driven layout runs 4px/line taller than the
game and the boxes stop matching. Antialiasing must be explicitly OFF or the pixel font
smears. The quests/achievements spiral icons were cropped 1:1 from the wiki screenshot
(opaque on `BOX_FILL`, which blends invisibly on our identical boxes).

Phase 2 candidates once the look is signed off: stone tab strip (`Tabs.SLANTED_TAB`
geometry), the mottled outer frame (crop the edge strips from the wiki 1x screenshot and
tile them), hover/selected fills (`V2StoneButton` has real variants), buttons,
progress bars, scrollbar restyle.

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
Its tab is a static recreation of the Character Summary built purely from the atoms
(sample data, labelled as such — honesty rule). Render: `build/reports/designlab-tab.png`,
compared side-by-side against the wiki 1x original.
