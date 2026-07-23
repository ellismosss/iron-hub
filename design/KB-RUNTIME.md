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

- **Best source per `how`** (drop / shop / make / reward), max 4 — compact and
  maximally informative; rates/prices kept as the wiki's own strings.
- Discontinued (`removal_date`) and restricted-mode rows excluded; `#Version`
  suffixes stripped for display.
- `reqs` are requirement-graph strings (pack test asserts every one parses
  non-manual); `reqsOrigin` = `audited` | `extracted` | absent — surfaces may
  phrase extracted reqs as "the wiki states", never silently gate on them.

### 2. `ItemSourcesPack` — one query + display seam

`com.ironhub.data.ItemSourcesPack`: lazy id- and name-indexed;
`entry(itemId)`, `sourceLine(itemId)` (the compact one-liner:
"Drop: Abyssal demon 1/512 · Reward: Unsired"), `reqs(itemId)`. All consumers go
through it — no surface hand-rolls obtainment copy.

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

Generator lessons recorded in gen_item_sources.py: `removal_date` filters at
the ID level (LMS/holiday duplicates share real items' names — a name-level
filter deleted Eternal boots and Bandos godsword), reward labels normalize at
generation, same-`from` sources dedupe across hows.
