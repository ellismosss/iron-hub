# Goals v2 — Implementation Plan (G2–G8)

Instructions for the implementing agent. Written 2026-07-18, when G0
(interface mockup, approved at round 2: "this is the right shape") and
G1 (GoalSeed unification, commit 3b311e3) had landed. Read in order:

1. **CLAUDE.md** — the house rules. Every one of them binds here,
   especially: "The Iron Hub way" recipe, the **"track this" seed
   recipe (rewritten by G1 — GoalSeeds factory, addGoalSeed, one map)**,
   the polish bar (green builds, render PNGs you actually LOOK at,
   honesty-is-a-feature, negative-path tests), and the standing gates.
2. **design/GOALS-V2.md** — the agreed design. §0.1 (Goals/Tasks/Routes
   vocabulary + benefits-first presentation), §1–§4 (architecture),
   §5.2 (Luke's locked decisions), §6 (the four-section surface).
3. **`GoalsHubMockup`** (modules/designlab) + renders
   `build/reports/goals-hub-{stone,mystic}.png` — the APPROVED
   interface. G7 builds this, live.
4. ENGINE-DESIGN.md §11 (data pipeline — G4 follows it) and
   design/PLANNER-UX.md §5 (feedback tone: no streaks, no confetti,
   honest numbers as the reward).

Ways of working (non-negotiable, all proven in this repo):
- One slice per commit, `./gradlew build` green before EVERY commit
  (capture gradle's `$?` directly, never after a pipe), push to origin
  main, commit body explains the why, ends
  `Co-Authored-By:` + the model credit line per CLAUDE.md.
- Luke does the in-client passes. Unit-prove everything headless;
  render PNGs from tests for every UI surface and inspect them before
  calling work done.
- Time copy: benefits lead, hours are one quiet stat ([[design-system]]
  memory + GOALS-V2 §0.1). A surface where every row's right edge is an
  hour count is the thing Luke rejected.
- **Approval checkpoints** (stop and present, do not proceed):
  G4's regenerated methods pack (standing gate: meta regenerations are
  NEVER landed without laying the changes out to Luke first), and G7's
  removal of the old planner views (build the new surface first, show
  renders, delete on his word — strangler pattern).

---

## G2 — Four new providers + the completion archive

**Providers** (each: one `GoalSeeds` factory method, one tab affordance,
proof wiring, tests; the G1 recipe in CLAUDE.md is the template):

- **PoH** — `poh:<tierId>`, family `"poh"`. Steps from the tier's
  poh.json reqs (`skillb:Construction:N` + gates), labels via
  `Requirements.parse(raw).describe()`, final step "Build <tier name>"
  with proof `unlock:pohtier_<tierId>`; achieved = that proof.
  PohModule marks the unlock when the tier is detected built or
  manually marked (and immediately at add time if already built —
  `state.getPohBuilt()`), never on goal removal. PohTab: a goal glyph
  per tier row in the expanded ladder (the diaries `+` grammar).
- **QoL** — `qol:<id>`, family `"qol"`. qol.json entries already carry
  graph reqs: steps = reqs, achieved = the SAME reqs (fully detectable
  — no unlock flag needed, exactly like custom skill goals). QolTab
  gains the goal glyph per unlock row.
- **Bank** — NO new family. A `+ Goal` beside the Bank skill view's
  target-level field creates the existing custom skill goal
  (`GoalSeeds.custom("custom:skill:<skill>:<lvl>", "<Skill> <lvl>",
  "skill:<Skill>:<lvl>")` — dedupe with PlannerTab's search which
  builds the identical id). Banked XP is already a cost DISCOUNT inside
  the engine; the provider only promotes the target the tab persists
  (`bankSkillTargets`).
- **Supplies** — `supply:<itemId>`, family `"supply"`. One-shot
  "stock N × item" (Luke's decision, §5.2 Q1): steps =
  `[item:<id>:<N>:<Name>]` (display name IN the leaf — raw ids render
  as garbage), achieved = same req; completes when bank+carried ≥ N
  (variation-aware for free via `item:`), re-addable after completion.
  RunwayTab affordance: `+ Goal` per supply row; quantity defaults to
  the runway's own suggested stock for that item (a StoneTextField to
  override — keep it one row, no dialog).

**Completion archive** (GOALS-V2 §1.4):

- `PersistedState.GoalRecord { String goalId, name, family; long
  addedAt; long completedAt; double estimatedHours; double
  hoursAtCompletion; }` + `List<GoalRecord> goalRecords` capped
  **200** (house pattern; `MAX_GOAL_RECORDS`), with `copy()`, wired
  into activateProfile + persistNow like every list.
- Capture `estimatedHours` at ADD time: `previewMerge(candidate)`
  already computes it — store `addedHours` on a new
  `GoalSeed.estimatedHours` field when the add flow ran a preview;
  0 = unknown (module `+` clicks don't run previews — leave 0, render
  "—", never invent).
- The completion detector lives in **GoalPlannerModule** (it already
  recomputes on every state change): keep a per-goal achieved
  fingerprint; on a SELECTED goal flipping false→true, append a record
  (completedAt = now, hoursAtCompletion = plan.knownHours) and retire
  the goal exactly as today. **Silent first sync**: a goal already
  achieved when first observed (pre-plugin feats, achieved-at-add)
  records `completedAt = 0` → renders "detected", never an invented
  date. **Goal removal NEVER writes a record** (the never-flash-Done
  rule). Negative-path tests for both, plus login-replay (a second
  observation of the same achieved goal must not duplicate the record
  — key on goalId, latest wins).
- "Done this month" = count of records with completedAt in the current
  calendar month. Time-dependent tests anchor at noon UTC
  ([[working-style]] — midnight-adjacent flake shipped once already).

Exit: four providers create/remove/prove goals through the planner
end-to-end headless; archive records survive profile round-trips;
negative paths pinned. Module count unchanged (no new modules — these
are affordances on existing tabs).

## G3 — RateSource: the bundled clog rates into CostModel

Today every KILL step and clog OBTAIN is NaN while clog.json (256
activities, iron completions/hr, 2,522 drop rows) ships in the jar.

- Load ClogPack in GoalPlannerModule.startUp, add to `EnginePacks`.
- Resolution (a small pure class, e.g. `engine/RateSource`): itemId →
  clog activity (the pack already indexes slots by item — see
  ClogRanker) → completions/hr + drop rate → expected hours =
  `expectedKillsForDrop(p) / perHour`, P90 via
  `unluckyKillsForDrop` — both ALREADY in CostModel, currently
  unwired. `kc:<source>` leaves match activity by name (the pack
  carries chat-name aliases; miss → NaN as today).
- **One implementation of the math**: ClogRanker's TTNS buckets and
  CostModel must converge on the same geometric core — extract it,
  have ClogRanker delegate. ClogRankerTest is the regression net.
- Plan.Step gains a `spreadHours` field (NaN when deterministic);
  explain card shows "up to ~Xh if unlucky" per PLANNER-UX 4b. The
  fingerprint must NOT include spread (plan diffs stay stable).
- Gear pack `hours` stays as the OBTAIN fallback; add a pack test
  cross-checking items present in both sources — order-of-magnitude
  disagreement fails the build (data bug, not code).
- Honesty: no clog rate → NaN exactly as today; zero-attempt purchase
  slots never get rate-costed (ClogRanker already excludes them —
  keep that rule).

Exit: a `clog:` goal plans with real expected hours + P90; RouterTest
golden scenarios updated deliberately (hour totals will change — that
is the point; eyeball the diffs, don't just re-record).

## G4 — methods.json v2 generator + freshness (GATED)

*(Historical note, 2026-07-23: G4 landed with rates UNCHANGED per Luke's
keep-practical-rates call; that decision was later superseded by his
methods-regen directive — the pack now merges the knowledge base's wiki
tables with wiki rates preferred. The WOM envelope + freshness machinery
below remain live.)*

Follow ENGINE-DESIGN §11's methods.json row exactly:

- `tools/gen_methods.py`: WOM's open-source ironman EHP configs at a
  PINNED commit (rates + bonus-xp edges) joined with wiki
  `Module:Skill calc/<Skill>` Lua (per-action xp/materials — reuse
  gen_xp_actions.py's proven fetch/parse) and the wiki
  `Ironman_Guide/<skill>` feasibility overlay. Requirement strings stay
  a hand-curated overlay FILE the generator joins — regeneration must
  never lose them. Generator conventions per CLAUDE.md (pinned
  sources, UA, cache, fail-fast, sanity asserts).
- Schema: every method gains mandatory `source`; pack gains
  `generated` (ISO date). CI: every ladder keeps a level-1 floor
  method (plans stall without one — RouterTest history), every req
  parses non-manual, calibration band ±25% vs ehp-calibration
  checkpoints (build tools/gen_ehp.py only if needed for the test —
  ENGINE-DESIGN §12).
- Freshness surfacing: hub hero + plan footer "meta: <Mon YYYY>" from
  the pack stamp; >~4 months old → a muted staleness note. No runtime
  HTTP, ever — regeneration is a build-time ritual.
- **STOP before landing**: present Luke the diff — methods
  added/removed, rate changes per skill, plan-hour deltas on the
  golden fixtures. He approves, then commit. This gate is standing
  (CLAUDE.md), not a one-off.

Exit: generator reproducible offline from cache; seed pack retired;
calibration + coverage tests green; Luke approved the data diff.

## G5 — Priority tiers, Someday, goal pins, per-Route task order

- `GoalSeed` gains `priority` ("high" | "normal" | "someday", default
  normal). Persisted pin ORDER: `List<String> pinnedGoals` in
  PersistedState (order-sensitive, so not a Set).
- Router (GOALS-V2 §3.2): a step's direct-progress value is weighted
  by the max tier among goals it serves (suggested: high ×2, normal
  ×1, someday ×0.05 — tune against golden fixtures); SOMEDAY goals
  stay in the DAG (dedupe + merge math keep working — test: a shared
  step still counts its someday goal in `neededBy` and the ×N badge)
  but their unique steps sink. Pinned goals outrank tiers: their
  unique steps get the pin weight (the existing +1000 mechanism at
  goal granularity), ordered among themselves by pin order.
  Determinism test extended over pins+tiers permutations.
- **Per-Route task order**: `Map<String, List<String>> routeTaskOrder`
  (goalId → action-id sequence) in PersistedState. UI arrows move a
  task among its FEASIBLE positions only (hard deps gate — recompute
  the feasible window from the DAG, a no-op press is fine, the farm
  picker precedent); the router honours the player's sequence for that
  route's tasks wherever the global order allows. Stale ids in the
  list (plan changed) are ignored harmlessly, never pruned eagerly.
- UI state on rows: pinned = select-fill + flag, someday = dimmed in
  place (the approved mockup grammar).

Exit: pins/tiers/order all persisted profile-scoped, router honours
them deterministically, no-silent-reshuffle banner still guards
external changes only (the player's OWN pin/reorder applies
immediately — the applyNextPlan seam, [[design-system]]).

## G6 — The suggester (SUGGESTIONS section brain)

GOALS-V2 §3.1, revised phrasing per §0.1:

- Candidate set (~40, curated builders): effects.json unlocks whose
  reqs are within reach; diary tiers ≤2 unmet leaves; gear items that
  change kill rates for KILL/OBTAIN steps in the current plan; quests
  whose xp effects shrink planned TRAIN nodes. Exclude anything
  already selected or achieved.
- For each candidate, `previewMerge` on the planner executor AFTER a
  replan settles (never per-keystroke, never blocking a replan),
  memoized on plan fingerprint; a run is abandoned if a new replan
  lands mid-loop.
- Classify by benefit kind, suggest at most ~5, ranked:
  **multi-route** (candidate's effects satisfy unmet reqs in ≥2
  routes — count against the DAG), **long-run time** (netHours < 0 in
  KNOWN hours only — NaN never backs a suggestion), **life-easier**
  (pack-sourced copy only — qol.json sourceNote grammar; no invented
  benefit text), **merge offers** (two routes sharing ≥5 tasks;
  accepting = both goals ride one route view — presentation only, the
  DAG already merges).
- Accepting any suggestion = a normal `addGoalSeed` through the
  provider system. While computing: an honest "computing…" line, not
  an empty section.

Exit: suggester never delays a replan (probe with EdtCostProbeTest
patterns), suggestions reproducible for a fixed fixture, every card's
numbers traceable to a previewMerge result.

## G7 — The single Goals surface (replaces the three views)

Build `GoalsHubMockup` live. The mockup file + its render test are the
spec — match them, then delete them in the swap commit (the Dailies
pilot precedent: the mockup dies when the real thing ships).

- New tab (suggest `GoalsHubTab`) mounted by GoalPlannerModule as its
  ONE tab. Four sections top-to-bottom: hero (Routes active / Done
  this month — the latter opens the ARCHIVE as a depth-2 view with
  back arrow + title; est-vs-took lines live there, "detected" for
  undated), CURRENT TASK (plan head respecting pins; live progress
  bar — reuse PlannerOverlay's XP-gauge math for xp-left/actions-left,
  it is already verified against RuneLite's tracker; Why line from
  step.why; serving routes), GOALS (category-grouped expandable route
  rows — family→category map: quest→Quests, gear/clog→Gear,
  skill/custom:skill→Level unlocks, ca/diary/qol/poh/clue→Unlocks,
  supply→Supplies; one route expanded at a time; task rows next-first
  with status glyphs, ×N folded INTO the name label, arrows+pin;
  update banner above the rows; add-goal search field with the
  existing merge-preview flow), SUGGESTIONS (G6 cards).
- Interaction rules that already bit us: RebuildGate.install for the
  plan/state listeners; every skinned string through OsrsLabel
  (wrapped()/squeezable()/leftAligned() as appropriate); mousePressed;
  row caps ~50 with "+ N more"; GlyphSafety ASCII; renders
  `goals-hub-tab-{stone,mystic}.png` + the client-mount label-height
  pin; theme flip drops the cached tab.
- THEN, in a separate commit AFTER Luke's render/in-client verdict:
  delete PlannerTab's Today/Route/Goals views and the embedded legacy
  GoalsTab, migrate anything still referenced (WhatNow is already
  deleted; Dashboard reads sharedPlan — unaffected; check
  PanelGalleryTest/ModuleRoutingTest and the planner render tests).
  PlannerOverlay is untouched throughout.

Exit: one surface under the Goals nav block, old views gone, all
planner behaviours (pin/snooze/ban, banner semantics, merge preview,
manual ticks) preserved and re-render-tested.

## G8 — Account-type detection (iron-first honesty)

- `AccountState.accountType` from the game's ironman-status varbit
  (resolve the gameval constant — verify against the client at the
  pinned tag, do NOT trust memory; document the decode in
  DOMAIN-NOTES). Values: standard/HC/UIM/GIM-variants/normal.
- UIM: banked-xp discounts OFF in PlannerService (bank is
  inaccessible) and the Bank tab's banked-XP views carry an honest
  note. Normal accounts: no behaviour change in v2 — the normal
  economy (GE buy paths, price data) is a SEPARATE post-v2 arc, gated
  on the opt-in-HTTP rule. Do not start it.

Exit: account type detected + persisted, UIM honesty proven by a test
(banked material never discounts a UIM plan), no normal-account
behaviour invented.

---

## After every slice

Update CLAUDE.md's current-state blob and NEXT-ARC line, DOMAIN-NOTES
for any new game-data knowledge (G8's varbit, G4's sources), README
feature bullets where user-visible, and the memory files per their
house rules. When G7 lands, GOALS-V2.md's status line flips to
"implemented"; design/PLANNER-UX.md gets a superseded note pointing
here.
