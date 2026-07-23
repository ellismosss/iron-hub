# Iron Hub knowledge base

The complete-game-knowledge store behind the plugin (Luke's 2026-07-21 goal):
every wearable, PoH build, boost, diary task, consumable, collection-log slot,
combat achievement, boat upgrade, QoL unlock, training method and money-making
method — harvested from the OSRS wiki, tracked for coverage, with every hole
flagged for review instead of guessed at.

## How to browse it

- **Open [html/index.html](html/index.html) in a browser** — the progress
  dashboard (per-category coverage, flagged-row counts, sources, timestamps)
  with a searchable page per table. The filter box ANDs words; `flag:xyz`
  filters by flag.
- **[GAPS.md](GAPS.md)** — everything the harvest could not establish, grouped
  by category. Add what you know (notes under a row, or just tell me in chat)
  and I'll fold it into the DB.
- **knowledge.db** — the canonical SQLite store, if you want to query directly:
  `sqlite3 knowledge/knowledge.db "select ... "`. Every row carries `src`
  (provenance) and `flags` (unresolved markers).

## How it's built

`python3 tools/knowledge/rebuild.py` reruns the whole pipeline (drop DB →
import packs → harvests → joins → reports). Wiki fetches cache under
`tools/.cache-knowledge/` (gitignored), so rebuilds are fast; delete cache
entries to force a refresh. Individual steps rerun standalone.

| Step | Source | What it fills |
|---|---|---|
| `import_packs.py` | the plugin's own data packs | seeds all tables with provenance `pack:*` |
| `harvest_equipment.py` | 12 wiki equipment-slot categories (bulk API) | every wearable: full Infobox Bonuses stats (all versions), ids, examine, lead-prose effects, recipes |
| `harvest_poh.py` | Category:Furniture | every furniture piece: level, room, xp, Recipe materials, flatpack |
| `harvest_boosts.py` | Temporary skill boost + 24 subpages | every boost with amount, visibility and circumstances (+ invisible boosts) |
| `harvest_ca.py` | `combat_achievement` bucket + tier pages | all CA tasks + the wiki's per-tier Items/Skills/Quests requirements |
| `harvest_consumables.py` | Food/Potions/Drinks categories | effects, creation recipes, obtainment |
| `harvest_drops.py` | `dropsline` bucket | every drop line in the game (39k rows): source, rate, qty, type |
| `harvest_shops.py` | `storeline` + `collection_log_source` buckets | every shop's stock; the wiki's own clog slot→sources/rates map |
| `join_obtainment.py` | (joins) | attaches drops + shops + recipes to equipment/consumables/clog |
| `report.py` | (derives) | html/ dashboard + GAPS.md |

## Honesty rules

Same as the plugin's packs: nothing is invented. A fact the wiki doesn't
state lands in `gaps` with a reason; partial rows carry `flags`
(`reqs-unverified`, `obtain-unknown`, `effects-missing`, ...). Known
structural limits, stated up front:

- **Equip requirements are not machine-readable on the wiki** — the 189
  gear-progression items carry their audited req chains; the rest are flagged
  `reqs-unverified` until curated (or a prose-parse pass lands).
- **Quest/diary/minigame REWARD items** (untradeables like barrows gloves'
  source) aren't in dropsline/storeline — `obtain-unknown` until the quest
  bucket join lands.
- Player-specific state (what YOU have obtained/built) stays live in the
  plugin via AccountState — the KB holds game knowledge, not save data.
