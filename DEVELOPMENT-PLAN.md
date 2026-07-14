# Iron Hub — Development Plan

Milestone-based; every milestone is shippable and independently testable. Follows DESIGN.md §7 phases with explicit sequencing logic, exit criteria, and release points. Referenced by CLAUDE.md.

## Principles

- **Vertical slices.** Build each module end-to-end (data pack → AccountState → panel tab → overlay), not layer-by-layer. Every merge is demoable.
- **Riskiest foundations first.** The 225 px UI system and the requirement graph are the two things everything else leans on — they come before any feature logic.
- **Ship early.** Plugin Hub review has real latency; submit at v0.1 so review and architecture feedback arrive while change is still cheap.
- **Definition of done (every module, non-negotiable):**
  - Styles exclusively from `UiTokens`; side-by-side match with the mockup frame id
  - Heavy computation off the client thread; re-render on events, never per-tick
  - Logic unit-tested against mocked AccountState (no client required)
  - Data-pack JSON schema-validated in CI
  - Config toggle works; module cleans up fully on shutDown()

## Milestones

### M0 — Walking skeleton *(days)*
Git + CI (`gradlew build` + data-pack schema validation), dev client launches via `IronHubPluginTest`, panel opens, config toggles register, module lifecycle (startUp/shutDown) verified.
**Exit:** clean build in CI; smoke test in dev client.

### M1 — UI atoms + panel shell
1. Shared components in `com.ironhub.ui.components`: list row (owned/available/locked two-line/warning states), grid tile + labeled tile, ⊞/☰ toggle, segmented control, progress + mini bars, search field, nav header with back arrow, alert chip.
2. Dashboard (frame 1b) and module navigation (1c) on hardcoded placeholder data.

Fake data is deliberate: it derisks the 225 px constraint and the visual system before any real logic exists.
**Exit:** side-by-side match with frames 1a–1c; nav depth ≤ 2 works.

### M2 — Data spine
- AccountState ingestion: varbits/varps, quest states, stats, item containers; profile-scoped persistence (ConfigManager + JSON under `RUNELITE_DIR/iron-hub/<profile>/`).
- Data-pack loader with schema validation.
- **Requirement graph** (DESIGN.md §2.2): the `Requirement` model + resolver. Highest-leverage component — quests, diaries, gear, QoL, clues and goals all consume it. Pure logic, fully unit-testable.

**Exit:** mock-driven tests green; state survives relog and client restart.

### M3 — Read-only trackers → **v0.1, first Hub submission**
Quests, Achievement Diaries, Combat Achievements modules. Cheapest real value; exercises the entire spine (varbits → graph → list rows).
**Exit:** all three tabs live against a real account; Hub submission filed.

### M4 — Bank brain → **v0.2**
Bank snapshot + search, Banked XP, QoL checklist, Dailies + first infobox (dailies outstanding).
**Exit:** frames 2f (search/banked XP) and dailies flows match; snapshot diffing stays cheap on large banks.

### M5 — Combat → **v0.3**
Gear ladders (2a), best-in-bank loadout solver, DPS calc export, Loot & Supplies tracker (2g), Slayer optimizer basics.
**Solver correctness is a trust issue — unit-test heavily against known setups.**
**Exit:** solver test suite green; per-kill supply averages accumulate correctly.

### M6 — Daily loop → **v0.4**
Farming runs + run timer overlay (3b), remaining infoboxes (3f), Shortest Path bridge wired to every locatable row, notifications, Boat upgrades (data-pack-only), Death recovery.
**Exit:** full herb run tracked end-to-end in dev client; overlays within 250×200 budget.

### M7 — Intelligence → **v0.5**
Goal Planner (1d, 2d), "What Now?" engine, Clues & STASH, Supplies Runway.
Deliberately late: these compose all lower layers and are only as good as the data feeding them.
**Exit:** a real goal (e.g. Bowfa) compiles to a correct missing-steps plan that auto-completes from live events.

### M8 — Hub & sync → **v1.0**
Collection log & pets, Dashboard account score + snapshots, WikiSync/WOM/Temple sync (opt-in), Discord webhooks.
**Exit:** score components clickable; sync opt-ins documented for Hub review.

## Risk register

| Risk | Mitigation |
|---|---|
| Varbit mapping grindier than expected | Buffer in M2/M3; lean on runelite-api constants and community varbit references; map incrementally per module |
| Collection log parsing fragile (widget-based) | Degrade gracefully to "open your log to sync"; never block other modules on it |
| Sailing/boat content churn | Boat module stays 100 % data-pack; zero gameplay constants in Java |
| Loadout solver wrong answers | Golden-test suite of known gear scenarios; ship behind "beta" label until confident |
| Hub review latency/feedback | Submit at v0.1; keep external HTTP opt-in and documented from the start |
| Panel perf on big lists | Lazy tab construction; virtualize long lists; re-render on events only |

## Working agreement for implementation sessions

Start each session from CLAUDE.md; pick the next unchecked milestone item; end with `./gradlew build` green and a dev-client smoke test. UI work always closes with a mockup side-by-side against the frame id.
