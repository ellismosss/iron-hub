# Module data-access audit vs the knowledge base — 2026-07-21

Each plugin module's data source compared against what the KB now knows.
**Nothing here lands without your sign-off** (the standing gate on pack
regenerations). Ordered by how badly the module under-covers.

## Proposals needing your word

1. **boosts.json is 3% of the game's boosts** — 10 sources vs **298** wiki
   boost rows (KB `boosts` table). Everything that consults
   `Boosts.available` (diary doable-checks, CA gating, `skillb:` leaves,
   PoH buildable-now) under-reports what's boostable. Proposal: regenerate
   boosts.json from the Temporary-skill-boost subpages (same source the KB
   used), keeping the pack's per-source obtainment gating. **Biggest win.**

2. **qol.json is 9 unlocks; the KB catalog has 90** (Storage items + curated
   utility list — scope itself flagged for your review in GAPS.md). The QoL
   checklist module shows a tenth of what it could. Proposal: widen the pack
   after you settle the catalog scope.

3. **boat-upgrades.json: 4 real material mismatches** vs the current wiki
   pages (Crystal extractor, Gale catcher, Keg, Wind catcher — details in
   GAPS.md/boat). Sailing is moving; propose a regen of gen_boat_upgrades
   with these four checked by hand.

4. **methods.json holds 97 methods; the wiki guides yield 225+** (KB
   `training_methods`, wiki rows flagged prose-derived). Rates stay yours to
   gate — but the LADDER breadth (methods the planner can even consider) is
   under-covered, e.g. whole skills lean on 3-4 rungs. Proposal: review the
   wiki-derived list per skill in the KB browser; promote the ones you trust
   and I'll fold them into methods.json with your rates.

## Verified clean (no action)

- **diaries.json** — 492 tasks, per-tier counts match the wiki, 0 drift.
- **money-making.json** — all 526 methods still on the wiki; the 107 wiki
  methods not in the pack are its account-doable filter working as designed.
- **clog.json** — the 1,706-slot list matches the wiki's own
  collection_log_source bucket exactly; slot rates now cross-referenced.
- **slayer-tasks.json** — all 483 locations carry coordinates (fixed
  earlier today); bring lists, masters, unlocks wiki-sourced at generation.

## Structural notes

- **gear-progression.json** stays a curated LADDER by design (189 audited
  entries); the KB's 4,294-item equipment table is the superset behind it.
  The planner's item gates keep using the audited reqs; the KB flags
  `reqs-unverified` on the rest until curated.
- **Loadout Lab's corpus** is upstream data (imported plugin); its stats now
  cross-checkable against the KB's Infobox Bonuses harvest if drift is ever
  suspected.
- Player-state (obtained/built/unlocked) stays in AccountState — the KB is
  game knowledge only.

## Raw-bucket harvest follow-ups (2026-07-21, slice 6)

- **Port tasks**: `bucket_couriertaskline`/`bucket_bountytaskline` carry every
  task with xp/item/qty/ports — keyed by the SLOT-VARBIT task id (1..439),
  not the DBRow ids port-tasks.json keys rewards by (the two id spaces the
  module already distinguishes). A mapping pass (join on item+ports+label)
  would let the pack's hand-transcribed XP table be generated instead —
  needs your word as a pack regen.
- **`bucket_quest.ironman_concerns`** — per-quest ironman notes; a natural
  QuestsTab tooltip/KB surface when wanted.
- **`bucket_recommended_equipment`** (452 activity gear tables) — offline
  replacement candidate for the wiki-strategy fetch path.
- **`mine` and `ge_index_header` are EMPTY wiki-side** (defined, unpopulated)
  — gaps recorded; recheck on future rebuilds.
