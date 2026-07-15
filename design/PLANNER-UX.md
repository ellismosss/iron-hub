# Goal Planner UX — Design Exploration

Status: **proposal** — the UX layer over ENGINE-DESIGN.md's plan output.
Companion mockups: `design/goal-planner-mockups.html` (frames 4a–4f, same
conventions as iron-hub-mockups.html; all type maps to the ≥14px UiTokens
scale in-client). Supersedes frames 2d/1d where they conflict; the shared
atoms and hard constraints (225px, depth ≤2, flat Swing, painted glyphs)
all still bind.

---

## 1. Jobs to be done

Ordered by frequency, from how the plugin is actually used (session-start
is the moment the panel gets opened):

| # | job | today's answer | gap |
|---|---|---|---|
| J1 | "I have 45 minutes — what should I do *right now*?" | WhatNow's static scored list | not plan-aware, no time fit, no why-numbers |
| J2 | "How far is Bowfa, and what's the path?" | goal detail tree (1d) | shows *structure*, not *route or hours* |
| J3 | "What should my goals even be?" | Browse targets grid | fine — keep, extend with merge preview |
| J4 | "I hate this step — what else?" | nothing | needs alternatives + ban/prefer |
| J5 | "What changed / what did I achieve?" | nothing | needs diffs + milestones |
| J6 | "Are my passive lanes running?" | separate farming/dailies tabs | needs one glance in the planner |

The single biggest UX decision: **the planner opens on J1, not J2**. Goal
lists are the *configuration*; the plan head is the *product*. Today's 2d
frame opens on configuration.

## 2. Principles (the panel-shaped truths)

1. **Reorder by constraint, not by drag.** 225px + Swing makes
   drag-to-reorder miserable and imprecise. Every step instead offers
   three one-tap constraints — **pin** (do this next), **snooze** (not
   today), **ban** (never suggest this method/step) — and the engine
   re-routes around them. This is *more* powerful than dragging: the
   plan stays feasible and explains the consequence ("pinning CG now
   costs +1.2h vs doing Royal Titans first").
2. **No silent reshuffles.** A plan that reorders itself while being
   read cannot be trusted. State changes queue into a one-line **plan
   update banner** ("Plan updated: 2 steps cheaper · review ›"); the
   list only changes when tapped (or on tab re-entry). Diffs are shown
   as green/red hour deltas, not as mysterious motion. (No animation is
   a Swing constraint that becomes a feature here.)
3. **Every number earns its row.** One number per fact (house rule):
   a step row = name + one time figure. Everything else (spread, why,
   alternatives, goals served) lives one tap deeper in the expand.
4. **Three taps from anything to "why".** Trust is the product. Row →
   expand → full explain card with computed numbers. Never a black-box
   score anywhere in the UI (scores exist in the engine only).
5. **The player's taste is visible state.** Bans/pins/snoozes show as
   chips on the affected rows — the plan never looks arbitrarily
   different from what a guide says without showing *which of your
   choices* made it so.

## 3. Information architecture

One tab, three views over one plan — segmented control at top (existing
atom), preference persisted:

```
Goal planner  [ Today | Route | Goals ]        depth 1
  └─ goal detail (per-goal route slice + tree) depth 2
  └─ add goal (search-first picker)            depth 2
```

- **Today** (default): the session view — time-budget chips, the Now
  card, up-next rows, passive-lane card, since-last-session strip.
- **Route**: the whole ordered plan, grouped into chapters, with the
  horizon header and update banner. This is the engine made visible.
- **Goals**: evolved 2d — active/other/completed goals + browse grid +
  "+ Goal". Configuration lives here, out of the daily path.

WhatNow's tab remains for cross-module non-plan suggestions but its top
card becomes plan step 1 (ENGINE-DESIGN §10) — one brain, two windows.

## 4. View specs

### 4a Today (frame 4a)

- **Horizon header** (replaces summary card): `All goals · ~212h at your
  pace ~6w` — two numbers, both tappable (hours → Route; pace → tooltip
  "measured 5.1h/week over last 4 weeks").
- **Budget chips**: `30m · 1h · 2h+ · AFK` (ChipRow atom). Selecting
  filters/reorders Today's suggestions to steps that *fit* — a 30m
  budget surfaces the 25-min quest, not the 17h CG grind; AFK surfaces
  afk-tagged steps only (naguas, salvaging). Selection persists per
  session, defaults to last used.
- **Now card** (accent border, the only accent-bordered card in view):
  step name, one time figure, one why-line, glyph row of served goals
  (tiny goal icons), and two IconButtons — ⚑ Path (Shortest Path
  bridge) and W Wiki. Tap card → inline expand (frame 4b).
- **Up next**: 3 dense rows (26px): status glyph + name + time.
  Right-click any row: pin / snooze / ban / alternatives (context menu —
  established pattern from CA tab).
- **Passive lane card**: one row per running lane (`Farming 61→65 ·
  ~Aug 2`, `Birdhouses idle 3h ✗` in amber when stale) + one-line setup
  suggestion when a lane is unlockable but not running. Tap → farming/
  dailies tab (cross-tab nav exists).
- **Since last session strip** (muted, collapsible, auto-collapsed when
  empty): `✓ Fairytale II · plan −2.1h` — one line per completed
  step/milestone since last login.

### 4b Step expand — the explain card (frame 4b)

Inline expansion of any step row (accordion, one open at a time — diary
tab pattern):

- **Why now**: the full sentence with computed numbers ("unlocks fairy
  rings — saves ~3.5h across 14 later steps").
- **Time**: expected + spread when stochastic (`~17h · up to ~39h if
  unlucky`, spread in muted) + basis line in faint ("pack rate 3 CG/hr —
  no measured data yet").
- **Serves**: goal chips (`Bowfa · Quest cape +2`), each tappable →
  goal detail.
- **Alternatives** (when the catalog has them): up to 2 rows —
  `or: Colossal Wyrm course · +2.3h · low intensity` with a per-row
  "prefer" affordance. Preferring re-plans with that method pinned for
  this skill (and shows as a taste chip).
- **Constraint buttons**: `Pin next` `Snooze` `Ban` — flat buttons,
  full-width row, consequences shown after tap in the update banner
  ("Pinned. Plan +1.2h › undo").
- **Manual steps**: same card, tick-box instead of time, engine
  disclosure line: "can't detect this — tick when done".

### 4c Route (frame 4c)

- **Update banner** (when pending): single amber row pinned under the
  header — `Plan updated: 2 steps cheaper, 1 reordered · apply ›` —
  tap applies + shows per-step deltas as green/red hour chips that
  persist until next interaction. Never auto-applies while the tab is
  visible.
- **Chapters**: the plan grouped by the engine's linearized phases —
  collapsible SectionLabel headers with aggregate time
  (`QUESTS & UNLOCKS · ~9h`, `STAT BLOCKS · ~41h`, `THE CG GRIND ·
  ~17h`) — the BRUHsailer-chapter mental model the community already
  has. Chapter headers show a 4px mini progress bar.
- **Step rows** (dense 24px): position number (faint) + status glyph
  (✓ done · ● next/doable · ○ waiting) + name + right time. Pinned rows
  get an accent left-edge tick; snoozed rows sink to a collapsed
  `SNOOZED (3)` section at the bottom; banned steps don't appear (they
  live in Settings › Planner as a reviewable list).
- **Shared-step badge**: rows serving 2+ goals show a faint `×3` after
  the name (tooltip lists the goals) — the dedupe made visible, which
  is the planner's best sales pitch for adding more goals.
- **RNG rows**: time shown as `~17h` with a tilde; the spread lives in
  the expand only (principle 3).

### 4d Goal detail (frame 4d — evolves 1d)

- Header card: name, %, bar, `~34h remaining · 12 of 21 steps` and an
  ACTIVE chip / `make active ▲` affordance (unchanged concept).
- **Route slice | Tree** segmented toggle (Tree keeps the existing 1d
  dependency view — it answers "structure", the slice answers "path"):
- **Route slice**: this goal's steps *in plan order*, with steps shared
  with other goals marked `shared` (faint) — and a one-line insight
  card when relevant: `6 of these steps already serve Quest cape —
  adding it costs only ~4h extra` (the merge upsell, computed).
- **Critical path emphasis**: the longest-chain steps get a subtle
  left-edge amber tick — "these are the ones that gate your finish
  date" (tooltip copy).

### 4e Add goal (frame 4e)

Search-first (SearchField atom, autofocused): one box searches pack
goals, gear-chart items, quests, skills ("agility 70" → skill target),
bosses (KC targets), CA/diary tasks. Results grouped by kind with the
existing glyph language. Below the search: the Browse targets grid
(unchanged 2d concept) for the direction-seeker.

**The merge preview is the moment that sells the planner.** Selecting a
candidate shows a preview card *before* confirming:

```
Achievement diary cape           [+ Add goal]
adds ~68h to plan (~46h shared with your goals)
· shares: 70 Agility, SotE chain, 9 more
· new chapters: ELITE DIARIES (~22h)
```

Numbers from a dry-run merge — the engine is fast enough (<100ms) to
compute this live per selection.

### 4f Goals view (frame 4f — evolved 2d)

Active goal card + other-goal rows (mini-bar + % + ▲) — proven, kept.
Additions: each row gains its remaining-hours figure (the one number
that was always missing), the dedupe note card becomes computed instead
of static copy, and Completed gets a `this month: 3 ✓` header count.
Browse grid moves to Add goal (4e) — Goals view stays short.

## 5. Feedback & celebration (no confetti, still satisfying)

- Step completion: the row flips to ✓ green + strikethrough and holds
  position until the next replan apply (no instant vanish — the player
  should *see* the win). Chat message option (existing caGoalMessages
  pattern): `[Iron Hub] Fairytale II done — plan −2.1h, next: Ardougne
  cloak 2 (~40m)`.
- Goal completion: goal card gets the green treatment + moves to
  Completed on next Goals visit; optional chat line with total measured
  hours vs first estimate ("estimated 34h · took you 29h") — honest
  numbers as the reward.
- Weekly recap row on Today (Mondays, collapsible): steps done, hours
  measured, plan delta. No streaks, no pressure mechanics — factual
  framing per the collection-log DRY STREAK precedent.

## 6. Component inventory (all existing atoms — no new primitives)

| need | atom |
|---|---|
| view switcher | SegmentedControl (3-way, like CA) |
| budget chips | ChipRow |
| Now/goal cards | card grammar + accent border (only one per view) |
| step rows | ListRow dense 24px + StatusGlyph |
| expand/collapse | accordion via mousePressed + PaintedIcon triangles |
| pin/snooze/ban | right-click JPopupMenu (CA pattern) + flat buttons in expand |
| update banner | AlertChip row (exists) |
| chapter headers | SectionLabel + mini HubProgressBar |
| add-goal search | SearchField + grouped rows |
| goal icons | GoalsPack.Goal.icon() sprites (exists) |

New painted glyphs needed: pin (small triangle-flag), snooze (moon or
zz — must survive PaintedIcon's ASCII-safe rule: painted, not font),
shared-×N badge (plain text, fine).

## 7. Prioritization (maps to ENGINE roadmap E5)

- **P0 (E5 MVP)**: Today view (horizon, Now card, up-next, why-lines),
  Route view (chapters, step rows, update banner), pin/snooze/ban,
  goal-detail route slice. Without these the engine is invisible.
- **P1**: budget chips, explain-card alternatives + prefer, merge
  preview in add-goal, passive-lane card, since-last-session strip,
  completion chat messages.
- **P2**: weekly recap, taste settings page (ban list review, afk
  slider), Now overlay (3x family: active step + timer) — IMPLEMENTED
  as `PlannerOverlay` (live ETA + step-progress bar + method/resources
  + Next/Goal context, right-click snooze/mark-done), estimate-vs-
  actual on completion, critical-path ticks.

## 8. Open questions for Luke

1. Default view: Today (proposed) or Route? Today optimizes the common
   session; Route shows off the engine.
2. Budget chips: 4 fixed buckets (proposed) or a free-form minutes
   field? Chips fit the panel; field is more precise.
3. Snooze semantics: "until tomorrow" (proposed, matches dailies reset)
   or "until I unsnooze"?
4. Should banned methods be per-skill ("never 3-tick granite") or
   global tags ("never anything intensive")? Proposed: both — row bans
   are per-method, a Settings slider handles the global taste.
5. Chat messages default: on for goal/plan milestones (proposed, quiet
   wording) or opt-in?
