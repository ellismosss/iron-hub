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

**v2.1 (2026-07-24, Luke's source-quality + scaling report):**
- **Sensible source ORDER** (`HOW_ORDER`): shop → make → reward → spell →
  drop → open → other. Buy/make lead a commodity; a drop-only unique still
  leads with its drop (it is the only option). Mithril arrow led with a
  4/101 Catablepon drop over the shop and its Fletching recipe until this.
- **A `make` row must consume MATERIALS** — a skill-only recipe with none
  is the wiki listing an ALTAR-OFFERING as a craft ("Vorkath's head · Make:
  Prayer 1"). Dropping those (plus joke/holiday containers) removed 31
  items whose ONLY source was spurious; `sourceLine` returns null (honestly
  unknown) > nonsense.
- **`HOLIDAY_CONTAINERS`** (Christmas cracker et al.) are never a source —
  "open a Christmas cracker for silk" is absurd advice; discontinued rares
  (partyhats) whose only "source" was the cracker become sourceless.
- **Currency metadata** on shop prices (`Source.currency` → `currencyReq()`):
  item currencies (nuggets, marks of grace, coins) count from bank+inv; the
  three readable point varbits (Tithe 4893 / NMZ 3949 / slayer 4068) via the
  `varbit:<id>:<value>:<label>` graph leaf; an unreadable currency → an
  honest "Earn N X" step, never a pretend progress figure.
- **Batch recipes SCALE** (`Source.outputQty` = the recipe's per-batch
  output, recorded only when >1; `Source.batchesFor(n)` = ceil(n/outputQty)).
  A material list is per-BATCH; making N needs ceil(N/outputQty) batches, so
  75 Mithril arrows from a 15-per-batch recipe (15 headless + 15 tips) needs
  5 batches = 75 + 75. `Action.obtainQty` carries the required count (from
  the requirement); both the GoalExpander gather sub-steps and the sidebar
  material rows scale by it.

The **method-choice** flow (v2) has a hard rule when a gear item has a
curated `any:` gate ("Crafting 80 OR Hunter 83"): the choose-method menu
offers ONLY those PATHS (a `path|<req>` pref), because the KB sources there
are display-only (the expander's `currencyOnly` suppresses them). Offering
both let the player pick a KB "Make" that never steered the `any:`, so the
other skill stayed in the plan.

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
the chosen method becomes THE line on every surface. In the Goals sub-line,
a KB SHOP first-source beats the clog attempt-count label (point purchases
modeled as attempts read as fake drop rates: "1/250 · Tithe farm" seed box).

**v2 round 2 (2026-07-23, the choice now REACHES the planner):** the pref
is a `PlanConstraints.itemSourcePrefs` entry, in the plan fingerprint, so
`GoalExpander` honours it — picking "Crafting 80" for an Amulet of glory
drops Hunter 83 from the plan entirely and the overlay re-routes. Two pref
forms: an `ItemSourcesPack.key` (choose a KB source) or
`GoalExpander.PATH_PREF + "<requirement>"` (choose one branch of the
item's own `any:` gate). And OBTAIN nodes now expand the KB's obtainment
into REAL sub-steps — `Source.currencyReq()` turns a priced shop into a
tracked currency requirement (item currencies count from the bank; the
three readable point varbits via the `varbit:` leaf; unreadable currencies
become an honest "Earn N X" manual step), `Source.materials` become gather
steps, and a quest-reward source becomes the quest. This is what closed
the "Buy: Twiggy O'Korn" / "Buy: Mahogany Homes (500 points)" dead ends.
MODELLING RULE: when the curated gear chain already offers a choice
(`any:`), that `any:` is authoritative — the KB then contributes only the
orthogonal purchase cost, or both routes get demanded at once.
`Source.materials`/`Source.currency` are pack v2 data (the UI renders one
sprite row per material, not one clumped sentence).

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
