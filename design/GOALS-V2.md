# Goals v2 — The Central Goals System

Status: **IMPLEMENTED** (Luke, 2026-07-18/19 — G1–G8 landed, GoalsHubTab is
the sole Goals surface, the old PlannerTab/GoalsTab/mockup deleted in G7
part 2). Original agreed outline in §5.2; interface revised after his
round-2 mockup feedback (§0.1 + §6). Per-slice history: design/GOALS-V2-PLAN.md.
Companion docs: ENGINE-DESIGN.md (the engine this builds ON, not replaces),
design/PLANNER-UX.md (the Today/Route/Goals VIEW SPLIT there is
**superseded** — Luke: one cohesive interface, no view switcher).

## 0.1 The vocabulary (Luke, round 2) — Goals, Tasks, Routes

Two distinct things, named in the client:

- **Goal** — the final destination of a route (Bow of Faerdhinen).
- **Task** — any stepping stone toward it (Complete Song of the Elves).
- **Route** — a Goal plus its ordered Tasks. The `+` affordance in every
  module **creates a Route** with that step as the Goal.

A Task can itself be a Goal if the player sets it as a destination —
the same node is a Goal in one Route and a Task in another. Multiple
Routes coexist, each with its own Task list; shared Tasks appear in
every Route that needs them (marked shared — the dedupe made visible).

Engine mapping (no new machinery): a Route = one selected goal's slice
of the merged DAG — the per-goal route slice the engine already
computes. The merged plan stays the internal truth; the UI presents it
per-Route, never as one global ordered list.

**And the presentation philosophy, per Luke: not everything is time.**
Players act to raise stats, make their lives easier, and unlock
interesting things — time-saved is ONE benefit among several, not the
frame. Rows lead with the name and the benefit ("unlocks fairy rings",
"frees 2 inventory slots", "gates Song of the Elves"); hours appear as
one quiet stat where honest, never as every row's headline. The cost
model keeps using time internally (ordering needs a currency) — the
*copy* stops leading with it.

The brief (Luke, 2026-07-18), restated as nine capabilities:

1. Ten modules provide "add this as a goal".
2. Complete, truthful requirement knowledge + live account knowledge.
3. Complete, truthful knowledge of every currently viable meta method.
4. Complete, truthful expected-time knowledge (xp rates, drop rates, …).
5. Recommended progression pathways per account type (iron-types + normal).
6. A running account of current and accomplished goals, filterable.
7. Efficient pathways for any goal combination, including suggesting
   stepping-stone goals the player hasn't considered.
8. Player prioritisation and custom pathways.
9. A centralised, beautiful stats-hub interface.

---

## 0. Where we actually stand (audit, 2026-07-18)

Most of this system exists. The engine (ENGINE-DESIGN E1–E5) already does
merged-DAG expansion, forward-simulated routing, banked-xp discounts,
quest-xp effects, boosts, pins/snoozes/bans/preferred-methods, dry-run
merge previews (<100 ms), and honest NaN for anything unsourced. Six goal
families ship today (`gear:` `ca:` `diary:` `clog:` `clue:` `custom:`).
The gaps, precisely:

| # | Brief point | Status | Gap |
|---|---|---|---|
| 1 | 10 provider modules | 6 of 10 | **PoH, QoL, Bank, Supplies have no goal affordance**; wiring is hand-spread (see §1) |
| 2 | Requirement truth | ✅ solid | graph + packs + CI parse-sweeps already deliver this |
| 3 | Meta methods | ⚠️ seed | methods.json = 97 curated methods / 24 skills; the designed generator (WOM EHP + wiki calc join) was never built; no freshness surfacing |
| 4 | Time knowledge | ⚠️ half | TRAIN/QUEST costed honestly; **every KILL step is NaN** and clog.json's ironman rates (bundled!) are consumed only by ClogRanker, never the engine; OBTAIN leans on gear-pack `hours` only |
| 5 | Pathways per account type | ❌ | plugin is standard-ironman everywhere; AccountState has **no account-type concept**; route prior/rates/gear chart are all iron |
| 6 | Running account + filters | ⚠️ half | live goals render fine; **no completion timestamps, no archive, no filters** — "Completed" is recomputed membership |
| 7 | Combination pathways + stepping stones | ⚠️ half | merged-DAG routing = done; **stepping-stone suggestion doesn't exist** (but previewMerge is 90% of the machinery) |
| 8 | Prioritisation | ⚠️ half | step-level pin/snooze/ban/prefer = done; **no goal-level priority** |
| 9 | Stats-hub interface | ❌ | Goals view is the legacy 2d checklist embedded under a search box |

So Goals v2 = **one refactor + two data arcs + three features + one
interface**, on top of a proven engine. Not a rewrite.

---

## 1. W1 — Goal domain unification (brief #1, #6)

### 1.1 The problem with today's wiring

Adding a goal family currently means touching four places in lockstep:
a seed class + map in PersistedState (five parallel maps exist:
`caGoals`, `diaryGoals`, `clueGoals`, `clogGoals`, `customGoals`), a
`to<X>Goal` builder + an `allGoals` loop + a `removeGoal` prefix branch
in GoalPlannerModule, and add/remove/proof accessors on AccountState.
Six families in, the pattern is proven — and due for its extraction.
Four more families would mean four more rounds of the same synchronized
edits.

### 1.2 One seed shape, one registry

```java
GoalSeed {
  String family;      // "gear" | "ca" | "diary" | "clog" | "clue" |
                      // "poh" | "qol" | "quest" | "skill" | "pack" | "custom"
  String id;          // family-local id; goalId = family + ":" + id
  String name;        // display, snapshotted (renders offline — house rule)
  int iconItemId;     // sprite seed (0 = family badge icon)
  List<String> reqs;  // graph parse() strings — the honest planner steps
  List<String> proofs;// achieved proofs (unlock:/item:/itemx: strings)
  Map<String,String> meta; // family extras (tier, activity, region…)
  long addedAt;       // epoch ms — new; feeds the archive (§1.4)
}
```

One persisted map `Map<String, GoalSeed> goalSeeds` replaces the five
legacy maps (one-time migration in `activateProfile`; Gson keeps the old
keys harmless afterwards). `GoalPlannerModule.toGoal(GoalSeed)` replaces
the six builders; `allGoals` becomes one loop; `removeGoal` one branch.

Each providing module registers a **GoalProvider** (a small interface,
not a Guice ceremony — a static registry list in GoalPlannerModule):

```java
interface GoalProvider {
  String family();
  // marks proof unlocks from live data; modules already do this —
  // the interface just names the existing pattern
}
```

The "track this = synthetic goal" recipe from CLAUDE.md is unchanged in
semantics; this is the same pattern with the boilerplate factored out.
**This lands first** — every later slice builds on it, and it's the only
part of v2 that touches existing behaviour (pin with the existing goal
tests + a migration test seeded from a legacy-format profile JSON).

### 1.3 The four new providers

| Module | Goal | Reqs (already in packs) | Proof |
|---|---|---|---|
| **PoH** | `poh:<tierId>` — "Build Ornate rejuvenation pool" | tier's `skillb:Construction:` + gates from poh.json | the existing `pohBuilt` detection / manual mark |
| **QoL** | `qol:<id>` — any checklist unlock | qol.json entries already carry graph reqs | the checklist's own met-state |
| **Bank** | a `+ Goal` beside the per-skill target level → `skill` family goal (`skill:<Skill>:<lvl>` req) | — | level reached |
| **Supplies** | `supply:<itemId>` — one-shot "stock N × <item>" (Luke's call, §5.2 Q1): req/proof = `item:<id>:<N>` (bank+carried, variation-aware), completes when owned ≥ N, re-addable after completion | item req | ownership count |

Bank is deliberately thin: banked XP is already a **cost discount**
inside the engine (BankedXp.compute feeds every replan), which is the
correct modelling — "930k banked Prayer xp" isn't a goal, "Prayer 70"
is, and the engine already knows the bank shortens it. The provider just
promotes the target the Bank tab already persists.

Also folded in: the 6 goals.json pack goals become the `pack` family
(no behaviour change, kills the "bare id" special case), and
`custom:skill:` / `custom:quest:` become first-class `skill` / `quest`
families (they already behave like it).

### 1.4 The archive — completion becomes an event, not a recomputation

Today `isAchieved` is recomputed live and "Completed" is just current
membership — nothing records *when*. New: `List<GoalRecord>` in
PersistedState (capped like the farm/slayer records):

```java
GoalRecord {
  String goalId, name, family;
  long addedAt;            // from the seed
  long completedAt;        // 0 = "detected, not dated" (see below)
  double estimatedHours;   // plan's known-hours share at add time
  double lastPlanHours;    // … at completion — honest estimate-vs-actual
}
```

Written when a **selected** goal's `isAchieved` flips false→true,
edge-triggered with the silent-first-sync seed (the notification
pattern): goals already achieved when first observed — pre-plugin
accomplishments, the achieved-by-ownership feature — get
`completedAt = 0` and render as "detected on your account", never an
invented date. Goal removal never writes a record (the never-flash-Done
rule). This is what makes brief #6's "running account of accomplished
goals" and the estimate-vs-actual honesty line ("estimated 34h · took
29h", PLANNER-UX §5) possible at all.

---

## 2. W2 — Knowledge expansion (brief #2, #3, #4)

### 2.1 Reframing "complete and truthful"

Flag, up front: **completeness and truthfulness pull against each
other.** A system that claims complete knowledge of the meta is lying
somewhere; the meta shifts, rates are contested, and half the "time to
goal" numbers in any guide are vibes. The engine's existing honesty
boundaries (ENGINE-DESIGN §4: no invented rates, NaN over fabrication,
RNG communicated with P90) are the right frame. So v2 targets:

> **Audited coverage with provenance and freshness** — every rate
> carries its source and date, the plan footer says "meta as of
> <month year>" from the packs' `generated` stamps, coverage gaps
> render as "?" and sort last, and CI counts coverage so regressions
> are loud.

This is achievable and provable; "complete" is neither.

### 2.2 methods.json v2 — the full generator

The current pack is a hand-curated 97-method seed. ENGINE-DESIGN §11
already specifies the real pipeline, never built:

- **tools/gen_methods.py**: WOM's open-source ironman EHP configs
  (pinned commit) for the rate ladders and bonus-xp edges + wiki
  `Module:Skill calc/<Skill>` Lua for per-action xp/materials (the
  xp-actions.json generator already parses these — proven) + the wiki
  Ironman Guide per-skill pages as the feasibility/requirement overlay.
  Requirement strings stay the hand-curated layer (WOM deliberately
  lacks them) — kept in a curated overlay file the generator joins, so
  regeneration never loses them.
- Every method gains `source` + the pack gains `generated` (both fields
  already exist — they start being surfaced and enforced).
- Coverage floor in CI per skill (a ladder without a level-1 floor
  method stalls plans — already a known rule, becomes a pack test).
- **Freshness surfacing**: plan footer + Goals hub show "meta:
  <Mon YYYY>"; a pack older than ~4 months renders a staleness note
  (the ca-completion cadence precedent). Regeneration is a ritual on
  meta shifts, not a live fetch — external HTTP stays build-time only.

### 2.3 Drop-rate time — wire the rates we already bundle

The single biggest truthfulness gap in the live engine: **every KILL
step and most OBTAIN steps show "?"** while clog.json sits in the jar
carrying exactly the needed numbers — 256 activities with ironman
completions/hr and 2,522 drop rows (the Log Adviser port), already
TTNS-ranked by ClogRanker in the collection-log tab.

Fix: a shared **RateSource** consulted by CostModel:

1. `clog:` goals — the seed already snapshots the activity; resolve
   (activity → completions/hr, item → drop rate) → expected hours +
   P90 via the geometric math **already in CostModel**
   (`expectedKillsForDrop` / `unluckyKillsForDrop`, currently unwired).
   ClogRanker and the engine converge on one implementation (they are
   the same math today, written twice).
2. `kc:` leaves — activity completions/hr from the same table.
3. Gear OBTAIN steps whose item appears in a clog activity — same
   resolution, with the gear pack's curated `hours` kept as the
   fallback and the two cross-checked in a pack test (order-of-
   magnitude disagreement = data bug, fail the build).
4. Personalization stays as designed (ENGINE-DESIGN §8): the loot
   module's measured kills/hr beats the pack after real trips; drop
   *rates* are never personalized.

This makes "expected time to any goal" real for the families where it
was weakest (clog, gear bosses, kc), at near-zero new-data cost. The
full `sources.json` obtainment resolver (drop/craft/shop/grow per item)
from ENGINE-DESIGN §11 remains the eventual end-state; it is **not**
needed for v2 — the gear pack + clog rates cover the goal-reachable
item space we actually plan over. Grow-on-demand later.

### 2.4 Requirement truth (brief #2) — already delivered, kept honest

No new machinery. The requirement graph + per-pack parse sweeps + the
manual-gate fallback are the design. One addition: a **whole-catalog CI
sweep** — every req string reachable from any goal family parses
non-manual unless whitelisted, and the whole expanded catalog is
acyclic (ENGINE-DESIGN §11's maintenance checks, promoted to a test
that spans all ten families).

---

## 3. W3 — Planning intelligence (brief #7, #8)

### 3.1 Stepping-stone suggestions — previewMerge in a loop

`previewMerge` already computes, in <100 ms, exactly the number a
suggestion needs: marginal plan delta for one candidate. The suggester
is that machinery pointed at a **curated candidate set** the player
hasn't selected:

- effects.json unlocks (fairy rings, diary teleport tiers, spellbooks —
  ~60 entries, each with modelled downstream discounts)
- diary tiers whose reqs are near-met
- gear-chart items that change kill rates for planned KILL/OBTAIN steps
- quests with large xp effects against planned TRAIN nodes

For each candidate: `plan(goals + candidate)` on the projected state.
The output is the **SUGGESTIONS** section (round-2 naming), and each
suggestion is phrased by its benefit KIND — time is one kind, not the
frame:

- **Multi-route**: the candidate's effects satisfy unmet requirements
  in ≥2 Routes ("Fairytale II — advances 3 of your routes"). Gate:
  counted against the actual DAG, never implied.
- **Long-run time**: `netHours < 0` **in known hours** — a suggestion
  backed by NaN is not a suggestion ("Ardougne cloak 2 — saves ~3.2h
  across your routes").
- **Life-easier**: curated QoL benefit copy carried by the pack
  (qol.json's sourceNote grammar — "Rune pouch: frees 2 inventory
  slots every trip"), offered when its reqs are near-met. No invented
  benefit copy — pack-sourced only.
- **Merge offers**: two Routes sharing ≥K Tasks (K≈5) get a combine
  suggestion ("Bowfa & Quest cape share 9 tasks"); accepting makes one
  two-Goal Route — engine-trivial, the merged DAG already is one.

Budget: ~40 candidates × <100 ms ≈ 4 s worst-case, run on the existing
planner executor after replans settle (never per-keystroke), memoized
on the plan fingerprint. Accepting a suggestion creates a normal Route
through the provider system — a stepping stone IS a goal, no new
species.

### 3.2 Goal-level priority (brief #8)

Step-level constraints (pin/snooze/ban/prefer) survive unchanged. New,
at the goal level:

- **Priority tier per goal**: High / Normal / Someday (persisted on the
  seed). Feeds the router's value term — a step's direct-progress value
  is weighted by the max tier among the goals it serves. High-tier
  goals' steps front-load; **Someday** goals stay in the DAG for dedupe
  and merge-preview math but contribute ~zero urgency (their unique
  steps sink; their shared steps still count — the honest way to "park"
  a goal without lying about overlap).
- **Goal pin** (Luke's addition, §5.2 Q3 — absorbs `activeGoal`): a
  player who doesn't want the suggested path pins one or more Routes as
  ACTIVE — a pinned Route's tasks outrank everything (the step-pin
  mechanism at goal granularity), so the Current task serves the pinned
  Route(s) first. Pins outrank tiers; among pinned Routes, pin order.
- **Per-Route task order** (round 2): within a Route the player can
  manually re-order Tasks and pin Tasks to the top. On 225px this is
  the farm-picker arrows pattern (stacked up/down + a pin affordance),
  not drag. Hard dependencies still gate — arrows move a Task among its
  FEASIBLE positions only (SotE can't precede its prereqs), and the
  router honours the player's order within feasibility. Persisted
  per-Route (`routeTaskOrder` map on the seed), same spirit as
  `farmRunOrder`.

Custom pathway remains **reorder-by-constraint, never drag**
(PLANNER-UX principle 1 — 225 px Swing drag is misery, and constraints
keep the plan feasible + explainable). Priority tiers + pins express
everything drag would, with consequences shown in hours.

### 3.3 What deliberately does NOT change

The router (greedy value-density + lookahead over forward simulation),
the no-silent-reshuffle banner, the explain cards, determinism,
the 400 ms debounced off-thread replan, NaN honesty. v2 feeds the
same engine better data and more goal families; it does not touch the
search. Performance: ten families multiply goals, not DAG size — dedupe
is the point; the existing <100 ms budget holds (property-tested).

---

## 4. Account types (brief #5) — the scope flag

Iron Hub is a **standard-ironman plugin** by charter (DESIGN.md, README,
every pack: iron rates in clog.json, the ironman OQG route prior, the
ironman progression gear chart). AccountState has no account-type
concept at all today. Honest options:

- **(a) Iron-first v2** (recommended): add `accountType` to
  AccountState read from the game's own ironman-status varbit (one
  varbit, cheap, also lets us stop assuming); v2 plans for
  iron-types. Standard/HCIM share everything; UIM diverges on banking
  (banked-xp discounts and "banked" resource lines are wrong for UIM) —
  v2 *detects* UIM and disables banked-xp discounts honestly, nothing
  more.
- **(b) Normal-account support** = a genuinely different planning
  economy: every tradeable item gains a BUY path, which needs GE
  prices. Live prices violate the opt-in-HTTP gate unless gated on a
  toggle; bundled prices are stale in days (dishonest); and the route
  prior, method rates and gear chart all have non-iron variants to
  source. This is its own arc with its own data pipeline — proposed as
  **post-v2, opt-in-gated**, not silently folded in.

Decision needed (§5.2 Q2).

---

## 5. Flags, risks, and improvements to the concept

### 5.1 Improvements proposed over the brief

1. **"Complete" → "audited + provenance + freshness"** (§2.1) — the only
   truthful version of points 2–4, and it's testable.
2. **Stepping stones as ordinary goals** via the provider system — no
   parallel "suggestion" species to maintain (§3.1).
3. **The archive as the reward loop** — estimate-vs-actual per completed
   goal is the honest dopamine (no streaks, no confetti; PLANNER-UX §5
   already agreed this tone).
4. **Someday tier** — the merge math keeps working for parked goals
   instead of deleting them (§3.2).
5. **Provider refactor first** — four synchronized-edit sites collapse
   to one; the four new modules then cost ~a class each.

### 5.2 Open questions — ANSWERED (Luke, 2026-07-18)

1. **Supplies runway goals** → one-shot **"stock N × item"** goals
   (§1.3). Not a maintenance lane.
2. **Account types** → **iron-first for v2** confirmed. Account-type
   varbit detection + UIM banked-xp honesty in scope; normal-account
   economy is a later opt-in arc.
3. **Goal priority** → **tiers**, plus **goal pins**: the player can
   pin goals as active when they don't wish to follow the suggested
   path (§3.2).
4. **Where the hub lives** → the entire Goals interface lives under
   the left-most **Goals nav block** (where the Goal planner already
   mounts — it remains that hub's single module).
5. **Stepping-stone surfacing** → inside the single Goals surface
   (a "Worth a detour" section) — resolved by the one-interface
   decision below.
6. **Meta freshness** → quarterly-ish regeneration agreed, **with a
   standing gate: never regenerate/implement a meta refresh without
   first laying the proposed changes out to Luke** (same class of gate
   as Hub submission).
7. **Archive depth** → not explicitly answered; going with the house
   cap (~200 records) — cheap to lift later.

**And the structural decision: the three PlannerTab views
(Today | Route | Goals) are REMOVED.** One cohesive interface, no view
switcher, "for now" (re-splitting stays open if the single surface
proves too long in practice). §6 is redesigned accordingly.

### 5.3 Risks

- **methods.json v2 generator** is the riskiest data arc (three sources
  joined; WOM's EHP configs change shape occasionally). Mitigation: the
  curated overlay file owns requirements and survives regeneration;
  the seed pack remains the fallback; calibration tests (±25% band
  vs ehp-calibration checkpoints) catch drift — all already designed
  in ENGINE-DESIGN §11–12.
- **Suggester cost** (~4 s worst) must never block a replan — it runs
  after, cached on fingerprint, and renders "computing…" honestly.
- **Migration** of five seed maps → one: a legacy-profile fixture test
  is mandatory (byte-seeded JSON in the old shape, assert identical
  goals after load).
- **Archive edge cases**: login replay, profile switches, and
  achieved-at-add-time goals must never fabricate `completedAt` — the
  silent-first-sync pattern covers all three; negative-path tests
  required (house rule).

---

## 6. The interface (brief #9) — ONE cohesive surface, FOUR sections

Revised to Luke's round-2 feedback (2026-07-18): the first mockup was
too cluttered and too time-centred. Cut: the NOW section and its
budget chips, the standalone ROUTE section (redundant once Routes live
in GOALS), the COMPLETED section (reached through the Done-this-month
block instead), the Active/Someday/Done filter chips. What remains:

1. **Hero** — Routes active / Done this month as the two stat boxes
   (**Done this month is the click-through to the completed archive**,
   a depth-2 view with back arrow — dates + est-vs-took live there,
   not on the main surface), plus one quiet footer line
   (`meta: Jul 2026 · ~212h total` — the only total-hours figure on
   the surface).
2. **CURRENT TASK** — the single next step (plan head, pinned Routes
   first): task name, a real progress bar with level/percent labels,
   the stats that describe *progress* (xp left, actions left, time at
   your pace — number-left first, time last), a **benefit line**
   ("Why: gates Song of the Elves · faster travel for 3 routes"), and
   which Routes it serves.
3. **GOALS** — every Route as an expandable row, **grouped by category**
   (Quests · Gear · Level unlocks · Unlocks · Supplies — derived from
   the seed family). Route row = icon + Goal name + thin progress meter
   + task count; expand (one at a time, hub grammar) = that Route's
   Tasks sorted next-first, each with a status glyph, shared-mark, and
   the up/down + pin affordances for manual ordering (§3.2). Pinned
   Routes wear the select fill. Someday Routes sit dimmed within their
   category. Capped rows with "+ N more" per the house rule. The
   add-a-goal search field closes the section.
4. **SUGGESTIONS** (renamed from Worth a detour) — steps the player
   hasn't selected, each phrased by its benefit kind, never
   hours-first: **multi-route** ("advances 3 of your routes"),
   **long-run time** ("saves ~3.2h across your routes"), **life-easier**
   ("frees 2 inventory slots every trip"), and **merge offers** when
   Routes share many Tasks ("Bowfa & Quest cape share 9 tasks —
   combine?"). Accepting adds a Route (or combines two into one
   two-Goal Route — engine-trivial, the merged DAG already is one).

The plan-update banner survives (inside GOALS, above the Route rows)
— no-silent-reshuffle is a trust rule, not clutter. Both themes,
render-tested, judged in the Design lab before any wiring.
PlannerOverlay (the canvas companion) is unaffected — it follows the
plan head regardless of how the sidebar arranges itself.

## 7. Slices (post-agreement, each green + committed)

0. **G0** — the Design lab mockup of §6 (in flight — judged before code).
1. **G1** — GoalSeed + provider registry + migration (behaviour-neutral).
2. **G2** — four new providers (PoH, QoL, Bank, Supplies stock-N) + archive.
3. **G3** — RateSource: clog rates into CostModel; ClogRanker converges.
4. **G4** — methods.json v2 generator + freshness surfacing (regenerations
   are proposed to Luke BEFORE landing — standing gate, §5.2 Q6).
5. **G5** — priority tiers + Someday + goal pins.
6. **G6** — stepping-stone suggester.
7. **G7** — the single Goals surface replaces PlannerTab's three views.
8. **G8** (post-v2, separate arc) — account-type detection now; normal-
   account economy later, opt-in.
