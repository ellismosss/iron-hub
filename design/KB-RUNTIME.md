# KB → Runtime: making the plugin use the whole knowledge base

Luke's directive (2026-07-23): *"reconsider the architecture used by the plugin and
develop to correctly retrieve, send, interweave, connect and display all information,
goals and routes across every area. The plugin needs to make full-use of the entire
knowledge base."*

## The architecture, reconsidered

Three layers exist today; the verdict is that the layering is RIGHT and one layer has
a hole in it:

1. **Ground truth** — `knowledge/knowledge.db` (build-time, wiki Bucket API + slot
   categories + prose extraction; regenerable, gitignored). Complete: 16.7k items,
   39k drop lines, 6.3k shop rows, 7.4k recipes, equip reqs for every wearable that
   states them.
2. **Projections** — the `data/*.json` packs, each generated for one consumer
   (gear chart, clog ranker, recipes decomposition, boosts...). Schema-validated,
   shipped in the jar.
3. **Runtime** — `DataPack` loaders + `AccountState` + the requirement graph + the
   goal engine. Player state stays live; game knowledge stays static.

What we deliberately do NOT change:

- **No runtime SQLite.** Shipping the DB would add a native dependency the Hub
  won't take, and 90% of its rows answer questions no surface asks. Projections
  stay JSON packs.
- **No runtime HTTP.** The KB was built to make the wiki unnecessary at runtime.
- **No invented rates.** The KB knows drop *rates* for arbitrary monsters but not
  *kill speeds*; only clog activities carry honest completions/hr. Arbitrary-drop
  OBTAIN steps stay NaN-houred — they gain a *where-from* line, never a fake ETA.
- **methods.json untouched.** Training-rate promotion from the KB remains Luke's
  per-method call (standing gate).

## The hole: no universal "item X — where from, what gates it" projection

Every existing pack projects a *feature's* slice (gear chart items, clog slots,
supply recipes). The question every surface keeps half-answering — *how does an
ironman get this item, and what gates using it* — has no projection, so:

- OBTAIN goal steps with no clog rate render bare ("Obtain Dragon warhammer" —
  silence about where it comes from).
- Missing materials (goals resources, slayer bring, farm runs, money-making
  inputs, dailies SHORT) say *what* is missing, never *where from*.
- Equip requirements exist in the KB for 1,300+ wearables (189 audited + 1,135
  prose-extracted, 97.9% validated) but the plugin only knows the gear chart's 189.
- `recommended-equipment.json` (452 activities of wiki gear tables) ships with no
  consumer at all.

## The fix — three pieces

### 1. `data/item-sources.json` — the universal item projection

`tools/gen_item_sources.py` reads `knowledge.db` (run `tools/knowledge/rebuild.py`
first if absent) and emits one entry per obtainable item:

```json
{"name": "Abyssal whip", "ids": [4151],
 "sources": [{"how": "drop",   "from": "Abyssal demon", "rate": "1/512"},
             {"how": "reward", "from": "Unsired",       "rate": "12/128"}],
 "reqs": ["skillb:Slayer:85", "skill:Attack:70"], "reqsOrigin": "audited"}
```

- **Best source per `how`** (drop / shop / make / reward / open), max 5 —
  compact and maximally informative; rates/prices kept as the wiki's own
  strings.
- Discontinued (`removal_date`) and restricted-mode rows excluded; `#Version`
  suffixes stripped for display.
- `reqs` are requirement-graph strings (pack test asserts every one parses
  non-manual); `reqsOrigin` = `audited` | `extracted` | absent — surfaces may
  phrase extracted reqs as "the wiki states", never silently gate on them.

**v2 (2026-07-23, Luke's data report — every symptom was a class):**
- `make` rows carry `detail` = the recipe's actual materials (imbue point
  costs included: "Salve amulet + 800,000 x Nightmare Zone points", up to 3
  variants) — the empty "(see recipe)" row was imbue recipes (no skill gate)
  WINNING the lowest-gate ranking.
- `open` how: a "drop" whose source is an ITEM is a container you open
  (impling jars, caskets) — never a kill, never a "reward".
- Reclaim shops (Lost Property) excluded — they sell BACK what you earned
  (27 items incl. the imbued god capes had them as their only "source").
- Point-shop prices harvested from each shop page's `{{StoreLine|sell=}}`/
  plink tables (the storeline bucket carries NONE — verified live), pinned
  against hand-verified costs so parser drift fails the build.
- Diary reward rows only for the 12 diaries' actual reward-item families
  (Rewards sections link every item their prose mentions); quest rewards
  outrank diary rows; miniquests harvested (Category:Miniquests — Mage
  Arena II); famous containers carry activity context ("Pyramid Plunder —
  Grand Gold Chest").
- Wiki-vs-itself conflicts (gem bag: bucket 80, prose 100) resolve via
  curated `shop_prices` in curated-sources.json.

### 2. `ItemSourcesPack` — one query + display seam

`com.ironhub.data.ItemSourcesPack`: lazy id- and name-indexed;
`entry(itemId)`, `sourceLine(itemId)` (the compact one-liner:
"Drop: Abyssal demon 1/512 · Open: Unsired 12/128"), `reqs(itemId)`. All
consumers go through it — no surface hand-rolls obtainment copy.

**v2: `sourceLine(itemId, state, prefKey)` is the seam everywhere now** —
state drops MET make-gates from the copy (telling a level-68 mage "needs
Magic 68" is noise), and `prefKey` (from `ItemSourcesPack.key(source)`) is
the player's CHOSEN obtainment method: right-click an OBTAIN task in Goals →
"Get it via ..." persists `AccountState.setItemSourcePref(itemId, key)`, and
the chosen method becomes THE line on every surface. Choice drives display
only — the planner's time-costing still uses clog/gear rates (a future arc
may route the cost through the chosen method). In the Goals sub-line, a KB
SHOP first-source beats the clog attempt-count label (point purchases
modeled as attempts read as fake drop rates: "1/250 · Tithe farm" seed box).

### 3. Consumers — every "what/where" surface answers "where from"

| Surface | Wiring |
|---|---|
| Goals hub task sub-line | OBTAIN steps with no clog rate fall back to `sourceLine` |
| Goals resource rows / supply materials | short rows tooltip the where-from |
| Gear progression tiles | hover adds the source line under the missing reqs |
| Slayer bring rows | unowned items tooltip where-from |
| Money-making inputs | input rows tooltip where-from |
| Bank stats tooltip | equipment adds "Requires: 75 Attack" (unmet in red) |
| Gear & Combat monster card | NEW "Wiki gear" fold from `recommended-equipment.json`: per-style wiki-recommended items, ownership-tinted, unowned rows route into the Goal planner |

The last row closes the recommended-equipment gap: wiki gear tables become
*routable goals* — pick a monster, see what the wiki recommends, click + on what
you lack and the engine costs the grind via the gear/clog projections.

## Slices (all LANDED 2026-07-23)

1. This doc + `gen_item_sources.py` + schema + pack + `ItemSourcesPack` + tests.
2. Where-from wiring: goals sub-line/resources, gear tiles, slayer bring,
   money-making inputs, supplies-runway LOW rows, bank reqs tooltip.
3. The "Wiki gear" fold on the Gear & Combat monster card
   (`RecommendedEquipmentPack` + `LoadoutLabModule.wikiGearSection`):
   collapsed by default, style chips, per-slot top picks ownership-tinted,
   "+" seeds one-shot supply goals, wiki-page provenance line. Render:
   build/reports/loadout-wiki-gear.png.
4. Docs (CLAUDE.md / knowledge README / MODULE-AUDIT) in the same arc.
5. **v2 round (2026-07-23 evening, Luke's 17-item data report)** — the
   classification fixes above, the method-choice seam, boost-aware doable
   checks reaching PoH/Sailing/Diaries, where-from on every remaining
   missing-item surface, poh.json materials, and the PoH/Miscellania
   detection fixes (commits 4b4abcd..e17f12f).

Generator lessons recorded in gen_item_sources.py: `removal_date` filters at
the ID level (LMS/holiday duplicates share real items' names — a name-level
filter deleted Eternal boots and Bandos godsword), reward labels normalize at
generation, same-`from` sources dedupe across hows. **And from v2: always
DIFF a regenerated pack's entry names against the committed baseline before
accepting it** — a loop variable shadowing the `name` parameter silently
dropped 499 single-material-recipe items, invisible to every count floor,
caught only by the name diff.
