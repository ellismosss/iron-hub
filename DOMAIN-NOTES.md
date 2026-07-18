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

**Farm-run patch unlock requirements** (data/farm-runs.json `reqs`, curated
from the wiki — Easy Farming does NOT encode them; its "requirements" are
teleport-item requirements). Verified: Harmony Island's herb patch needs
the **Elite Morytania Diary** (not a quest — the island is reachable via
The Great Brain Robbery, but the *patches* are diary-locked); Troll
Stronghold = My Arm's Big Adventure; Weiss = Making Friends with My Arm;
Farming Guild herb/tree = 65 Farming, fruit tree = 85; Morytania/Canifis =
Priest in Peril started; Civitas/Auburnvale/Kastori/Aldarin = Children of the
Sun (Varlamore); Lletya = Mourning's End Part I started. Calquat needs 72
Farming (Tai Bwo Wannai / Kastori / The Great Conch), celastrus needs 85 (Farming
Guild high tier). Quest tokens are validated against the RuneLite Quest enum at
generation.

**Curated routes + new patch data** (data/farm-runs.json `routes` +
curated locations, tools/gen_farm_runs.py). Easy Farming's tables cover
herb/tree/fruit/hops only, so calquat (Tai Bwo Wannai, Kastori, **The Great
Conch** = the wiki's third calquat site, which Luke calls "Summer Shore" —
an instanced region so its live state may not always resolve) and the
Farming Guild **celastrus** patch are hand-added, with tiles placed in the
vendored FarmingWorld's PRIMARY region for that patch (FarmRunsPackTest
proves every stop's tile sits in a region carrying its patch type — the
Farming Guild's tree/fruit/celastrus all report region 4922). Easy Farming's
"Nemus Retreat" tree is the game's **Auburnvale** patch (same region 5427) —
renamed at generation (NAME_OVERRIDE) so the id is tree/auburnvale, keeping
its richer teleport list, rather than curating a duplicate. `routes` holds
Luke's optimal **All trees run** (regular + fruit + calquat + celastrus, 18
stops in a fixed order the auto-grouping templates can't express); its "Start
at GE" step is deferred to the banking-steps spec.

**The wiki run lineup** (built from oldschool.runescape.wiki/w/Farming_runs,
all curated `routes` in the pack): Herb, Tree, Fruit tree (fruit + calquat),
Combo tree (= the wiki's combined tree run), Hardwood tree (Fossil Island /
Locus Oasis / Anglers' Retreat — category `hardwood`, teak+mahogany saplings),
Hop and bush (interleaved single-category stops — hop and bush patches are at
DIFFERENT locations, so no multi-patch stop is needed; `bush` category, seeds
not saplings), Allotment/flower/herb, and Supercompost. Route order matches
the wiki's example sequences. Two modelling notes: (1) **co-located patches are
separate stops** — the allotment/flower/herb run lists allotment/<area>,
flower/<area>, herb/<area> as three stops per area (generated by copying each
herb area's tile + teleports, category+reqs adjusted — gen_farm_runs.py
`companions()`), matching how the combo tree run already splits gnome fruit/tree;
the wiki presents them as one stop per location, so this is more granular (a
candidate for a future "multi-category stop" grouping if it reads as verbose).
(2) **Prifddinas is omitted** from the allotment/flower/herb run — its vendored
region (13151) is instanced/off-map, so no tile both paths correctly and sits
in that patch region. The **Supercompost run** is a compost-bin-filling
activity, not a plant/harvest run: `compost` category stops (the bins at
Catherby/Ardougne/Falador/Port Phasmatys) have `categoryTab` = null, so they're
never growing-culled or sapling-culled and never auto-advance — the run is a
manual markThrough checklist. The Farming Guild BIG compost bin is left out to
keep every compost stop a plain COMPOST patch (FarmRunsPackTest region check).
The **Birdhouse run** (Bird_house_trapping wiki: Verdant Valley N→S then
Mushroom Meadow N→S on Fossil Island; Digsite pendant + magic mushtree; gated
on Bone Voyage + Hunter) is a second non-crop run — `birdhouse` category, no
PatchImplementation (bird houses are a separate hunter tracker, so
FarmRunsPackTest skips their region check), categoryTab = null → manual
markThrough. Tiles are approximate (on-island travel is via the mushtree, not
walking).

**Culling a run to what's worth doing** (Luke's Combo-tree-run spec —
FarmingRunModule.cull, applied at startRun to every run). Drop a stop when:
it's locked (the graph reqs above); its own-category patch is **confirmed
still growing** (the vendored predictor's GROWING view — nothing to harvest
yet; ready/empty/dead/diseased/unknown all read as "assume doable", Luke's
"100% confirmed no, otherwise assume yes"); or you hold no **sapling** to
plant there. When short on saplings for a type, keep only as many of its
stops as you can plant, in route order ("cull down to sapling count").
Saplings plantable per category are a generated pack section
(FarmRunsPack.saplings, from client ItemID constants —
tree/fruit/calquat/celastrus). Herb/hops plant seeds directly (no sapling
list) so they're never sapling-culled — only the lock/growing checks apply.
The overlay caps its upcoming-stop list (MAX_UPCOMING) so an 18-stop run
stays within the 200px budget; the sidebar tab carries the full checklist.

**Advancing a run stop** (matches Easy Farming's FarmingStepHandler): a
stop is done once the patch is PLANTED — its crop state reads GROWING via
the vendored predictor — AND COMPOSTED, for **every** category including
trees and fruit trees. Compost has no varbit; it's detected from the chat
message "You treat the … with (ultra|super|)compost." (the core
CompostTracker's pattern), attributed to the current stop by the player's
region. The compost is the *live* "I've worked this one" signal — reading
the persisted GROWING varbit alone advances on growth from a PREVIOUS run
(trees/fruit trees are still growing hours later), which made a fresh run
start half-done and cascade straight to complete (Easy Farming gates trees
on compost too, unless its pay-for-protection mode is on — we don't model
that yet, so a pay-to-protect tree stop is cleared with the manual
sidebar skip / markThrough). Never advance on mere arrival/proximity —
that both fires too early and can skip a patch you pass near, desyncing the
guide from Shortest Path.

## Repeatable events (module: dailies, pack: dailies.json)

**The claim varbits read 0 while the daily is UNCLAIMED.** Every "last
claimed" varbit (`ZAFF_LAST_CLAIMED`, `LUNDAIL_LAST_CLAIMED`,
`YANILLE_SAND_CLAIMED`, `SEERS_FREE_FLAX`, `ARDOUGNE_FREE_ESSENCE`,
`WESTERN_RANTZ_ARROWS`, `KOUREND_FREE_DYNAMITE`) is a flag *set when you take
it* — so `== 0` means "go and get it", not "nothing here". This is inverted
from the obvious reading and is the single easiest thing to get backwards;
`DailiesModuleTest.claimVarbitZeroMeansClaimable` pins it. Ported from core's
Daily Task Indicator (BSD-2, `licenses/runelite-LICENSE`) at
runelite-parent-1.12.32 — match it from their source, never from memory.

**Those varbits only refresh from the server on login.** Cross 00:00 UTC while
staying logged in and they still read "claimed" for a daily the server has
already reissued. Core covers this with a `dailyReset` flag
(`varbit == 0 || dailyReset`); Iron Hub's equivalent is `crossedReset()`, set
once the UTC day differs from the day we last saw a login. Erring toward
"available" is deliberate: showing a daily you have already done costs a
glance, hiding one you can claim costs the daily.

**`MORYTANIA_SLIME_CLAIMED` counts, it does not flag.** Robin's cap is your
Morytania legs tier (13/26/39), so "done" is `collected >= cap`, and the cap
moves when the diary tier does.

**Tears of Guthix has no cooldown varbit — but the game will tell you in
chat.** `TOG_COUNTDOWN` (5099) is the *in-minigame ticks-left* timer —
confirmed against the Improved Tears of Guthix Interface plugin, which uses it
exactly that way — and varbits 451–456 are just bitfields inside varp 449.
Nothing exposes "days until you can play again".

What does exist is Juna's reminder: speak to her to toggle it on, and the game
then prints **"You are eligible to drink from the Tears of Guthix."** (red,
`<col=ef1020>`) once a day, on login, every day for as long as you stay
eligible. That is the best signal available — it needs no history from us and
survives having missed a visit — so it wins over any timer we keep, and its
daily repeat means one missed login costs nothing. Two independent server
emulators reproduce the string identically (GregHib/void's `Juna.kt`; Zenyte's
`TearsOfGuthixServerLaunchSubscriber`), and both gate it on the reminder toggle
and the 7-day clock only. Match it as a **substring of the tag-stripped text**
— never an equality check on a string we cannot read out of the real cache.
Caveats: the reminder is **opt-in**, so silence is not evidence of ineligibility
(fall back, and tell the player to ask Juna); and it reports the 7-day clock,
not the 100k-xp / 1-QP gate.

Fallbacks, in order: the reminder flag → a visit we watched
(`TOG_MINIGAME_COLLECTING` going live stamps it, starts the 7-day clock and
retires the reminder) → UNKNOWN, rendered "?", never guessed either way.

It is also **not a fixed weekday**: the wiki says 7 days from your last visit
at 00:00 UTC ("the same day of the week in UTC as the previous attempt"), so a
Wednesday-based weekly reset is wrong.

**Two routing targets deliberately are not the NPC's tile** (pinned by
`DailiesPackTest.awkwardRoutingTargetsStayDeliberate`):
- *Lundail* stands in the Mage Arena bank at (3109,10357), which cannot be
  walked to at all — the lever in the ruin is the only way in, and Shortest
  Path ships no transport for it (checked against its `transports.tsv`). The
  pack targets the surface lever (3090,3958) and the note explains the webs.
- *Juna* is on **plane 2**, not 0. The wiki's `{{Map}}` template carries no
  plane, and the y+6400 convention makes plane 0 look right; Shortest Path's
  own tunnel out of the Lumbridge Swamp Caves runs (3226,9542,0) →
  (3219,9532,2), and every transport in that cave is plane 2. Advisor Ghrim is
  likewise plane 1 (Miscellania castle's first floor).

**Miscellania's approval varbit is 0..127, not 0..100.** Core's
`KingdomPlugin.MAX_APPROVAL = 127` and `getApprovalPercent = approval*100/127`
— so 100% approval is varbit **127**, and reading the raw value as a percentage
calls 78% "100". The stop is done at 100% approval because there is no
"collected today" flag to read and approval is what you go there to fix.

**Robin costs two inventory slots per bone.** He gives back 1 bonemeal AND 1
bucket of slime per bone, neither noted ("you will need free inventory space"),
so one inventory holds **14 bones** — legs 3 (26) and legs 4 (39) are two and
three trips. That is why he is the last stop and why his tile routes to the
Port Phasmatys bank (3689,3466 — Shortest Path's own bank.tsv) rather than to
Robin at (3676,3494): the stop starts at the bank.

**Two wiki rows mis-model easily.** Zaff's requirement column says *None* —
the Varrock diary only raises the cap (5/15/30/60/120), it is not a gate.
Bert's elite Ardougne reward changes the *delivery* (auto to bank on login),
not the 84 amount; no special case is needed for it, because the delivery sets
the same claim varbit and the stop culls itself.

**NMZ herb boxes are ironman-blocked by the game** — core literally checks
`IRONMAN == 0` — so they are not in the pack at all.

## STASH units & emote clue steps (module: clues, pack: clue-steps.json)

**A built STASH's game object only renders for the player who built it** —
the `ObjectID.HH_*` object spawning in the scene is proof the LOCAL player
built that unit (the STASH Tracker plugin's core trick, ported). The
gameval `ObjectID` class is **split across `ObjectID` and `ObjectID1`**
for class-file size — resolve constants against both.

**Filled state has no varbit**: it comes from deposit/withdraw chat
messages (GAMEMESSAGE/SPAM/MESBOX containing "stash", keyword-loose),
attributed to the STASH the player clicked within 5s, else the nearest
unit within 5 tiles. A STASH **filled before the plugin existed is
undetectable** — the manual filled toggle in the tab is the escape hatch,
not a luxury. Filling implies built; emptying leaves built alone.

**Emote clue items**: core's EmoteClue table is the canonical source —
its requirement DSL (`item`/`any`/`all`/`range`/`xOfItem`/`emptySlot` +
named constants) maps onto the requirement graph directly, and its
`ItemVariationMapping.getVariations` streams collapse to single `item:`
leaves because the graph already counts variants. `emptySlot` steps
("nothing equipped", "no jewelry") can COEXIST with item requirements.
Core's STASHUnit constant names drift from mirrors in four places (the
two Warriors' Guild banks disambiguate by object id, not name). POH
costume-room contents are not readable — "owned" means bank + carried,
and the UI must say so.

## Slayer detection & block lists (module: slayer, pack: slayer-tasks.json)

**Current task** lives in varps the core Slayer plugin reads: `SLAYER_COUNT`
(394, remaining kills), `SLAYER_TARGET` (395, task id), `SLAYER_AREA` (2096,
Konar's assigned area id), `SLAYER_COUNT_ORIGINAL` (4258, the assigned
amount). Target id **98 is the boss-task sentinel** — the real task resolves
through `DBTableID.SlayerTaskSublist` by `VarbitID.SLAYER_TARGET_BOSSID`.
Names come from `DBTableID.SlayerTask.COL_NAME_UPPERCASE` and areas from
`DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER` — **DBTable reads are
client-thread only** (they hit the cache).

**The assigning master** is `VarbitID.SLAYER_MASTER` (4067). Its values are
the same "focus ids" the game's own block-list clientscripts (8025–8033)
switch on: 1 Turael/Aya · 2 Mazchna · 3 Vannaka · 4 Chaeldar · 5 Duradel ·
6 Nieve/Steve · 7 Krystilia (corroborated by core's
`KRYSTILIA_SLAYER_MASTER = 7`) · 8 Konar · 9 Spria. **Spria shares Turael's
block list** (script8025 routes 1 and 9 to the same reader).

**Block lists are per-master in the game itself** (post-2025): varbits
`SLAYER_BLOCKED_<MASTER>_1..6` (17812–17859) plus `_DIARY` (17860–17867,
the Lumbridge Elite slot), each holding a blocked task's `SlayerTask.COL_ID`.
The `SLAYER_BLOCKED_*_OLD` varbits (17805–17811) are the retired global
list. Resolve slot values to names through the DBTable; a slot value with no
row is unknowable — show "Task #N", never guess.

**Completion vs skip**: `SLAYER_TASKS_COMPLETED` increments only on genuine
completion (`SLAYER_WILDERNESS_TASKS_COMPLETED` is Krystilia's separate
streak — sum both for the completion signal). A points skip zeroes
`SLAYER_COUNT` without touching either streak, so **never infer kills or
completion from the count reaching 0** — a drop to exactly 0 bigger than a
Dusk&Dawn double-kill (2) is a skip or reset.

**Point unlocks are individually named varbits** (`SLAYER_UNLOCK_*`,
`SLAYER_LONGER_*` extends, `SLAYER_AUTOKILL_*`, `SLAYER_HELM/RING/AMMO_
UNLOCKED`) — no bitfield decoding needed; the packed
`SLAYER_REWARDS_UNLOCKS*` varps are the transport, not the API. The pack
joins wiki reward names (which are puns and change tone, never guess —
"I Wildy More Slayer" costs 0 points) to varbit constant names, verified
reflectively by the pack test.

**NPC target matching (core parity)**: alternate names per task come from
core's package-private `Task` enum (generated into the pack at the pinned
tag); match `(?:\s|^)NAME(?:\s|$)` case-insensitive against
`npc.getTransformedComposition().getName()` with NBSP→space, and require an
`Attack` action (`Pick` for zygomite Fungi). Composition, not spawn ids —
variants and transforms just work.

**Wiki sources**: Turael/Mazchna/Nieve/Duradel keep their assignment tables
on `<Master>/Slayer_assignments` subpages; Spria/Vannaka/Chaeldar/Konar/
Krystilia inline them under `==Tasks==` with different columns (Krystilia
uses rowspan groups for wilderness bosses — continuation rows have fewer
cells than headers). Monster stats come from the wiki's **Bucket API**
(`action=bucket`, bucket `infobox_monster`, hard 5000-row cap per query) —
fetched at generation time only; the client never phones home.

## Swing/data gotcha — never key a HashMap by an enum and iterate it

Java enums inherit `Object.hashCode()`, i.e. an **identity** hash, so a
`HashMap<SomeEnum, V>` iterates in a different order on every JVM run. Anything
that renders that order (a tile strip, a tab bar, a list) will silently
rearrange itself between sessions — which is exactly what the farm overview's
category tiles did, because the vendored `FarmingTracker.buildCustomTabData`
used a `HashMap<Tab, ...>` while the rest of that class already used `EnumMap`.

Use `EnumMap` (iterates in ordinal order) or a `TreeMap`, and have the UI state
its display order explicitly rather than inheriting a map's iteration order.

## Panel icons — never re-request an item sprite on every rebuild

`ItemManager.getImage(id)` returns ONE shared `AsyncBufferedImage` per item, and
`AsyncBufferedImage.onLoaded(r)` either **appends to an unbounded listener list**
(while the sprite is unresolved) or **queues `r` on the client thread** (once it
is). Neither is free, and a tab that calls it on every rebuild pays it every
time.

The trap is the login screen. `ItemManager.loadImage` refuses to resolve
anything while `gameState < LOGIN_SCREEN`, so listeners registered there just
pile up — one set per rebuild, and the panel rebuilds on every AccountState
change. The moment the game state advances (i.e. you click Play Now) `loaded()`
runs **every** accumulated listener at once, synchronously, on the client
thread, in the middle of the login sequence. With a slow `SCALE_SMOOTH` in each
callback that is enough to stall the client.

Use `com.ironhub.ui.components.SpriteCache` (one request per item id + size for
the life of the tab, scaled once, repaint when it lands). ItemManager's own
javadoc says the same thing for the simple case: *"If this is used for a UI
label/button, it should be added using AsyncBufferedImage::addTo"* — `addTo` is
safe because it adds a listener only while unloaded and does nothing after.
