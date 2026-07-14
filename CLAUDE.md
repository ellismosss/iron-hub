# Iron Hub — Claude Code Handoff

RuneLite Plugin Hub plugin: an all-in-one progression companion for standard OSRS Ironman accounts. Design phase is complete; your job is implementation.

## Document map (read in this order)

1. **DESIGN.md** — full feature spec. §2 architecture, §3 the 21 feature modules, §7 roadmap phases.
2. **design/DESIGN-PACKAGE.md** — UI handoff from design: tokens, shared atoms, per-screen specs (frames 1a–3f), interaction rules. This is the UI source of truth.
3. **design/iron-hub-mockups.html** — open in a browser. High-fidelity mockups; frame id badges (1a–1d visual system/P0, 2a–2g module tabs, 3a–3f overlays/infoboxes) match the sections in DESIGN-PACKAGE.md.
4. DESIGN-HANDOVER.md — the original design brief; superseded by the design package where they differ.

## Current code state

- `com.ironhub.IronHubPlugin` — entry point; a `@Provides Set<IronHubModule>` registers 19 of 21 modules (RuneLite ships Guice without the multibindings extension). TODOs mark the rest: skills, boat. Config toggles gate module lifecycles (`enabled()` + ConfigChanged sync).
- `com.ironhub.IronHubConfig` — per-module enable toggles + opt-in integration settings.
- `com.ironhub.state.AccountState` — single-source-of-truth service: skill/quest/container ingestion, unlock flags, kill counts; profile-scoped persistence via `ProfileStore` (JSON under `RUNELITE_DIR/iron-hub/<accountHash>/`). Quest states refresh tick-throttled on varbit dirty. Test seam: `StateFixture` (test sources).
- `com.ironhub.requirements` — the shared requirement graph (`Requirement` + `Requirements`): skill/quest/item/unlock/kc + allOf/anyOf composites, `missing()` leaf resolution, and `parse()` for the data-pack string form (`skill:Farming:65`); unparseable strings become manual (never auto-met) requirements.
- `com.ironhub.data.DataPack` — bundled data-pack loader (`/data/<name>.json` → typed model, e.g. `DailiesPack`); content schema-validated in CI, loader fails fast on missing/corrupt packs.
- `com.ironhub.modules.*` — one package per module; tabs contributed via `IronHubModule.buildTab()`, routed by nav-row name (guarded by `PanelGalleryTest.navRowsExistForImplementedModules`). **Live (M3): quests, diaries, ca** (documented API constants only). **Live (M4): bank (search + banked XP, frame 2f), qol (requirement-graph checklist), dailies (reset-aware manual ticks + outstanding infobox; varbit/chat auto-detection pending)**. **Live (M5): gear (2a ladders, 3 styles), loadout (top-level module per user direction — deviates from 2f's embedded placement; BETA solver, golden-tested, solves on the client thread), loot (per-source totals + per-kill view over NpcLootReceived)**. Remaining stubs: skills, boat, farming, goals, whatnow, clues, slayer, supplies, collectionlog, sync, dashboard-module, death. **M5 complete (v0.3): + dps export (protocol verified live), loot (2g incl. supplies-used), slayer basics (varps + cache-DB task names + infobox)**. Supplies consumption = trip diffing: checkpoint carried gear at bank interactions/kills, decreases attribute to the killed source, potion sips cancel via ItemVariationMapping. Still open for later milestones: uniques pack, per-hour/session views, block/skip advisor. **M6 in progress: farming** — run timer + proximity-based patch checklist (2e tab, 3b overlay, run-timer infobox), first Overlay + first ShortestPathBridge consumer; crop-stage varbits pending. **death recovery** (ActorDeath capture: when/where/carried, capped history, Path to spot; reclaim costs later). **herb crop states**: HerbPatchDecoder generated from core's PatchImplementation.HERB at the pinned client tag, held equivalent by a reflective parity sweep (HerbDecoderParityTest) — regenerate from the tag if it fails. Last-seen patch states persist (farming varbits only sync in-region); readiness predicted at 20 min/stage; patches-ready infobox hidden at 0. Notifications: patch-ready + daily-reset via Notifier, per-category config toggles. M6 code-complete except the boat pack (parked until Sailing content is stable). **M7 complete (v0.5): goals (2d checklist-first; steps auto-complete via the graph, manual steps tick through unlock flags), supplies runway (rolling consumption log rates), whatnow (impact x urgency x fit over cross-module statics), clues (emote readiness from starter pack; STASH deferred — no API constants)**. Exit criterion unit-proven in GoalPlannerTest. Remaining stubs: skills, collectionlog, sync, dashboard-module (+ unregistered boat).
- `com.ironhub.ui.IronHubPanel` — CardLayout shell: `DashboardPanel` (frame 1b) ↔ `ModuleNavPanel` (frame 1c), placeholder data until M2. `com.ironhub.ui.components` — shared atoms (M1). `com.ironhub.ui.UiTokens` — all design tokens as constants (mirrors DESIGN-PACKAGE.md; keep them in sync, never hardcode styles in views). Offscreen renders for mockup side-by-sides: `./gradlew test` → `build/reports/*.png`.
- `com.ironhub.integrations.ShortestPathBridge` — PluginMessage soft integration, done in principle.
- `src/main/resources/data/*.json` — data packs (dailies, banked-xp, qol, gear-ladders, scenarios), schema-validated in CI; requirement strings use the graph's `parse()` form.
- Build: Gradle, Java 11, `net.runelite:client` latest.release. Dev launcher: `src/test/java/com/ironhub/IronHubPluginTest`.

## Implementation order

**DEVELOPMENT-PLAN.md is the authoritative sequencing** — milestones M0–M8 with exit criteria, release points, and a risk register. Summary (build the UI shell first since everything renders through it):

1. **UI foundation**: shared atoms from DESIGN-PACKAGE.md — list row (3 states incl. two-line locked), grid tile, ⊞/☰ toggle, segmented control, progress bars, search field, nav header with back arrow. Build as reusable components in `com.ironhub.ui.components`; every module view composes these.
2. **Panel shell**: dashboard (frame 1b) with placeholder data + module navigation (1c).
3. **Phase 1 data**: AccountState event ingestion (quests/varbits/bank/stats) + requirement graph model — DESIGN.md §2.2 "Requirement graph as shared infrastructure". Build this before any module logic; goals/quests/diaries/gear/clues all consume it.
4. Then modules in roadmap order, wiring each stub's panel tab as you go.

## Hard constraints (violating any of these = rework)

- Panel content exactly **225 px wide** (`UiTokens.PANEL_WIDTH`), vertical scroll only. Content that doesn't fit gets cut, tooltipped, or two-lined — never widened.
- Swing: flat fills, 1 px borders, radius ≤ 2 px, no gradients/shadows/animation.
- Status colors are semantic and never repurposed; accent `#DC8A00` is interaction-only and is NOT the amber status color (`#E0A23C`). See UiTokens.
- Navigation depth ≤ 2; back arrow + title on every non-home screen.
- Overlays ≤ 250×200 px, display-only. Infoboxes: 32×32 icon + short yellow text + tooltip.
- No client automation; Hub rules: no dependencies on other hub plugins (PluginMessage only), external HTTP opt-in.
- Heavy computation off the client thread; re-render on relevant events only, never per-tick.

## Conventions

- Modules stay self-contained: own package, own panel tab, own overlays/persistence; shared reads via `AccountState`, shared prerequisites via the requirement graph.
- Static game content (ladders, orders, methods, rates, clue items) is data-pack JSON under `src/main/resources/data/` — schema-validate additions; no gameplay constants in Java.
- Persistence profile-scoped: small flags via `ConfigManager`, bulk state as JSON under `RUNELITE_DIR/iron-hub/<profile>/`.
- Icons from the game sprite cache at runtime (the mockups' two-letter squares mark each sprite site). Status glyphs (✓ ● ○ !) are painted icons, not font emoji.
- ⊞/☰ view preference persists per module (profile-scoped config key).
- Code style follows RuneLite conventions (tabs, braces on new lines), Lombok available.

## Verification

- `./gradlew build` must pass; run `IronHubPluginTest` to smoke-test in a dev client.
- UI work: compare against the mockup frame ids in a side-by-side; tokens must come from `UiTokens`.
- Data-pack changes: validate JSON in CI (schema stubs to be added under `data/schemas/`).
