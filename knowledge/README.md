# Iron Hub knowledge base

The complete-game-knowledge store behind the plugin (Luke's 2026-07-21 goal):
every wearable, PoH build, boost, diary task, consumable, collection-log slot,
combat achievement, boat upgrade, QoL unlock, training method, money-making
method, resource/material and processing recipe — harvested from the OSRS
wiki, tracked for coverage, with every hole flagged for review instead of
guessed at.

## How to browse it

- **Open [html/index.html](html/index.html) in a browser** — the progress
  dashboard (per-category coverage, flagged-row counts, sources, timestamps),
  a searchable page per derived table, and a **Raw wiki buckets** section
  (the 17 unprocessed bucket stores the joins run from). The filter box ANDs
  words; `flag:xyz` filters by flag.
- **[GAPS.md](GAPS.md)** — everything the harvest could not establish, grouped
  by category. Add what you know (notes under a row, or just tell me in chat)
  and I'll fold it in.
- **[MODULE-AUDIT.md](MODULE-AUDIT.md)** — the pack-vs-KB audit and the status
  of each enacted proposal.
- **knowledge.db** — the canonical SQLite store:
  `sqlite3 knowledge/knowledge.db "select ..."`. Every row carries `src`
  (provenance) and `flags` (unresolved markers).

## How it's built

`python3 tools/knowledge/rebuild.py` reruns the whole pipeline (drop DB →
import packs → harvests → joins → verifications → reports). Wiki fetches
cache under `tools/.cache-knowledge/`; delete cache entries to force a
refresh. Individual steps rerun standalone and retire their own stale
flags/gaps before re-judging (a re-detected gap reopens; `wont-fix` sticks).

| Step | Source | What it fills |
|---|---|---|
| `import_packs.py` | the plugin's own data packs | seeds tables with provenance `pack:*` |
| `harvest_equipment.py` | 12 wiki equipment-slot categories (bulk API) | every wearable: full versioned stats, ids, examine, effects prose, recipes |
| `extract_equip_reqs.py` | equipment page PROSE (requirement sentences) | equip reqs for 1,135 items — validated 97.9% against the 189 audited chains before writing |
| `harvest_poh.py` | Category:Furniture | level, room, xp, {{Recipe}} materials, flatpack |
| `harvest_boosts.py` | Temporary skill boost + 24 subpages | every boost: amount, visibility, circumstances |
| `harvest_ca.py` | `combat_achievement` bucket + tier pages | all CA tasks + per-tier Items/Skills/Quests reqs |
| `harvest_consumables.py` | Food/Potions/Drinks categories | effects, recipes, obtainment |
| `harvest_drops.py` | `dropsline` bucket | every drop line in the game (39k): source, rate, qty |
| `harvest_shops.py` | `storeline` + `collection_log_source` buckets | every shop's stock; the wiki's own clog slot→sources map |
| `harvest_items.py` | `infobox_item` bucket | 16.5k items: ids, examine, quest flags — the universal join |
| `harvest_qol.py` | Storage items + curated list | the QoL catalog with effects + sources |
| `harvest_training.py` | wiki training guides | per-method sections: rates where stated, flagged prose-derived |
| `harvest_rewards.py` | quest pages + diary Rewards sections | reward-source obtainment lines |
| `harvest_recipes.py` | `recipe` bucket | 7.4k productions + the derived **materials** table (obtained-from / used-in with quantities; dose, degraded and quest-internal variants classified on-row) |
| `harvest_buckets.py` | 17 raw buckets, schema-driven | `bucket_*` tables: monsters, scenery, loclines, spells, shops, exchange, varbits, sea charts, port tasks, recommended equipment, … |
| `join_obtainment.py` / `join_effects.py` | (joins) | attaches drops/shops/recipes/rewards + clog what-it-does |
| `apply_curated.py` | curated-sources.json (Luke's verified answer key) | clog holdouts, named materials, pattern classes, spell joins, wont-fixes |
| `verify_packs.py` / `verify_port_tasks.py` | (verification) | pack-vs-wiki drift, flagged never overwritten |
| `report.py` | (derives) | html/ dashboard + GAPS.md |

## Packs generated FROM these sources (Luke-approved)

- **recipes.json** — `tools/gen_recipes.py` reads the **recipe bucket**
  (2026-07-22; was Template:Recipe wikitext crawling — the migration fixed
  3 real errors and dropped 5 parse artifacts). Flatpacks supplement from
  the Furniture/Flatpacks category pages; untradeable unf-potions
  chain-collapse to raw materials.

- **boosts.json** — `tools/gen_boosts.py`: 194 boost sources (was 10) from the
  wiki subpages; 10 hand-audited gates kept verbatim, harvested rows gate on
  owning the source item; non-item boosts (areas, percent-of-level specials,
  minigame-internal potions) are skipped with a log line and stay KB-side.
- **qol.json** — `tools/gen_qol.py`: 98 unlocks (was 9); the original 9 kept
  verbatim (ids are load-bearing for saved goals); non-item unlocks (fairy
  rings, spirit trees) stay KB-side.
- **port-tasks.json** — `tools/gen_port_tasks.py` now cross-corrects the
  hand-transcribed XP table against the wiki's courier/bounty task buckets
  (49 corrections applied; ambiguous joins keep the transcription and stay
  in GAPS.md).
- **recommended-equipment.json** — `tools/gen_recommended_equipment.py`: the
  wiki's own gear tables for 452 activities, offline (the old per-page
  wiki-strategy fetch path's data, bundled). Consumed by the Gear & Combat
  "Wiki gear" fold (2026-07-23, design/KB-RUNTIME.md).
- **item-sources.json** — `tools/gen_item_sources.py` reads **knowledge.db
  directly** (run `tools/knowledge/rebuild.py` first): the universal per-item
  obtainment + equip-req projection — 6,870 items, best source per how
  (drop/shop/make/reward), reqs with audited/extracted origin. Every
  "where does this come from" surface in the plugin renders through it
  (design/KB-RUNTIME.md).

## Domain notes worth remembering

- **Potion doses**: mixing yields a **(3)** — an Amulet of chemistry gives a
  5% chance of a (4), a charged Alchemist's amulet 15%; other doses come from
  use or decanting. The materials rows carry this note; partial doses are
  never treated as having an obtainment "source" of their own.
- **Equip requirements have NO structured wiki field** (all 4,242 pages
  surveyed: zero `|req=` params, zero templates, no bucket field) — but the
  prose is patterned, so `extract_equip_reqs.py` machine-extracts them from
  requirement sentences, gated on ≥90% validation against the 189 audited
  chains (currently 97.9%). Flags: `reqs-extracted` (prose-sourced),
  `reqs-none-stated` (cosmetics — genuinely none), `req-conflict` gaps where
  extraction disagrees with the audited pack (several look like audit
  coarseness — review before trusting either).
- `mine` and `ge_index_header` buckets exist wiki-side but are EMPTY —
  rechecked on every rebuild.
- Player-specific state (what YOU own/built) stays live in the plugin via
  AccountState — the KB holds game knowledge, not save data.
