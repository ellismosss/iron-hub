# Iron Hub — Claude Code Handoff

RuneLite Plugin Hub plugin: an all-in-one progression companion for standard OSRS Ironman accounts. Design phase is complete; your job is implementation.

## Document map (read in this order)

1. **DESIGN.md** — full feature spec. §2 architecture, §3 the 21 feature modules, §7 roadmap phases.
2. **design/DESIGN-PACKAGE.md** — UI handoff from design: tokens, shared atoms, per-screen specs (frames 1a–3f), interaction rules. This is the UI source of truth.
3. **design/iron-hub-mockups.html** — open in a browser. High-fidelity mockups; frame id badges (1a–1d visual system/P0, 2a–2g module tabs, 3a–3f overlays/infoboxes) match the sections in DESIGN-PACKAGE.md.
4. DESIGN-HANDOVER.md — the original design brief; superseded by the design package where they differ.

## Current code state (scaffold only — no feature logic yet)

- `com.ironhub.IronHubPlugin` — entry point; a `@Provides Set<IronHubModule>` registers 13 of 21 modules (RuneLite ships Guice without the multibindings extension). TODOs mark the rest: quests, skills, QoL, loot/supplies, CAs, diaries, boat.
- `com.ironhub.IronHubConfig` — per-module enable toggles + opt-in integration settings.
- `com.ironhub.state.AccountState` — single-source-of-truth service, event ingestion stubbed.
- `com.ironhub.modules.*` — one package per module, each a stub implementing `IronHubModule` with a Javadoc pointing at its DESIGN.md section.
- `com.ironhub.ui.IronHubPanel` — CardLayout shell: `DashboardPanel` (frame 1b) ↔ `ModuleNavPanel` (frame 1c), placeholder data until M2. `com.ironhub.ui.components` — shared atoms (M1). `com.ironhub.ui.UiTokens` — all design tokens as constants (mirrors DESIGN-PACKAGE.md; keep them in sync, never hardcode styles in views). Offscreen renders for mockup side-by-sides: `./gradlew test` → `build/reports/*.png`.
- `com.ironhub.integrations.ShortestPathBridge` — PluginMessage soft integration, done in principle.
- `src/main/resources/data/dailies.json` — example of the data-pack pattern.
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
