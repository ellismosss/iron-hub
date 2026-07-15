# OSRS domain notes

Game knowledge that shapes this plugin's design, collected from the OSRS wiki
(https://oldschool.runescape.wiki/) and in-client testing. **Read this before
writing any feature that infers account state from game data.** When in doubt,
verify against the wiki — its MediaWiki API is public (`/api.php`, send a
descriptive User-Agent); `tools/wiki_audit.py` shows the pattern.

## Item identity is layered — never assume one id = one item

Every visual/mechanical variant is its own item id. RuneLite's
`ItemVariationMapping` groups related ids, but the groups mix THREE
relationships that the plugin must treat differently:

1. **Interchangeable variants** — recolours (24 graceful hood ids), charge
   states (blowpipe empty/charged, trident), broken/repaired (fire cape
   variants), open/closed containers (coal bag). Owning any = owning the
   item. → `item:` requirements, `canonicalStock`.
2. **Tiers and imbues in one group** — Ghommal's hilts 1–6, diary rewards
   (Ardougne cloak 1–4, Explorer's ring, Desert amulet, Rada's blessing),
   salve amulet /(e)/(i)/(ei), black mask ↔ (i), berserker ring ↔ (i).
   Owning tier 2 does NOT mean owning tier 4. → `itemx:` requirements,
   `exact: true` in the pack.
3. **Upgrade/consumption chains across groups** — the successor destroys or
   supersedes the predecessor, so owning it PROVES the predecessor was
   obtained even though it no longer exists: Ava's attractor → accumulator →
   assembler, Kharedst's memoirs → Book of the dead, black mask → slayer
   helmet, whip → tentacle, Arclight → Emberlight, dragon defender → Avernic
   defender, torture → rancour, the three Cerberus boots → Avernic treads,
   fire cape → infernal cape (sacrificed for Inferno entry). → `implies`
   lists in the pack.

**ItemID constant trap:** RuneLite names constants from cache order — the
unsuffixed name is the FIRST id with that name, which for content that had a
pre-release beta (DT2 rings, Soulreaper axe) is the dead beta item. The real
item is the `NAME_<id>` duplicate at a higher id.

## Requirement semantics — three different "levels" per item

The wiki (and players) distinguish gates the plugin must not conflate:

- **Equip/use gates** — hard, always apply (75 Ranged for a blowpipe,
  90 HP for rancour/confliction gauntlets, 70 Def + 50 Agility for crystal
  armour, Rigour/Augury also need 70 Defence to *activate*).
- **Obtainment gates** — how the account gets it (quest completion, kill
  counts, 66 Magic to enter the Magic Guild shop, diary tiers). For ironmen
  these are mandatory; for mains often bypassable via GE.
- **Creation gates** — skills to assemble it. Include only when
  iron-mandatory with no alternative (93 Magic zenyte enchants, 89–98
  Crafting for zenyte jewellery, 78 Fletching for the blowpipe, 75 Crafting
  for the divine pouch). Skip when boostable-trivial or when the guide's
  obtainment path avoids it.

**Obtainment is usually a DISJUNCTION of paths, each with its own nested
gates.** An amulet of glory is craft-it-yourself (80 Crafting for the
dragonstone amulet, then 68 Magic to enchant) OR dragon implings (83 Hunter)
— a player with neither meets no path, so "requirements met" must mean "at
least one full path met". Encode with the `any:` requirement form
(`any:skill:Crafting:80|skill:Hunter:83`; `&` joins leaves within a path).
Rules of thumb when writing entries:
- Chase each path to its OWN gates (the impling path's gate is Hunter, not
  Crafting; the crossbow's antler needs 72 Hunter before 74 Fletching).
- If one practical path is UNGATED (magic shortbow from hard clues, mixed
  hide cape made-for-you by Pellem for 20k), the gated alternative must NOT
  appear as a requirement — it would block a perfectly reachable item.
  Ignore fringe paths (wilderness-only drops like bloodbark from zombie
  pirates) rather than making everything vacuous.
- Enchant levels are per-gem, not per-item: sapphire 7, emerald 27, ruby 49,
  diamond 57, dragonstone 68, onyx 87, zenyte 93.
- **Combine/upgrade/mount entries must require their input items** — a
  mounted glory needs an amulet of glory in hand, Avernic treads need all
  three Cerberus boots, the tentacle needs a whip. Meeting the entry's own
  skill gates means nothing without the input, and the input carries its
  whole upstream chain. Encode as `item:<id>:1:<Display name>` (the label
  keeps tooltips readable); the input's own gates live on ITS chart entry,
  never copied. This is the requirement-side mirror of `implies` (which
  handles the obtained-direction of the same relationship).

Facts that have already bitten us (all wiki-verified now): dual macuahuitl is
70 Att/75 Str (not 75/75); the demonbane trio is level **77** (not 75);
Moons armour is 75 style + **50 Defence**; the eclipse atlatl needs 50
Attack AND 50 Strength; the DWH needs 60 **Strength** (not Attack); Masori
(f) is 80 Def (base Masori is 30); Elidinis' ward (f) is 80/80/80;
eye of ayak is 83 Magic; dragon hunter wand 65 Magic; Virtus is 78 Magic/75
Def; full Void needs 42 in six combat stats + 22 Prayer.

## Temporary skill boosts

Boosts satisfy **action gates only** — creation, activities, slayer kills —
never equip gates (wiki, Magic potion: "will not be able to equip... but
will be able to cast"). The requirement graph encodes this as `skillb:`
(boostable) vs `skill:` (hard/equip); only `skillb:` leaves consult boosts.

Mechanics that matter:
- **Visible boosts don't stack with each other** — the best one wins. The
  crystal saw is *invisible* and stacks on top: saw (+3) + POH tea (+3) = +6
  (86→92, Theoatrix's max-house line), saw + spicy stew (+5) = +8.
- **Boost sources have their own obtainment gates**, tracked in
  `data/boosts.json`: spicy stews need RFD Evil Dave; pies need the Cooking
  level (or owning one); the saw needs The Eyes of Glouphrie; POH tea needs
  Teak shelves 2. An unavailable boost gives no headroom.
- Slayer: a wild pie (+5) lets you *kill* higher monsters but not *receive*
  tasks above your level.
- The chart paints an **amber** corner triangle for boost-reachable items
  (green = met outright); tooltips name the usable sources per gap.

## Detection limits (what the client can and cannot tell us)

- **Kill counts** only accumulate from loot events after install — historic
  accomplishments need ownership proofs (`achieved` lists on goals).
- **Bank contents** are only known from the last bank visit; equipment and
  inventory sync live. POH furniture, diary completion states (varbits exist
  for diaries; furniture no), and spent currencies (marks of grace) are not
  item-detectable → manual marks / varbits where documented.
- Farming varbits only sync in-region; quest states need script runs on the
  client thread.

## Combat Achievements cache layout

- The full task catalog lives in the game cache: per-tier enums
  **3981–3986** (Easy→Grandmaster) map task index → struct id; each struct
  carries params **1306** (task id), **1308** (name), **1309** (description),
  **1311** (type 1–6: Stamina/Perfection/Kill Count/Mechanical/Restriction/
  Speed), **1312** (boss id, resolved via string enum **3971**). These have
  no gameval constants — values adopted from the production Combat
  Achievements Tracker hub plugin; `CaCatalog.load` fails soft if they move.
- Completion is a bitfield: task id N → bit N%32 of documented varp
  `CA_TASK_COMPLETED_{N/32}` (20 varps = 640 bits; 637 tasks as of 2026-07,
  ids 0–636 — the exact fit corroborates the id mapping, as does the wiki's
  `data-ca-task-id` matching param 1306).
- Points per task are fixed per tier (Easy 1 … Grandmaster 6); tier
  thresholds and CA_POINTS come from documented varbits — never hardcode
  thresholds, Jagex rebalances them.
- Community completion rates come from the wiki's All-tasks table, bundled
  as `data/ca-completion.json` via `tools/gen_ca_completion.py` (no runtime
  HTTP; regenerate occasionally — rates drift slowly).
- Task completion fires a GAMEMESSAGE/SPAM chat line "Congratulations,
  you've completed … combat task"; varps update the same tick, so a reload
  on the next tick reads fresh state.

## Achievement diary task tracking

- Per-task completion is **varplayer bitfields**, two 32-bit varps per
  region (e.g. `ARDOUNGE_ACHIEVEMENT_DIARY`/`2` = 1196/1197, Kourend =
  2085/2086; all named in gameval `VarPlayerID`). Tiers occupy contiguous
  ascending bit ranges with holes (bits of removed/multistage subtasks are
  skipped). The four per-region tier-complete varbits
  (`Varbits.DIARY_*`) are separate and only flip on claim.
- **Never derive displayed counts from the bitfields alone.** Some tasks
  have **ironman-only alternate completion flags the client can't see**
  (confirmed: Desert Medium's "redirected house tablet" task — ironmen
  complete it via the Pollnivneach house portal and bit 22 of varp 1198
  never sets, yet the tier completes). The game's own per-tier
  **completed-count varbits** (`<REGION>_<TIER>_COUNT` in gameval, e.g.
  `DESERT_MED_COUNT` 6296; the full 48-varbit table is what clientscript
  2200 `diary_completion_info` returns, per the RuneStar cs2 dump — its
  per-tier totals also corroborate all 48 pack task counts) include those
  alternates. Counts come from these varbits (bit-count as fallback), and
  a task also renders complete when its tier is claimed or its count
  varbit equals the tier size.
- **Karamja is the exception**: its tasks are individual `ATJUN_*` varbits
  (gameval-named), some counting (`ATJUN_EASY_BANANA` completes at ≥ 5,
  `ATJUN_EASY_SEAWEED` at ≥ 5); Karamja Elite is varp `ATJUN_TASKS_4`
  bits 1–5.
- **Which bit tracks which task** was adopted from Quest Helper's diary
  helpers at the hub-pinned commit (field-name semantics corroborated by
  Jagex's own gameval names for Karamja). Bit order matches the in-game
  journal order (= wiki task numbering) in 44 of 48 tiers; **four tiers
  were reordered or extended after launch** and carry hand-verified
  overrides in `tools/gen_diaries.py`: Ardougne Elite (#3/#4 swapped),
  Kourend Medium (fairy-ring task shown first, bit appended last),
  Kourend Hard (#5/#6 swapped), Karamja Medium (#7/#18/#19 rotated).
  Never assume journal order = bit order when adding diary data.
- Task text/requirements/rewards come from the 12 wiki diary pages (task
  tables carry `data-diary-name`/`data-diary-tier`). Requirement bullets
  parse into graph leaves only when unambiguous: SCP skill templates →
  `skillb:` (or `skill:` when the wiki marks `{{Boostable|n}}` — diary
  skill reqs are boostable unless marked), "Completion of [[X]]" links →
  `quest:` validated against the RuneLite Quest enum, "X or Y" skill
  alternates → `any:` paths. Lines with started/partial-quest wording,
  item lists, or "recommended" stay display-only and never gate the
  amber doable highlight — conservative gates beat false "doable now".

## Wiki page-name gotchas

Override `wiki` in the pack when the display name isn't the page: "God capes"
(plural), "Spirit tree (Construction)", "Fairy ring (Construction)",
"Jewellery box" (lower-case b), POH altars live under "Occult altar" /
"Altar space". Set items (Barrows, crystal, Masori, oathplate, Virtus,
Ancestral) have per-set overview pages that read better than per-piece ones.

## Collection log detection (module: collectionlog, pack: clog.json)

Three signals, all read-only, all ported from Log Adviser (BSD-2):

- **`VarPlayerID.COLLECTION_COUNT`** — the game's own live count of unique
  slots obtained. It is the truth for "how many", but says nothing about
  *which* slots. We watch it via `AccountState.watchVarps`.
- **Chat**: `"New item added to your collection log: <name>"`
  (GAMEMESSAGE/SPAM) fires per new unlock. Names resolve against the pack's
  slot names — with two gotchas: the Tithe Farm "Farmer's shirt/jacket"
  slot chats as either "Farmer's jacket" or "Farmer's shirt" (never the
  slash form → `chatNames` aliases), and Body-type-B characters receive the
  odd item ids (13641/13643/13645/13647) which map onto the Body-type-A
  slot ids the tables carry (`aliases`).
- **Widget scripts**: script **7797** fires once when the collection log
  interface is built (attach the Log Sync button there; another plugin
  handling 7797 may `deleteAllChildren()` after us — re-attach next cycle
  AND self-heal on GameTick). Script **4100** is the per-item callback the
  interface fires for every OBTAINED slot as pages render — browsing the
  log passively leaks obtained ids. Script **2240** walks the entire log
  and re-fires 4100 for every obtained item; the Log Sync button presses
  the game's own Search toggle (`menuAction` on
  `InterfaceID.Collection.SEARCH_TOGGLE`) then runs 2240 — one click
  imports the whole log. Large logs stream 4100s over several ticks:
  settle 3 ticks after the last one before consuming the harvest.

**Why a baseline instead of comparing counts:** our obtained set counts
only slots the pack knows; after a game update adds new slots, "pack-known
obtained == varp" would never hold again and the sync nudge would nag
forever. Instead we pin the varp value at the last full sync as a
baseline, bump it by one per live chat drop, and only call the data stale
when the varp moves PAST the baseline (slots gained while the plugin was
off or before install).

**Zero-attempt drop rows** (`attempts: 0`, e.g. Brimhaven-voucher Graceful
recolours) are purchase/threshold slots, not drops — they never enter the
rate buckets but still count toward an activity's slot totals. An activity
whose every remaining item is zero-attempt has no rankable estimate and
drops out of the ranking (upstream behaviour).

The log window title ("Collection Log - 246/1,568") is still parsed on
open — it is the only game-truth source for the TOTAL slot count (the
varp only gives the obtained count, and the pack's slot list drifts from
the live game between regenerations).

## Farming time-tracking (module: farming, vendored engine: rl/)

Iron Hub does NOT track patches itself — the core **Time Tracking**
plugin (enabled by default in RuneLite) is the single writer, and we are
a read-only consumer of what it persists, the same interop model the
hub-approved Time Tracking Reminder plugin uses. Its RSProfile-scoped
config group `timetracking` holds:

- `<regionID>.<varbit>` = `<varbitValue>:<unixSeconds>` — one entry per
  farming patch, written whenever the player is in the region (farming
  transmit varbits only sync in-region).
- `birdhouse.<varp>` = `<varpValue>:<unixSeconds>` — the four Fossil
  Island bird house spaces; a seeded house takes ~50 minutes.
- `farmTickOffset` / `farmTickOffsetPrecision` — the account's farming
  tick offset, learned by the core plugin from observed growth ticks.
  Predictions align stage advances to `unixTime % (tickRate*60)` shifted
  by this offset; without it they're accurate to one tick.
- `autoweed`, `preferSoonest` (any- vs all-patches-ready semantics),
  per-patch notify/compost/protected flags.

Decoding a varbit value into produce/stage/crop-state lives in core's
`PatchImplementation` — a per-patch-type state machine that is
**package-private-adjacent** (`PatchState`, `FarmingWorld`,
`FarmingPatch` are package-private), so it cannot be used directly from
another plugin. tools/gen_timetracking.py vendors the farming + hunter
packages at the RuneLite tag matching our client dependency (package
rename, visibility widening, every config WRITE stripped);
TimetrackingParityTest reflectively sweeps the classpath client (2,048
varbit values per implementation, the whole world layout, bird house
varps) so a client update that changes decoding fails CI with a
regenerate instruction.

If the user disables Time Tracking, our data goes stale silently — the
tab checks the `runelite.timetrackingplugin` config key and says so.

Farm-run stop coordinates and teleport tables come from Easy Farming
(data/farm-runs.json); patch-types-at-a-stop are derived from the
vendored FarmingWorld by the stop's region, because the source plugin's
own labels overclaim (its catalog marks flower/allotment at herb-only
spots like Weiss).
