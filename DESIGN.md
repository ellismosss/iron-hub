# Iron Hub — Ironman Companion Plugin for RuneLite

**Design Document v0.2** · Scope: Standard Ironman · Target: RuneLite Plugin Hub

---

## 1. Vision

Iron Hub is the single side-panel a standard Ironman opens instead of ten wiki tabs. It answers three questions at any moment of an account's life:

1. **Where am I?** — quests, diaries, combat achievements, gear tiers, skill milestones, QoL unlocks, bank state, collection log.
2. **What should I do next?** — meta-aware suggestions ranked by impact and what the account can actually do *right now*.
3. **How do I do it?** — requirements, routes (Shortest Path), loadouts (best-in-bank + DPS calc), timers, and reminders.

Everything is progression-aware: the plugin knows what you own, what you've unlocked, and filters every recommendation through that lens. No "buy a Bandos chestplate" advice on an iron.

---

## 2. Architecture

### 2.1 High level

```
┌──────────────────────────── RuneLite client ────────────────────────────┐
│  EventBus (StatChanged, VarbitChanged, ItemContainerChanged,            │
│            ChatMessage, GameTick, WidgetLoaded, LootReceived,           │
│            ActorDeath)                                                  │
│                                │                                        │
│                        IronHubPlugin (core)                             │
│                                │                                        │
│        ┌──────────── AccountState (single source of truth) ──────────┐  │
│        │ skills · quest states · diary/CA varbits · bank snapshot    │  │
│        │ owned untradeables · PoH state · unlock flags · timers      │  │
│        │ collection log · STASH states · consumption rates           │  │
│        └──────┬───────────────┬───────────────┬───────────────┬──────┘  │
│               │               │               │               │         │
│         Feature modules (each: state reader + panel tab + overlays)     │
│   Gear · Quests · Skills · QoL · Bank/BankedXP · Farming · Dailies      │
│   Loot/Supplies · CA · Diaries · Boat · Slayer · Clues/STASH ·          │
│   Goals · WhatNow · Runway · CollectionLog · Dashboard · Death          │
│                                │                                        │
│        Persistence (profile-scoped JSON via ConfigManager + files)      │
│        Static data pack (JSON resources, updatable from data repo)      │
│        Bridges: Shortest Path (PluginMessage) · Wiki DPS calc ·         │
│                 WikiSync · Wise Old Man/Temple · Discord webhooks       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Core principles

- **AccountState as single source of truth.** Modules never read the client directly for shared state; they subscribe to a normalized `AccountState` service that ingests client events. Cuts duplicate varbit polling and makes modules unit-testable.
- **Data-driven content.** Gear ladders, quest orders, meta methods, dailies, QoL items, boat upgrades, clue item lists, CA difficulty ratings all live in versioned JSON. Game updates mean data-pack updates, not code releases. Ship a bundled pack; optionally fetch newer packs from a GitHub data repo at startup.
- **Requirement graph as shared infrastructure.** Quests, diaries, gear, QoL items and clue steps all express prerequisites in one common `Requirement` model (skill / quest / item / unlock / kc). The Goal Planner and What Now engine are consumers of this graph, not special cases.
- **Soft integrations only.** Plugin Hub plugins cannot depend on each other. Integrate with Shortest Path via RuneLite's `PluginMessage` event (namespace `shortestpath`, message `path`), and degrade gracefully when it isn't installed.
- **Everything profile-scoped.** All tracked state keys off the logged-in account (RSProfile), so multiple accounts on one client don't collide.
- **Read-mostly, never automate.** The plugin observes and advises. No automated input — keeps it Jagex-compliant and Hub-approvable.

### 2.3 Persistence

| Data | Mechanism |
|---|---|
| Small flags/settings | `ConfigManager` (profile-scoped keys) |
| Bank snapshot, loot history, timers, consumption rates, snapshots | JSON files under `RUNELITE_DIR/iron-hub/<profile>/` |
| Static content (ladders, methods, dailies, clue items, CA ratings) | Bundled resources + optional remote data pack |

Bank snapshots are captured on every bank open (`ItemContainerChanged` for the bank container) and diffed, so history stays cheap.

---

## 3. Feature Modules

### 3.1 Gear Progression

- Per-slot upgrade ladders per combat style (melee/range/mage) and per context (slayer, raids, specific bosses), sourced from the data pack (modelled on the wiki's gear progression tables, filtered to iron-obtainable order).
- Each item annotated: **Owned** (bank/equipped/PoH costume room), **Obtainable now** (reqs met), **Locked** (missing reqs, with the blocking req listed).
- Click an item → requirement tree (quests, levels, drops, components) + "route me there" (Shortest Path bridge) + drop source with rates.
- "Next best upgrades" widget on the dashboard: top 3 highest-impact upgrades currently obtainable, per style.

### 3.2 Quest Progression

- Reads quest states via the client's quest API/varps. Presents an optimal quest order (data pack; based on community iron quest orders) with the *next actionable quest* surfaced.
- Requirement graph: for a locked quest, show the full dependency chain and which leaf requirements (skills/items/quests) are missing.
- Item pre-fetch list per quest: everything to bring, checked against bank/inventory so it renders as a shopping list of what you still need and where an iron gets it.

### 3.3 Skill Milestones & Meta Methods

- Per skill: milestone timeline (unlocks that matter — e.g. Herblore 38 prayer pots, Agility 70 for Sepulchre floors) with current level marker.
- Meta method browser: for the current level, show the recommended iron methods (data pack) with XP/hr and inputs listed as *supplies needed*, checked against the bank.
- Covers skilling, slayer (task priorities per level bracket) and bossing (what's meta to farm at this account stage).
- EHP context from External Sync (§3.19) where enabled.

### 3.4 Shortest Path Integration

- Any entity with a location (quest start, patch, boss, shop, STASH, diary step) gets a "Path" button.
- Implementation: publish `PluginMessage("shortestpath", "path", {start?, target})`. If Shortest Path isn't installed, fall back to opening the wiki map or showing coordinates, and show a one-time hint suggesting the plugin.

### 3.5 QoL Progression Checklist

- Curated checklist of account QoL unlocks: herb sack, seed box, gem bag, coal bag, rune pouch, graceful, Ava's, fairy rings, spirit trees, PoH (pool tiers, portals/nexus, jewellery box tiers, altar, costume room), Kourend favors, dragonfire wards, music cape teleports, elite void, etc.
- Each entry: owned/complete detection (item in bank, varbit, PoH board), requirements, acquisition guide link, Path button.
- PoH sub-tracker: parses house state where detectable; otherwise manual check-off with construction level gating what's shown as available.

### 3.6 Bank Tracking, Banked XP & Best-in-Bank Gear

- **Bank snapshot**: full bank contents cached on open; searchable from the side panel while anywhere in the game world ("do I own a ruby harvest? how many ranarrs?").
- **Banked XP**: maps bankable resources → actions → XP per skill (Banked-Experience-style), with method selection (e.g. make unf potions vs full potions). Dashboard chip: "Herblore: 2.4M banked".
- **Best-in-bank loadout solver**: given a scenario (boss/monster + style, from the data pack), compute the best owned gear set. Stats come from RuneLite's item stats; ranking via max-hit/accuracy heuristic locally.
- **DPS calc integration**: one click exports the generated loadout + current stats to the OSRS Wiki DPS calculator (WikiSync-style sync or URL-encoded loadout), so the authoritative numbers live where they're maintained.
- Loadouts exportable as Bank Tags / Inventory Setups strings.

### 3.7 Boat Upgrades (Sailing)

- Data-driven tracker for boat/ship upgrade tiers: hull, facilities, salvaging gear, etc. Owned/available/locked, material checklists against the bank, and best-available-boat summary.
- Kept fully in the data pack since Sailing content will evolve rapidly; module code is a generic "upgrade tree renderer" shared with PoH and gear ladders.

### 3.8 Farming Run Helper

- Patch state tracking via varbits (crop stage, disease, dead, compost) for all patch groups: herbs, trees, fruit trees, hardwood, hops, allotment, bushes, cacti, seaweed, Hespori, anima, crystal, celastrus, calquat.
- **Run timer**: start a run → per-patch checklist in overlay, route ordering with Path buttons, elapsed time, and a historical log ("herb runs average 5:40; best 4:55").
- Ready notifications (patch grown, Hespori ready) with configurable channels (tray, infobox).
- Seed stock awareness: warns when planting outpaces seed reserves (feeds Supplies Runway, §3.17).
- Sub-timers for adjacent loops: birdhouses, compost bins, big compost, giant seaweed.

### 3.9 Loot & Supplies Tracker

- **Loot**: per-source tracking (`LootReceived`/`NpcLootReceived`), aggregable globally, per boss, per slayer task, per trip, per session. Uniques highlighted; wealth is de-emphasized (irons care about *items*, not GE value) but value shown for interest.
- **Supplies**: consumption tracking per trip/task — food eaten, potions sipped, runes/ammo used, charges spent (trident, blowpipe, crystal). Report: "Zulrah: avg 1.2 sharks, 0.8 anti-venom per kill" (per hour and per kill views).
- Net view: loot minus supplies per activity, in items — the iron-relevant sustainability metric.
- Consumption rates stream into Supplies Runway (§3.17); kill counts stream into Collection Log stats (§3.18).

### 3.10 Combat Achievements

- Tier progress from varbits, per-boss grouping, filter by "possible with my current gear/stats" using the loadout solver.
- "Easiest next" list: incomplete tasks sorted by community difficulty rating × your readiness. Task detail shows mechanics notes from the data pack.
- Points-to-next-tier and reward-unlock summary (e.g. Ghommal's hilt tiers).

### 3.11 Achievement Diaries

- Per-region, per-tier progress via varbits; requirement diff against current stats ("Falador Hard: need 76 Agility (you're 74)").
- "Closest to completion" sorting; each task gets Path + requirement links; lamp/reward summary to help choose targets.

### 3.12 Dailies & Recurring Reminders

- Configurable checklist with reset-aware timers (daily 00:00 UTC, weekly, custom):
  - Battlestaves from Zaff (scales with Varrock diary)
  - Tears of Guthix (weekly, tracks QP-gain eligibility)
  - Miscellania coffer & approval (with kingdom profit estimate)
  - Hespori seed check, buried bird bones, Champions' Guild flax, sand from Bert, seaweed/pineapples from Arhein, ogre arrows from Rantz, pure essence from Wizard Cromperty
- Detection via varbits/chat messages where possible; manual tick otherwise. Compact infobox shows count of dailies outstanding; panel shows the list with Path buttons.

### 3.13 Goal Planner (dependency engine)

The keystone module. Pick any target — an item ("Bowfa"), a quest ("Song of the Elves"), a capstone ("Quest Cape", "base 80s", "Barrows Gloves") — and the planner walks the shared requirement graph against AccountState to produce the full dependency tree, collapsed to only what's *missing*, ordered into an actionable plan.

- **Plan structure**: tree view (what depends on what) + flattened checklist view (do this, then this). Steps carry Path buttons, method links (§3.3), and loadout links (§3.6).
- **Live progress**: steps auto-complete as AccountState changes (level gained, quest done, item banked). No manual ticking for detectable steps.
- **Multiple goals**: concurrent goals with one pinned "active goal" shown as a dashboard strip and optional overlay. Shared prerequisites across goals are deduplicated and flagged ("70 Agility feeds 3 of your goals").
- **Estimates**: rough time-to-complete per step from data-pack rates (XP/hr for level steps, avg completion time for quests, drop-rate expectation for item steps).
- Implementation: goals compile to a DAG of `Requirement` nodes; topological sort with cheap-first tie-breaking produces the plan. Recompute is event-driven, off the client thread.

### 3.14 "What Now?" Suggestion Engine

Answers login paralysis. A ranked shortlist of 3–5 things worth doing *right now*.

- **Inputs**: time available (quick selector: 5 min / 30 min / 1 h / 2 h+), active goals (§3.13), dailies outstanding (§3.12), farming/birdhouse readiness (§3.8), slayer task state (§3.16), banked XP (§3.6), supplies runway warnings (§3.17).
- **Scoring**: each candidate activity gets `impact × urgency × fit`: goal-relevance drives impact, decaying opportunities (grown crops, weekly resets) drive urgency, time-available and current location drive fit.
- **Output**: "Herb run (6 min, 7 patches ready) → Hespori kill → 40 min Sepulchre toward 72 Agility for your SotE goal". Each suggestion is one click from its module detail.
- Deliberately explainable: every suggestion shows *why* it ranked ("feeds active goal", "resets in 3 h").

### 3.15 Clue & STASH Helper

Clue completion on an iron is gated on owned items — this module makes that visible.

- **STASH tracker**: built/filled state per STASH unit via varbits; construction material checklist for unbuilt ones against the bank.
- **Emote clue readiness**: per tier, percentage of emote clues completable with owned items ("hard: 84%"), with the missing-item list and iron acquisition source for each (drop, shop, skilling).
- **Clue session support**: while a clue is active, show the step's requirements vs owned, STASH proximity, and Path button.
- **Keep-list**: items needed by clue steps are flagged in the bank view so they don't get cleaned out during bank purges.

### 3.16 Slayer Optimizer

- **Task tracking**: current assignment via chat parsing + varps; kill counts, streak, points.
- **Point planning**: models point income vs spend against your unlock goals (block slots, extends, helm recolors are cosmetic-flagged) and recommends when to skip vs bank points.
- **Block/skip advisor**: given your gear, stats and goals, ranks the block-list candidates by expected time-cost of keeping the task, using per-task rates from the data pack.
- **Per-task readiness**: on new assignment, auto-generate the best-in-bank loadout (§3.6), the recommended method/location, and supply expectations from your own historical consumption (§3.9).
- Task-source comparison (which master to use at this stage) driven by the data pack.

### 3.17 Supplies Runway

A consumption-rate model over the Supplies tracker — the iron's answer to "can I afford this grind?"

- **Runway per consumable**: from rolling usage rates (§3.9) and current bank stock: "14 h of prayer potions, 6 h of blowpipe scales, 22 h of karambwans".
- **Shortfall alerts**: configurable thresholds; warnings surface in the dashboard and What Now (restocking becomes a ranked suggestion when runway is short).
- **Restock guidance**: each consumable links to its meta restock loop for irons (Herblore method with banked-ingredient check, hunter spot, shop run, farming dependency) with Path buttons and time-to-restock estimates.
- **Planning mode**: "I want to do 100 Zulrah kills" → projected supply bill from your per-kill averages, diffed against stock.
- Includes herblore secondaries planning and seed sustainability (consumes Farming module data).

### 3.18 Collection Log & Pets

- **Log progress**: per-activity collection log state (varbits/widget parsing on log open), surfaced inside each boss/activity view and as an overall completion metric.
- **Dry-streak stats**: KC since last unique, expected-vs-actual rates ("2.1× rate on Vorkath head"), pet "chance seen so far" per pet. Strictly informational framing — no tilt-inducing pressure.
- **Suggestions**: "cheapest unfinished log slots" ranked by expected time given your gear (loadout solver) — feeds What Now for log-hunter users.
- Kill counts sourced from Loot tracker (§3.9) and chat parsing.

### 3.19 External Sync

All opt-in, all documented (Hub requirement for external HTTP):

- **WikiSync-compatible sync**: pushes quest/diary/level/unlock state so OSRS Wiki tools (DPS calc, quest planners) can read the account. This is the preferred DPS-calc integration path (§3.6).
- **Wise Old Man / TempleOSRS**: pulls EHP/EHB and gain data for context in Skills (§3.3) and the Dashboard (§3.20); optional auto-update ping on logout.
- **Discord webhooks**: configurable milestone notifications — new collection log slot, unique drop, pet, level milestone, CA tier, diary tier, goal completed — with optional screenshot attach.

### 3.20 Progress Dashboard & Account Score

The panel's home tab and the account's "front page":

- **Composite account score**: weighted blend of quest %, diary %, CA points %, QoL checklist %, gear ladder %, collection log % — with the weighting user-tunable and each component clickable through to its module.
- **Trend sparklines**: periodic snapshots (weekly, stored locally) show score and component trajectories over the account's life.
- **At-a-glance strips**: active goal progress, top 3 next-best upgrades, dailies outstanding, patches ready, runway warnings, current slayer task.
- **Shareable snapshot**: export a progress-post image (score card + recent milestones) for Reddit/Discord progress posts.

### 3.21 Death Recovery Helper

- On `ActorDeath` (local player): capture held-item state from the last container snapshot, record death location and grave timer.
- Panel card: what's in the grave, reclaim cost, what went where (grave vs Death's Office vs kept), untradeable handling notes.
- History of recent deaths for post-mortem ("what did I actually lose at Vorkath yesterday?").
- Panic-reducing by design: calm, factual, one Path button to the grave.

---

## 4. UI Design

- **Side panel** (single navigation icon): Dashboard home (§3.20) + module tabs behind a searchable nav list. Dense-but-scannable cards; consistent Owned/Available/Locked color language (green/amber/grey) across all modules.
- **Overlays**: farming run checklist, active-goal tracker, supplies-per-trip HUD, clue step helper (all optional, off by default).
- **Infoboxes**: dailies outstanding, patches ready, active run timer, slayer task.
- **Notifications**: RuneLite-native, per-category toggles (patch ready, daily reset, runway shortfall, goal step complete, log slot).
- Every list row follows the same pattern: *status icon · name · blocking requirement (if locked) · [Path] [Wiki] buttons*.

## 5. Data Sources

| Source | Used for |
|---|---|
| Varbits/Varps | quests, diaries, CAs, farming patches, dailies, STASH, collection log, slayer |
| `ItemContainerChanged` | bank, inventory, equipment tracking |
| `StatChanged` | levels, XP, banked-XP recompute, goal step completion |
| `LootReceived` / `NpcLootReceived` | loot tracker, dry-streak stats |
| `ChatMessage` (regex) | slayer assignments, daily confirmations, charge counts, KC |
| `ActorDeath` + container diffs | death recovery |
| RuneLite item stats API | loadout solver |
| Data pack (JSON) | ladders, methods, orders, dailies, CA ratings, clue items, task rates |
| OSRS Wiki (deep links) | guides, DPS calc |
| WikiSync / WOM / Temple / Discord (opt-in HTTP) | external sync (§3.19) |

## 6. Technical Considerations

- **Performance**: all heavy computation (loadout solving, banked XP, goal DAG recompute, suggestion scoring) off the client thread; recompute on relevant events only, never per-tick.
- **Hub compliance**: no client automation, no bundled hub-plugin dependencies, external HTTP opt-in and documented.
- **Shared requirement graph**: one `Requirement` model + resolver service used by quests, diaries, gear, QoL, clues, goals — build it early, everything leans on it.
- **Testing**: AccountState fully mockable; data pack schema-validated in CI; module logic unit-tested without a client.
- **Config surface**: per-module enable toggles so users can run Iron Hub as "just the farming helper" if they want.

## 7. Roadmap

| Phase | Contents |
|---|---|
| **1 — Foundation** | Core plugin, AccountState, requirement graph, persistence, panel shell, data-pack loader; Quests, Diaries, CAs (read-only trackers are quick wins) |
| **2 — Bank brain** | Bank snapshot/search, Banked XP, QoL checklist, Dailies |
| **3 — Combat** | Gear ladders, loadout solver, DPS calc export, Loot/Supplies tracker, Slayer optimizer |
| **4 — Daily loop** | Farming runs + timers, Shortest Path bridge, notifications, Boat upgrades, Death recovery |
| **5 — Intelligence** | Goal Planner, What Now engine, Clue/STASH helper, Supplies Runway |
| **6 — Hub & sync** | Collection log & pets, Progress Dashboard & account score, WikiSync/WOM/Temple sync, Discord webhooks |
