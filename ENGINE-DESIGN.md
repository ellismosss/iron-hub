# Iron Hub Goal Engine — Design

Status: **implemented** (E1–E5 shipped in `com.ironhub.engine` +
`PlannerTab`, July 2026 — this document remains the blueprint and the
rationale of record; §13's roadmap is done except noted deferrals:
sources.json folded into the gear pack for v1, measured-rate
personalization pending live data). Companion to DESIGN.md (§2.2 requirement graph, §3.13 Goal
Planner, §3.14 What Now) — the engine described here is the layer that turns
those modules from trackers into a router. Appendices A–D carry the 2026
meta research the models are calibrated against and the prior-art survey
that shaped the algorithm choices. One research finding frames the whole
effort: **no shipped OSRS tool computes an ordered, time-estimated route
from goals + account state** — everything existing is either a manual
checklist or a frozen expert route with progress sync (Appendix C §2).
The engine is first-of-kind, which argues for conservative algorithms
and aggressive explainability rather than novelty on both axes at once.

---

## 1. Purpose

Iron Hub already knows *what is true* about the account (AccountState: every
skill xp, quest state, container, kill count, unlock flag, diary/CA task) and
*what the player wants* (selected goals: pack goals, gear targets, CA tasks,
diary tasks). The engine's job is the missing third piece: **what to do, in
what order, and how long it will take** — a routed, time-estimated, always
explainable plan from the current account to the selected goals, recomputed
live as the account changes.

Design tenets, in priority order:

1. **Explainable over optimal.** Every step carries "why this, why now,
   how long". A plan the player distrusts is worthless even if it is 3%
   faster. No black-box scores.
2. **Feasible always.** A plan is a sequence in which every action's
   requirements are met by the time it starts (given the actions before
   it). Feasibility is a hard invariant, property-tested; optimality is a
   soft target, heuristic.
3. **Deterministic and incremental.** Same state + same goals + same packs
   = same plan, every time. Recompute is cheap enough to run on every
   relevant state change (off the client thread, debounced).
4. **Data over code.** The meta lives in schema-validated data packs with
   wiki provenance, like every other Iron Hub pack. When the meta shifts
   (it does — see Appendix A's 2024–26 change log), we regenerate packs,
   not rewrite Java.
5. **Personalized where measured, principled where not.** The engine
   starts on community rates and drifts toward the player's measured
   rates (session xp/hr, kills/hr from the loot module) as evidence
   accumulates.

## 2. The problem, stated precisely

> Given account state S₀, a set of goals G = {g₁…gₙ} (each a requirement
> expression over the shared graph), and an action catalog A (each action
> has a requirement expression, a state-dependent duration, and effects
> that mutate projected state), find an ordering of a subset of A that
> reaches a state satisfying every gᵢ, minimizing total active time.

Three properties make this harder than topological sort and easier than
general planning:

- **Shared substructure.** Goals overlap heavily (Bowfa and Song of the
  Elves share a quest chain; three goals may all want 70 Agility). The
  win from merging goals is the single largest optimization available —
  far larger than clever ordering.
- **State-dependent durations.** Training 70→85 Mining takes fewer hours
  *after* unlocking better methods/gear; every travel-shaped task is
  faster after fairy rings/diaries/POH. Actions can *discount* later
  actions without being prerequisites. This is what makes ordering matter.
- **Stochastic terminals.** Drop-gated goals (Bowfa's enhanced seed, a
  visage) have expected durations, not fixed ones. The engine must plan
  in expectation and communicate spread, never pretend certainty.

Formally this is precedence-constrained scheduling with sequence-dependent
processing times — NP-hard (contains P|prec|Cmax). We do not attempt exact
optimization. The shipped algorithm (§6) is a layered heuristic: exact
where the structure is a DAG (dependency closure, critical path), greedy
value-density with bounded lookahead where it is not (ordering under
discounts), with an explainability contract at every layer. Appendix C
surveys the precedents (EHP frameworks, speedrun routers, factory-game
planners) that justify this compromise.

## 3. Architecture

Seven layers, each pure and separately testable. Data flows down; nothing
below reaches back up. Everything runs off the client thread; the client
thread only snapshots AccountState.

```
AccountState ──snapshot──▶ [1] ProjectedState        (virtual account)
data packs  ──load──────▶ [2] ActionCatalog          (what can be done)
selected goals ─────────▶ [3] GoalCompiler           (goals → needed leaves)
                          [4] DependencyExpander     (leaves → action DAG, merged)
                          [5] CostModel              (duration of each action *in context*)
                          [6] Router                 (feasible order, minimized time)
                          [7] Plan + Explainer       (steps, times, whys → UI)
```

### 3.1 ProjectedState

A lightweight, mutable copy of the planning-relevant slice of AccountState:
skill xp (not just levels — partial progress matters), quest states, unlock
flags, item multiset (bank ∪ inventory ∪ equipment, canonicalized via
ItemVariationMapping like the rest of the plugin), kill counts, and a small
set of derived **context flags** the cost model reads (travel tier, best
method unlocked per skill). Applying an action's effects produces the state
the *next* action is costed against. The existing `Requirement.isMet`
evaluates against AccountState; the engine needs the same evaluation against
ProjectedState — the requirement graph gains one method
(`isMet(StateView)`) where `StateView` is the read-interface both satisfy.
This is the only change the engine asks of existing infrastructure.

### 3.2 ActionCatalog

An **action** is the atomic unit of planning:

```
Action {
  id            // stable: "train:Mining:mlm", "quest:Song of the Elves", ...
  kind          // TRAIN | QUEST | OBTAIN | KILL | MINIGAME | BUY | MANUAL | RECURRING
  requires      // requirement string (existing graph parse() form)
  effects       // state deltas: xp gained, quest done, unlocks set, items +/-
  duration      // model, not scalar — see §5
  lane          // ACTIVE (consumes plan hours) | BACKGROUND (dailies/farming — §7)
  tags          // afk / intensive / dangerous / daily-limited / member-of-ladder
  why           // one-line human rationale template
  source        // provenance: wiki page / pack id (mandatory, like all packs)
}
```

Actions come from four places:

1. **Method ladders** (`methods.json`, new — Appendix A is its seed
   content): per skill, the ironman-viable training methods with rates,
   requirement strings, and consumed/produced items. TRAIN actions are
   *generated* from ladders at plan time ("train Fishing to 81 using best
   unlocked methods"), not enumerated statically.
2. **Existing packs, reinterpreted**: gear-progression entries (OBTAIN),
   goals.json steps, qol.json unlocks, dailies.json (RECURRING),
   banked-xp.json (a *discount* on TRAIN, not an action).
3. **Quest actions** (`quests.json`, new): one action per quest with its
   full requirement expression and time cost. Requirements adopted from
   Quest Helper at its hub-pinned commit (the proven technique from the
   diaries rebuild); times from the wiki's official length ratings
   calibrated against the Optimal Quest Guide (Appendix B §3).
4. **Obtainment resolvers** (`sources.json`, new): item id → the ways an
   ironman gets it (drop: source + rate + kill time; craft: inputs +
   skill; shop; minigame points; grow). This is what lets the engine
   expand `item:` leaves instead of shrugging (§4).

### 3.3 GoalCompiler

Reuses what exists: every selected goal — pack goal, `gear:` / `ca:` /
`diary:` synthetic — already compiles to requirement strings
(GoalPlannerModule.compile / toGoal / toCaGoal / toDiaryGoal). The compiler
just collects every unmet leaf across every selected goal into a single
demand set, tagged with which goals want it. Nothing new is invented here;
the engine is a consumer of the requirement graph exactly as DESIGN.md
§2.2 prescribes.

### 3.4 DependencyExpander

Turns the demand set into a **merged action DAG**:

- `skill:`/`skillb:` leaf → TRAIN action to that level (`skillb:` may
  instead be satisfied by a boost — consult Boosts.available, cost ≈ 0;
  the boost path is offered only when the gap is within known headroom,
  same semantics as isMetWithBoosts today).
- `quest:` leaf → QUEST action; recursively expand *its* requirements
  from quests.json (chain quests pull their whole subtree in).
- `item:`/`itemx:` leaf → cheapest OBTAIN path from sources.json,
  recursively expanding inputs (a mith grapple wants Smithing 59 +
  Fletching 59 + a bar... — expansion bottoms out at TRAIN/KILL/BUY).
- `kc:` leaf → KILL action (rate from loot-module measurements when the
  player has history on that boss, pack default otherwise).
- `unlock:` leaf → mapped action if the catalog has one (diary task,
  minigame grind), MANUAL step otherwise — surfaced in the plan as a
  step the player ticks, never silently dropped.
- **Deduplication is the point**: identical/subsumed nodes merge (the DAG
  wants 70 Agility once, tagged with all three goals that need it; a
  quest appears once). Skill demands merge to the *maximum* level asked.
- Cycle guard: the OSRS content graph is acyclic in practice; a cycle in
  data is a pack bug — expansion fails loudly in CI, tolerantly at
  runtime (drops the edge, flags the plan "degraded").

Output: `ActionDag` — nodes with `neededBy` goal tags, hard edges
(requirements), and **soft edges** (discounts, from §3.5's modifier table)
annotated with estimated hours saved.

### 3.5 CostModel — see §5.

### 3.6 Router — see §6.

### 3.7 Plan + Explainer

The plan is a list of **PlanStep**s, each: action, start-state summary,
duration (expected + spread for stochastic steps), the goals it serves,
the "why now" line (template instantiated with real numbers: "before
Slayer: unlocks fairy rings, saving ~4h across 6 later steps"), and its
alternatives ("or: boost with a stew, or: train via X — 40m slower but
afk"). The plan object is immutable and versioned; UI diffing between
plan versions is what makes live replanning non-jarring ("step 3 got
cheaper because you finished a diary" rather than a silently reshuffled
list).

## 4. What the engine refuses to guess

Honesty boundaries, fixed at design time:

- **No invented rates.** A method without a sourced rate doesn't get an
  invented one; it enters the catalog as duration-unknown, sorts last
  among alternatives, and the plan shows "time unknown" rather than a
  fabricated number. (Same rule as DOMAIN-NOTES detection limits: never
  fake precision.)
- **No plan for unexpandable goals.** A goal whose leaves can't expand
  (manual steps, unmapped unlocks) still appears in the plan — as a
  manual step at the earliest feasible position — but the engine never
  pretends to know its cost.
- **RNG communicated, not hidden.** Drop-gated steps show expected time
  *and* an "unlucky" bound (P90, from the geometric distribution — e.g.
  "~25h expected, ~57h if unlucky"). Aggregate plan totals show both sums.
- **The player outranks the engine.** Pin a step to the front, ban a
  method ("never suggest 3-tick anything"), mark a preference (afk over
  intensive at a rate discount the player chooses) — all persisted,
  all respected as hard constraints. The engine optimizes *within* the
  player's taste, never argues with it.

## 5. Cost model

`duration(action, projectedState) → hours` (expected; plus spread where
stochastic).

- **TRAIN**: piecewise integration over the xp range, adopting Wise Old
  Man's community-standard rate schema (`{startExp, rate}` thresholds
  per method — Appendix C §1): for each band, pick the best
  *unlocked-in-projection* method from the ladder (respecting player
  bans/preferences), divide band xp by its rate. Two WOM ideas carry
  over wholesale: **bonus xp edges** (`{originSkill, bonusSkill, ratio}`
  — barbarian fishing pays Agility/Strength, GotR pays Crafting/Mining,
  naguas pay RC/Prayer; byproduct xp shrinks *other* TRAIN nodes and the
  engine must credit it or it will over-plan), and rates doubling as a
  **sanity envelope** for measured data (§8). Deductions, in order:
  banked xp (existing banked-xp machinery), bonus-xp inflow from other
  planned actions, recurring-lane forecast (§7 — birdhouses will
  passively deliver N Hunter xp/week; the router may choose to *wait*
  rather than grind), and boosts for `skillb:` terminals. The one thing
  WOM deliberately doesn't model — prerequisites baked invisibly into
  rates — is exactly what our requirement strings add per method.
- **QUEST**: pack time (wiki length rating calibrated to Optimal Quest
  Guide hour data) × travel factor (below). Quest xp rewards are
  *effects* — a quest that awards 13,750 Agility xp genuinely shortens a
  TRAIN node, which is why quest-first routing falls out of the model
  instead of being hardcoded.
- **KILL/OBTAIN(drop)**: expected kills = 1/p (communicated with P90 ≈
  2.3/p); hours = kills ÷ kills-per-hour. Kills-per-hour: measured (loot
  module) ≫ pack default; gear-sensitive defaults keyed to gear-chart
  phase so upgrading gear visibly discounts later bossing steps.
- **Travel factor**: a single global multiplier on quest/errand-shaped
  actions, stepped down by unlock flags (fairy rings, spirit trees, POH
  jewellery box/portal tiers, diary teleports, spellbooks). Coarse by
  design — per-route pathing is Shortest Path's job, not ours; the factor
  exists so unlock actions can claim their real downstream value.
- **Discount table** (`effects.json`): unlock flag → {travel tier change,
  per-method rate multipliers, methods enabled}. This is the data behind
  soft edges; every entry carries provenance and the router treats its
  hours-saved claims as estimates, recomputed against the actual DAG
  (an unlock only "saves 4h" if 4h of discountable steps are actually in
  this player's plan).

## 6. Router

The router's core asset is not the search — it is the **deterministic
forward simulator**: applying a candidate ordering to ProjectedState and
integrating costs is closed-form per segment and cheap enough to call
thousands of times per replan. Every pass below is "propose orderings,
score them with the simulator"; this is the same architecture shipped
route-optimizers converge on (Appendix C §3). Four passes, all
deterministic:

1. **Closure & merge** (exact): expand + dedupe (§3.4). Compute the
   critical path per goal (longest chain of hard edges) — this alone
   yields per-goal minimum times and is independently displayable.
2. **Scoring** (heuristic): every currently-feasible action gets
   `value density = (direct goal progress + Σ realized downstream
   discounts + unlock breadth) / duration`. Downstream discounts are
   computed against the actual DAG (see §5). Unlock breadth is a small
   tiebreak toward force-multipliers early (Appendix B §2's empirical
   rule: front-load multipliers) — small because the discount term
   already captures most of it honestly. A second tiebreak is the
   **route prior**: the community's curated ironman order (the wiki
   Optimal Quest Guide/Ironman sequence, BRUHsailer's chapters —
   Appendix B §3) ships as data, and among near-equal candidates the
   engine follows it. The effect: plans deviate from the consensus route
   only where this account's actual state justifies it — which is also
   exactly the deviation the explainer can defend with numbers.
3. **Ordering** (greedy + lookahead): repeatedly take the highest-density
   feasible action; before committing each pick, a bounded lookahead
   (beam width ~4, depth ~3, on cheap surrogate costs) checks whether a
   currently-cheaper permutation beats it — this catches the classic
   greedy trap (taking a long grind before the unlock that would halve
   it). Ties break lexicographically → determinism.
4. **Local improvement** (optional, budgeted): insertion/2-opt moves on
   the linearized plan under a fixed iteration budget, accepting only
   feasibility-preserving, time-reducing moves (the speedrun-routing
   standard — Appendix C §3). Off by default until profiling shows pass
   3 leaving real time on the table; the pass exists in the design so
   adding it never changes architecture.
5. **Passive-timer pass** (exact, CPM-style): once the active order is
   fixed, background-lane chains (§7) are scheduled to *start* as early
   as their prerequisites allow — the critical path exposes which active
   steps gate a passive chain, and those steps inherit the chain's
   forecast value in their why-lines.

Performance budget: full replan < 100 ms for 10 goals / ~300-node DAG on
the SwingWorker pool (plans are small — the catalog is hundreds of
actions, not millions of states; we search over *orderings of a merged
DAG*, never raw state space). Replans are debounced (2s) and memoized on
(state fingerprint, goal set, pack versions).

## 7. Background lane

Recurring actions (farming runs, birdhouses, dailies, kingdom, seaweed,
long-fuse unlocks like Tithe points) don't compete for the same hours —
they overlay any plan at a few minutes per cycle. The router schedules
them as a parallel lane with only two couplings to the main lane:

- Their *forecast yield* discounts TRAIN nodes (§5): the engine can plan
  "Farming to 65 arrives passively in ~3 weeks of runs; don't grind it".
- Their *setup actions* (unlock birdhouses, plant first trees) are main-
  lane actions whose value density includes the lane's forecast yield —
  which is why "set up passive income early" emerges from arithmetic
  rather than dogma.

The existing dailies/farming modules already track cycle state; the lane
reads them, it doesn't reimplement them.

## 8. Personalization

Priors from packs, posteriors from play:

- **Session xp rates**: AccountState already sees every xp drop.
  Attributing xp windows to catalog methods precisely is fragile, so v1
  keeps it coarse: per (skill, plan-step) measured effective rate while
  that step is active, shrunk toward the pack prior by sample size
  (simple precision weighting: `rate = (n·measured + k·prior)/(n + k)`,
  k ≈ 2 hours of prior mass). Pack rates double as the **sanity
  envelope** (WOM's `withinRange` precedent, Appendix C §1): measured
  rates are capped near the table's max-efficiency rate, so one
  mislabeled lucky session can't convince the planner the player fishes
  at 400k/hr. Notably, no existing tool blends priors with measured
  rates at all (Appendix C §4) — this is a differentiator, and low-risk
  because the prior dominates until real evidence accumulates.
- **Kills/hr and drop luck**: loot module supplies both; a player's
  actual Zulrah pace beats any pack number after a handful of trips. Drop
  *rates* are never personalized (that's gambler's fallacy) — only pace.
- **Taste**: afk-preference slider, method bans, pinned steps (§4).
  Persisted profile-scoped like everything else.

## 9. Failure modes & degradation

- **Pack gaps** → duration-unknown steps, plan flagged, never blocked.
- **Stale meta** → provenance dates on every rate; a pack older than N
  months renders a staleness note in the plan footer (same philosophy as
  ca-completion's regeneration cadence).
- **Detection limits** → leaves the client can't verify (DOMAIN-NOTES
  §detection-limits) compile to manual steps with tick-boxes; the plan
  treats a ticked manual step as truth (player outranks engine).
- **Contradictory state** (e.g. diary ironman-alternate flags) → the
  engine consumes AccountState's already-corrected views; it never reads
  raw varps itself. One decode, one owner — the M-diaries lesson.

## 10. UI surfacing (hooks only — UI design is a later phase)

- **What Now becomes a view over the plan head**: its impact×urgency×fit
  scoring survives for background-lane and time-boxed suggestions, but
  the top "do next" card is plan step 1 — the two engines stop disagreeing
  because one feeds the other.
- **Goal detail** shows that goal's slice of the merged plan (its route,
  its share of shared steps, its critical path time).
- **Dashboard** gets the plan horizon: total expected hours to selected
  goals, next milestone, "what changed since yesterday".
- **Plan view** (new tab, later milestone): the ordered steps with why
  lines, alternatives, pin/ban affordances, and the background lane.

## 11. Data pipeline

New packs (all schema-validated in CI, generated with provenance, never
hand-edited where a generator exists — house rules):

| pack | content | source | generator |
|---|---|---|---|
| methods.json | per-skill method ladders: WOM-shape `{startExp, rate}` tiers + bonus-xp edges, requirement strings, consumes/produces, style tags | **rates**: WOM's open-source ironman EHP configs (`server/.../configs/ehp/ironman.ehp.ts` — verified present, directly consumable) at a pinned commit; **per-action materials/xp**: wiki `Module:Skill calc/<Skill>` Lua data (fetchable raw, trivially parseable); **ironman feasibility + requirement overlay**: `Ironman_Guide/<skill>` (Appendix A §3) | tools/gen_methods.py joins the three; requirement strings are the hand-curated layer WOM deliberately lacks — every entry carries provenance |
| quests.json | per-quest requirement expression, xp rewards, time cost, **route-prior index** | requirements from Quest Helper's pinned commit (proven technique); xp rewards + ironman order from the wiki `Optimal_quest_guide/Ironman` (rows carry `data-rowid` + `{{Optimal quest}}` templates — parseable, Appendix B §3); QH's own `IronmanOptimalQuestGuide` list corroborates the order | tools/gen_quests.py |
| sources.json | item id → obtainment paths (drop/craft/shop/minigame/grow) with rates | wiki drop tables (parseable), existing gear pack audit tooling | tools/gen_sources.py, seeded from gear-progression's audited 180 items — full-economy coverage is explicitly NOT a goal; the pack covers goal-reachable items only, growing on demand |
| effects.json | unlock → discounts (travel tier, rate multipliers, methods enabled) | curated from Appendix B §2's force-multiplier research; small (~60 entries) and stable | hand-curated + schema |
| ehp-calibration.json | milestone hour checkpoints (Barrows gloves, BRUHsailer endpoint, base 70s…) with confidence labels | WOM rate-config integration + community anchors (Appendix B §6); BRUHsailer per-step stat spreadsheets for state-at-step fixtures | tools/gen_ehp.py — used only by tests (§12), never by the router |

Also encoded as data, not code: the Slayer block/skip table (wiki
`Ironman_Guide/Slayer` Task Summary — the deferred M5 block/skip advisor
falls out of the same pack), and the route prior (above). Kourend favor
no longer exists (removed Jan 2024) — no favor concept anywhere in the
model.

Maintenance model: regenerate on meta shifts; CI schema + referential
checks (every requirement string parses non-manual unless whitelisted;
every sources.json input item resolvable; effects keys are real unlock
flags; DAG acyclicity over the whole catalog).

## 12. Validation

- **Feasibility property test** (the invariant): for random goal subsets
  and random plausible account fixtures, every plan is executable in
  order — each action's requirements met by its predecessors' effects.
- **Golden scenarios**: fixed fixtures with locked expected plans —
  fresh account → early route matches Appendix B's consensus shape
  (quest-first, multipliers front-loaded); mid-game account → no
  regression to already-done content; the worked example in Appendix D.
- **Calibration tests**: engine's hour totals for standard milestones
  within a tolerance band (±25%) of ehp-calibration checkpoints — not to
  chase EHP-exactness, but to catch order-of-magnitude drift when packs
  change.
- **Determinism test**: plan(fixture) byte-identical across runs and
  across dedupe-order permutations.
- **Performance test**: replan under budget on the largest golden fixture.

## 13. Implementation roadmap (post-design; each slice ships tested)

- **E1 — StateView + ProjectedState + cost model core**: requirement
  graph evaluates against projections; TRAIN integration with banked
  xp/boosts; quest/kill costing. Exit: costed single-goal estimates match
  hand-computed fixtures.
- **E2 — packs v1**: quests.json (QH adoption) + methods.json seed (top
  2–3 methods/skill from Appendix A) + schemas + CI checks. Exit:
  referential test suite green; every goals.json/gear leaf expands.
- **E3 — expander + merged DAG**: dedupe, cycle guard, per-goal critical
  path. Exit: feasibility property test green; Bowfa+gloves+base-70s DAG
  merges shared nodes (Appendix D trace).
- **E4 — router v1**: scoring + greedy + lookahead; effects.json;
  background lane forecast. Exit: golden scenarios + determinism +
  calibration band.
- **E5 — personalization + UI integration**: measured-rate blend; What
  Now feeds from plan head; goal-detail route slice; plan tab MVP. Exit:
  in-client validation round with Luke.

Dependencies: E1→E3→E4 strict; E2 parallel to E1; E5 last. The existing
GoalPlannerModule/WhatNow stay untouched until E5 — the engine grows
beside them, swap is the last step (strangler pattern, same as the gear
and CA rebuilds).

---

## Appendix A — 2026 training-method meta digest (research)

Compiled July 2026 from the OSRS Wiki `Ironman_Guide/<Skill>` pages (all
23 exist, including `/Sailing` and `/Melee`) plus update-news
corroboration. Every row is a candidate methods.json seed entry. Rates
are wiki-cited approximations — **the generator must re-verify each
number against raw wikitext before pack encoding** (research caveat,
kept deliberately).

### A.1 Context: what moved in 2024–2026

The engine's data model must treat these as normal pack updates, which is
the argument for data-over-code (tenet 4):

- **Sailing released 19 Nov 2025** (23rd skill, unlocked via the
  Pandemonium quest). New training paths (Barracuda Trials 114–200k+
  xp/hr at 72+, courier routes, salvaging), new gates the engine must
  know (frost dragon bones behind 87 Sailing, ironwood WC behind 72),
  and new *demand sinks* — shipbuilding consumes planks/nails (25/74
  Smithing for lead/cupronickel bars), so Sailing goals back-propagate
  Construction/Smithing demand through the expander exactly like any
  other requirement.
- **Varlamore (3 parts, Mar 2024 → Jul 2025)** rewired several metas:
  gemstone crabs (all-styles semi-AFK combat from level 1, quest-gated
  only), sulphur naguas (AFK melee meta with passive RC+Prayer xp),
  blessed bone shards + libation bowl (safe drip-fed Prayer economy
  replacing wildy-altar-or-nothing), Vale Totems (outright Fletching
  meta, zero gp), Mastering Mixology (Herblore xp without burning herb
  stock), Hunter rumours, calcified rocks (AFK Mining + Prayer shards),
  Varlamore gem stalls (Thieving meta that feeds Crafting).
- **Bosses**: Araxxor (Aug 2024, 92 Slayer), Royal Titans (early 2025,
  entry duo), Yama (spring 2025, endgame duo — Oathplate devalues the
  Bandos path in gear ladders), Doom of Mokhaiotl (Jul 2025).
- **2026 QoL that changes routing**: Hallowed Sepulchre floor
  requirements lowered (F4 82→77, F5 92→87, boostable) — Sepulchre meta
  starts ~5 levels earlier; Colossal Wyrm course shortcuts; Sailing
  courier loot sacks (Jul 2026).

Meta half-life is ~6–12 months. Pack staleness notes (§9) are not
paranoia.

### A.2 Per-skill method ladders (methods.json seed)

Format: method | levels | ~xp/hr | key prereqs | style. Only the
ladder-defining entries; the full pack carries 2–4 per skill per band.

**Melee** — quest xp to ~30 (Waterfall/Fight Arena); gemstone crabs
1–60 (semi-AFK, Children of the Sun only, crab moves every 10 min —
displaced sand crabs in 2025); Slayer as the vehicle 40+; NMZ 70+
(70–85k, very AFK); **sulphur naguas 70+ (~140k w/ Piety, fully AFK, no
supplies, passive RC+Prayer — current AFK meta)**; vyrewatch sentinels
70+ (54k + ~130k gp/hr alchs, needs Sins of the Father).

**Ranged** — dorgeshuun c'bow on gemstone crabs 28–55 (Lost Tribe);
chinchompas 55–99 (100–300k scaling; needs 63/73 Hunter self-catch,
Hard Western/SotE for best spots); eclipse atlatl 75+ (str-scaling,
Moons of Peril grind).

**Magic** — gemstone-crab autocast/splash 1–43 (10-min AFK); Superheat/
MTA/alch 43–68; burst-barrage slayer 70+ (75–95k + slayer xp, DT1 +
rune supply from RC); Arceuus library any (15×level/hr); Plank Make 86+
(~90k, semi-AFK, pairs under other activities).

**Prayer** — **blessed bone shards/libation bowl 30+ (399–600% per
bone-equivalent, drip-fed from calcified rocks/Colossal Wyrm/rumours/
Moons — the safe ironman path, NEW 2024-25)**; chaos altar dragon bones
(~500k burst, wildy risk — player-taste gate §4); ensouled heads;
frost dragon bones late (87 Sailing + eternal brazier).

**Runecraft** — GotR 10–99 (25–70k, Temple of the Eye; feeds Crafting/
Mining); Arceuus library books 1–77; blood/soul 77/90+ (37–45k,
semi-AFK, self-supplies barrage runes); wrath 95+ (DS2); naguas trickle.

**Construction** — oak larders 16–52; Mahogany Homes 20+ (40–70k, cheap
— the iron-friendly default); teak/mahog benches + demon butler 52+
(300–900k, plank-logistics-bound: Plank Make ~7k/hr, **Auburnvale
sawmill up to 8k/hr w/ plank sack (2025)**, Miscellania, Forestry
vouchers). Sailing shipbuilding is a Construction sink to 87.

**Agility** — rooftop ladder 1–99 fallback (40–62k); Colossal Wyrm
50/62+ (31–43k, lower intensity, drops bone shards ≈ +1.7–2k Prayer
xp/hr banked); **Hallowed Sepulchre 72–99 (72–85k+, floors 77/87 since
2026 — intense, the meta)**; Prifddinas 75+ (59–66k + ~45 crystal
shards/hr — shard demand couples to Bowfa/CG goals).

**Herblore** — quests to 26; potion-making bounded by herb inflow (runs,
Miscellania, seed drops — effectively daily-limited, background lane);
**Mastering Mixology 60+ (xp without consuming ranarr/snapdragon stock —
NEW Nov 2024, changes the "hoard herbs" calculus)**; herbiboar 80 Hunter.

**Thieving** — fruit stalls 25–45; blackjacking 45+ (60–250k, extremely
intensive — the taste slider's poster child); Ardy knights 55+
(60–100k, low intensity); **Varlamore house robbing 50+ (very low
intensity + passive prayer xp, 2024)**; **Varlamore gem stalls 75+
(150–170k + gem supply for Crafting, 2024)**; Pyramid Plunder 81+.

**Crafting** — quests to ~35; glassblowing w/ Superglass Make + giant
seaweed (65–90k, semi-AFK, Lunar); gem cutting fed by gem stalls (2024);
battlestaves 54+; Dorgesh-Kaan lamps 87+ (needs Death to the
Dorgeshuun).

**Fletching** — headless arrows/darts early; broad arrows 52+ (~247k,
300 slayer points); **Vale Totems any level (oak ~36k → magic ~420k,
zero gp, own logs — outright meta since Oct 2024)**; Wintertodt kindling
passive.

**Slayer** — Turael-boost points → Konar milestones → Duradel; block/
skip list is data (wiki Task Summary table: monster | weight | do/skip/
block | ~xp/hr — the single best structured ironman dataset on the
wiki); unlock ladder 58 cave horrors (black mask) → 75 gargoyles → 85
abyssal demons → 87 kraken → 95 hydra; barrage tasks 75–95k.

**Hunter** — **birdhouses 5+ (daily-limited passive backbone, Bone
Voyage)**; **Hunter rumours 46+ (loot sacks: herbs/bones/food — default
active method since 2024)**; salamanders 60+; chins 63/73+ (dual-purpose
ranged ammo); herbiboar 80+ (115–130k + herbs).

**Mining** — iron 15–45; MLM 30+ (semi-AFK, nuggets→prospector/bags,
coal+gold for BF); **calcified rocks 41+ (~35–55k very-AFK + prayer
shards, 2024)**; 3-tick granite 45+ (70–120k+, extremely intensive);
Volcanic Mine 70+; shooting stars (extreme AFK).

**Smithing** — quest lump to ~37; **Giants' Foundry 15+ (56–300k
scaling, cheap — the ironman default)**; Blast Furnace gold 40+
(230–300k, needs goldsmith gauntlets/Family Crest + gp); cannonballs
(AFK, feeds Slayer, double mould from Foundry); Sailing bar sinks
(lead 25, cupronickel 74).

**Fishing** — fly fishing 20–58; **barbarian fishing 58+ (65–72k, or
104–115k 3-tick; passive Agi/Str xp)**; Tempoross 35+ (barrel/outfit);
karambwans 65+ (banks ~150k/hr Cooking); minnows 82+ (shark stock);
Deep Sea Trawling (Sailing-gated, being buffed 2026).

**Cooking** — trout/salmon alongside fishing; Hosidius kitchen +
gauntlets standard (Family Crest, Kourend easy); karambwans (to ~150k);
mess-hall pineapple pizzas 65+ (150–200k, zero supplies — iron meta);
jugs of wine 68+ (grapes from Zulrah/Vorkath/Farming).

**Firemaking** — burn ladder to 50; **Wintertodt 50–99 (150–300k
scaling; 2024 rework scales loot to own levels — still the early-game
ironman engine: nests/seeds/ores/fish)**.

**Woodcutting** — teaks 1.5t/2t 35+ (100–200k, Fossil Island + Civitas
2024 spot); sulliusceps 65+ (100–110k + prayer trickle); **ironwood 80+
(needs 72 Sailing, Nov 2025)**; redwoods 90+ (very AFK); Forestry bark →
sawmill vouchers (Construction coupling).

**Farming** — herb/tree runs (time-gated background lane; Civitas +
Auburnvale patches added 2024–25); Tithe 34+ (seed box/herb sack/
auto-weed); giant seaweed (passive, feeds Crafting); contracts 45+/
Hespori 65+ (seed engine).

**Sailing** — charting/couriers 1–30 (~10k, post-Pandemonium);
**Barracuda Trials 30/55/72 (19–25k → 65–89k → 114–184k, 200k+ w/
crystal extractor; gated on ship upgrades: mithril → adamant keel needs
Regicide)**; courier routes 46+ (low intensity, 30k→145k, loot sacks
since Jul 2026); salvaging 15+ (most AFK, 80–95k at 87 on merchant
wrecks).

### A.3 Machine-readable rate sources (feeds §11's generators)

- `Ironman_Guide/<Skill>` ×23: prose with recurring "Levels X–Y:" section
  headings + consistently structured quest-xp tables. Moderate parse
  difficulty → per-skill adapter, not one generic parser (format drifts
  per page).
- `Sailing_training`: cleanest tables on the wiki (trial | xp/trial |
  xp/hr per rank; salvage xp by wreck tier × hook metal). Good first
  generator target.
- `Ironman_Guide/Slayer` Task Summary table (monster | level | weight |
  do/skip/block | ~xp/hr): the block/skip advisor AND slayer costing in
  one parseable table.
- `Pay-to-play_<Skill>_training`: densest xp/hr tables but not
  ironman-filtered — use for *rates*, joined against ironman-guide
  method lists for *feasibility*. This join is the core of
  gen_methods.py.
- Fetch via MediaWiki API `action=parse` wikitext (descriptive
  User-Agent, as with all existing generators).

## Appendix B — Progression arc & force multipliers (research)

Compiled July 2026 from the wiki Ironman guide, `Optimal_quest_guide/
Ironman`, `Guide:BRUHsailer`, ironman.guide, ironmanmeta.com, and
ladlorchart.com.

### B.1 Phase structure (the consensus arc)

- **Phase A — quest rush / foundations (~0–100h)**: optimal-quest-order
  questing (quest xp substitutes for slow early training), Wintertodt
  (FM 50→90 + supply crates as seed capital), fairy rings, graceful,
  43 Prayer, Miscellania setup. **Boundary: Barrows gloves** — the
  universal early/mid divider.
- **Phase B — mid game (~100–400h)**: base 70s (driven by SotE's 8×70
  requirement set — the single most route-defining requirement block in
  the game), quest cape, hard diaries, Slayer ladder to 87–91, zenytes,
  Moons of Peril armour. **Boundary: Song of the Elves → Corrupted
  Gauntlet → Bowfa + crystal** — community consensus calls this the
  largest single upgrade an ironman gets ("everything after this is
  20–30% faster" — an *effects.json entry wearing a quote*). BRUHsailer,
  the de-facto efficient route, ends exactly here.
- **Phase C — late game (400h+)**: GWD, ToA→CoX→ToB (ToA-first is the
  settled 2026 raid order), DT2 rings, high slayer bosses, Nex/Torva and
  the 2025–26 additions (Yama, Doom of Mokhaiotl, Fortis Colosseum,
  Maggot King), Inferno terminal.
- **Completionist**: 4,000–8,000h community figure (low confidence).

The engine never hardcodes phases — they emerge from the requirement
graph + discounts — but golden-scenario tests (§12) assert plans
*reproduce this shape* from a fresh-account fixture, because deviating
from an arc this settled without a state-specific reason is a bug.

### B.2 Force multipliers (effects.json seed)

The unlocks optimal routes front-load, with what they discount (each row
becomes an effects.json entry; costs are the router's to compute, these
are the researched priors): graceful (~4–6h, all-travel/run regen);
fairy rings (~1–2h via Fairytale I + start of II — the single best
discount-per-hour in the game); spirit trees/glider (<2h, pre-ring
travel); Ardougne cloak 1–2 (farm-run teleport); Wintertodt (supply seed
capital, 20–40h banked early); Fossil Island (~2h → birdhouses, giant
seaweed, hardwood); 43 Prayer (gates all early PvM; Varlamore bone
shards now the safe path); Miscellania (~2h + daily upkeep → passive
herbs/teaks/nests forever); POH core (pool 90 / jewellery box 81–91 /
nexus 72+ via Mahogany Homes + crystal saw/stew boosts); spellbooks —
Ancients (DT1, burst slayer), Lunar (fertile soil, spellbook swap),
Arceuus (free, thralls after SotN); Barrows gloves (RFD forces broad
account development — the chain *is* the early game); DS2 (Vorkath =
sustain gp/supplies/bones); SotE (Prifddinas: CG/Bowfa, shards,
Zalcano); select diaries (Varrock med battlestaves gp, Ardy med thieving,
Morytania bonecrusher, Western hard); Royal Titans prayers (Jan 2025,
Dead Eye/Mystic Vigor — the new pre-CG DPS step).

### B.3 Quest backbone & route parseability

The wiki `Optimal_quest_guide/Ironman` is a linear table interleaving
quests with training steps; philosophy: front-load transport, dump quest
lamps into Herblore ("generally the slowest skill"), avoid raw early
combat. **Machine-parseable**: each row carries `data-rowid="<quest>"`,
xp rewards as `{{Optimal quest|skill=xp}}` templates, training
interleaves as `{{Optimal quest/train|skill|level}}` — same extraction
class as the diary tables we already parse. **BRUHsailer** (wiki-hosted,
updated through 2025–26) is the step-level consensus route (575+ steps in
the ironman.guide port) and publishes per-step stat/gp spreadsheets —
calibration gold for §12's fixtures (known account state at each step of
a known route). Milestone spine: travel quests → Lost City/Fairytales →
MM1/DT1 → RFD → MM2 → DS2 (200 QP) → SotE → quest cape → 2024–26 quests
(While Guthix Sleeps, Varlamore chain, Sailing's Tortugan quests).

### B.4 Bossing ladder (2026)

Pre-Bowfa: Barrows → Dagannoth Rex → Moons of Peril (2024 — Barrows-tier
gear, no repair costs, reshaped the mid game) → Royal Titans → Fight
Caves. **Bowfa checkpoint** (CG immediately post-SotE). Post-Bowfa:
Zulrah → Vorkath → demonic gorillas/zenytes → Muspah → Kraken → GWD
(Yama's 2025 Oathplate makes the Bandos grind skippable — gear-pack
`implies`-style reordering, live proof the ladder is data). Raids:
ToA → CoX → ToB. Then DT2 bosses (Vardorvis first), high slayer
(Cerberus 91 → Thermy 93 → Hydra 95 → Araxxor 92), endgame additions.

### B.5 Daily/passive layer (the background lane's content)

Herb runs (~80 min cycle, ~5 min each — highest-value habit); birdhouse
runs (~50 min cycle, ~2 min); Miscellania (herbs+teaks, weekly approval
upkeep); farm cycles (trees/fruit/hardwood/seaweed — seaweed feeds
Crafting); Zaff battlestaves (gp); Hespori (~32h); Tears of Guthix
(weekly). NMZ herb boxes are mains-only — excluded. No rigorous gp/day
figure exists for irons; the lane's value is modeled in
gathering-hours-saved, not gp.

### B.6 Time anchors (confidence-labeled, for ehp-calibration.json)

- Barrows gloves territory: **~50–100h** (medium confidence).
- BRUHsailer endpoint (quest cape + hard diaries + Bowfa/crystal):
  **~300–500h** (low confidence — the route publishes stats, not hours).
- Milestone hours are otherwise *derived*, not published: integrate WOM
  ironman rate configs over the milestone's stat targets. That
  derivation is gen_ehp.py.

## Appendix C — Prior art survey (research)

### C.1 Rate/time frameworks

- **Wise Old Man**: open-source ironman-specific EHP/EHB configs
  (github: `wise-old-man/wise-old-man`, `server/.../ehp/ironman.ehp.ts`,
  verified present; also a REST endpoint). Model: per skill,
  `methods[]` of `{startExp, rate, description}` — piecewise-constant
  rate over xp, method switching purely by threshold; cross-skill
  byproduct xp as `bonuses[]` `{originSkill, bonusSkill, ratio}`; rates
  double as anti-cheat sanity caps (`withinRange`). **Limitation**:
  prerequisites are baked into rates invisibly (the tables assume the
  efficient meta account). Adopted (§5): the schema and the bonus-xp
  concept; added: our requirement overlay.
- **TempleOSRS**: competing ironman EHP tables; rates are *negotiated
  social consensus*, revised several times a year with top players —
  the strongest argument for pack provenance/version stamps and a
  staleness note (§9). Weakly machine-readable (HTML, Cloudflare).
- **Crystal Math Labs** (legacy): its supplies calculator shows
  materials-per-goal falls out of a method table for free — planner and
  supplies module should share one model.
- **Wiki `Module:Skill calc/<Skill>`**: Lua data modules (raw-fetchable)
  with per-action `{name, level, xp, materials[], boostablexp}` — the
  canonical per-action layer. Wiki calculators count actions but never
  model hours; EHP tables model hours but hide actions. **The engine
  needs both, joined by method — that join is methods.json.**

### C.2 Planners & guides (the first-of-kind evidence)

- **Quest Helper**: within-quest sequencing is a live var-driven
  conditional-step machine (structurally near-identical to our
  requirement graph); **cross-quest order is a hardcoded curated list**
  (`IronmanOptimalQuestGuide.getQuestList()` mirroring the wiki OQG).
  Even the flagship quest tool ships expert order, not a solver.
- **ironman.guide** (OzirisLoL lineage), **BRUHsailer**: frozen expert
  routes with progress sync — "checklist, not a script". **AscendOSRS /
  OSRS Toolkit / Ladlor chart forks**: static gear ladders + checkboxes.
  **OSRS Genie etc.**: marginal DPS comparison, no sequencing, no time.
- Conclusion: nobody computes routes; nobody attaches hours to gear
  goals; nobody blends measured rates with priors. The pragmatic hybrid
  the engine ships — *expert route as prior, computed re-ordering where
  the account's state justifies it* (§6) — keeps the community's trust
  anchor while adding the three unclaimed capabilities.
- **exile-leveling** (Path of Exile) is the UX precedent worth copying:
  route-as-data + user reordering + tool revalidation (§4's
  player-outranks-engine, §10's plan view).

### C.3 Algorithm precedent

- Problem class: single-machine sequencing with sequence-dependent
  processing times (order matters only because of unlock discounts,
  passive-timer starts, and player urgency) — TSP-like, not P|prec|Cmax
  proper; NP-hard either way.
- What shipped route optimizers actually use: **deterministic forward
  simulator as the evaluation function + greedy seed + local search**
  (insertion/2-opt/simulated annealing). Speedrun communities route with
  exactly this (the Breath of the Wild all-korok route was a TSP-solver
  product); CPM passes handle parallel timers. §6 is this architecture.
- **Anti-precedent**: Factorio/Satisfactory calculators solve
  steady-state *ratio* problems with linear programming — LP does not
  transfer to ordering. At most it applies to "how much of supply X"
  subproblems, which we solve arithmetically anyway.
- Full STRIPS/HTN or A* over raw account-state space is overkill:
  feasibility is cheap graph resolution here; only duration ordering is
  hard. Beam search over "next unlock to pursue" is the escalation path
  if greedy+2-opt plateaus (§6 pass 3 already reserves the slot).

### C.4 Personalization precedent

Nobody blends: WOM prices measured gains in global rates (and uses rates
only as a validity envelope); RuneLite's xp tracker extrapolates pure
session rates with no prior; Temple/CML use global tables. The
shrinkage-blend in §8 (prior-weighted measured rates, envelope-capped)
is unclaimed territory with a graceful failure mode.

## Appendix D — Worked example

Fixture: a mid-game ironman (base ~60s, Barrows-tier melee, quest points
~180, fairy rings NOT unlocked, graceful owned). Selected goals: **Bow of
Faerdhinen** (`bowfa`, pack goal), **Barrows gloves** (`barrows_gloves`),
and a **base 70s** custom skill goal. Numbers marked ~ are illustrative;
real values come from packs at runtime.

**Compile + expand (layers 3–4).** The three goals produce a demand set
whose expansion merges hard:

- `bowfa` → `quest:Song of the Elves` → (Mourning's End I/II, Roving
  Elves, … chain) → leaf skills incl. **70 Agility** (boostable: summer
  pie −5 admissible), 70 Construction/Farming/Herblore/Hunter/Mining/
  Smithing/Woodcutting — *seven of which the base-70s goal also
  demands*: the expander tags one TRAIN node per skill with
  `neededBy: {bowfa, base70s}` instead of duplicating.
- `barrows_gloves` → `quest:Recipe for Disaster` → its ~10 subquest
  requirement closure → leaves incl. 70 Cooking (shared with base70s),
  Desert Treasure chain overlaps the SotE chain's Temple of the Eye—
  adjacent prerequisites (Troll Stronghold appears once, tagged twice).
- Fairy rings (`unlock:fairy_rings` via Fairytale II start) enter not as
  anyone's hard requirement but as a **soft edge**: effects.json says
  they step the travel tier down, and the DAG contains ~14 quest/errand
  actions still subject to travel factor → realized discount ≈ ~3–4h.

**Cost (layer 5).** The 70-Agility TRAIN node integrates rooftop bands
from 60 (graceful already owned so no detour), minus the ~13.7k Agility
xp the SotE chain's quest rewards deliver as effects — the router sees
quest-first ordering shave the grind without any hardcoded "quests
first" rule. Bowfa's terminal (enhanced seed at ~1/50 from the Corrupted
Gauntlet; the player has no CG kill history, so the pack-default ~3
completions/hr applies until the loot module measures better) is a
stochastic step: ~17h expected, ~39h P90, shown as both.

**Route (layer 6).** Value-density ordering front-loads: (1) Fairytale
II start (minutes of cost, ~3–4h realized discount → density off the
chart), (2) the shared RFD/SotE prerequisite quests in critical-path
order, (3) skill blocks scheduled against the background lane (Farming
70 arrives passively from tree runs already running → its TRAIN node
shrinks to the seed-buying errand), (4) CG grind last — nothing
downstream of it, worst spread, and every earlier gear/level step
improves measured kills/hr before the long grind starts.

**Explainer (layer 7) renders step 1 as:** *"Start Fairytale II (~15
min): unlocks fairy rings — saves ~3.5h across 14 later steps in this
plan. Needed by: travel (all goals)."* — every claim in that sentence is
a number the engine computed, not copy.
