# Iron Hub

A central companion hub for standard Ironman accounts on RuneLite: gear ladders, quest ordering, skill milestones, banked XP, best-in-bank loadouts, farming runs, dailies, loot & supplies tracking, combat achievements, diaries, goal planning, and more.

See [DESIGN.md](DESIGN.md) for the full design document, [design/DESIGN-PACKAGE.md](design/DESIGN-PACKAGE.md) for the UI handoff (mockups: [design/iron-hub-mockups.html](design/iron-hub-mockups.html)), and [CLAUDE.md](CLAUDE.md) for the implementation handoff.

## Features

- **Dashboard** — composite account score (quests · diaries · combat achievements · QoL · collection log) with daily snapshots and trend, ranked "What now?" suggestions, active goal strip, alert chips, next best upgrades. Every component clicks through to its module.
- **Trackers** — quests (with quest points), achievement diaries (all 48 tiers), combat achievements (points and per-tier progress from the game's own thresholds), collection log adviser (every remaining activity ranked by expected time to its next slot at ironman rates, with a one-click Log Sync button on the log window and per-slot goal assignment), QoL unlock checklist.
- **Bank brain** — bank search from anywhere, banked XP by skill and method, best-in-bank loadout solver (beta) with bank-tag export and one-click export to the OSRS Wiki DPS calculator.
- **Loadout Lab** — exact-DPS best-in-slot sets from the gear you own, per enemy and combat style (the [Loadout Lab](https://github.com/ajkatz/runelite-loadout-lab) plugin by Andrew Katz, imported whole as a module — BSD-2-Clause, see `licenses/`).
- **Loadout planner** — live equipped gear + inventory view; detects your slayer task and the NPC you're fighting, fetches the wiki's recommended setups for it (per combat style, with the page's tips), picks the best gear you own per slot, lets you click any slot to swap items, saves your chosen setup per task/boss, and exports to the DPS calculator (target preselected) or the Inventory Setups plugin (clipboard import — no plugin dependency).
- **Daily loop** — full farming tracker (every patch category, bird houses and the farming contract predicted from the core Time Tracking plugin's data), guided farm runs (built-in herb/tree/fruit/hops templates plus custom runs, teleports auto-picked from what you own, missing-item warnings, Shortest Path routing stop to stop, a prominent end-run control, and a saved gear+inventory setup that reorganises the bank itself when you open it so you can re-stock at a glance), per-category ready infoboxes, dailies with reset-aware ticks, patch-ready and daily-reset notifications.
- **Intelligence** — goal planner whose steps auto-complete from account state, supplies runway (hours of stock at your usage rate), loot & supplies per kill, emote-clue readiness.
- **Death recovery** — what you carried, where you died, one Path button to the spot.

## External integrations — all opt-in, defaults off

Iron Hub makes **no external HTTP requests** unless you explicitly enable a toggle:

| Integration | Config toggle | Default | What is sent, and when |
|---|---|---|---|
| OSRS Wiki DPS calculator | `dpsCalcExport` | on (user-initiated only) | Only when you click **DPS calc** on the Loadout tab: your skill levels, the shown loadout's item ids and (when planning against an NPC) the target's id are POSTed to `tools.runescape.wiki/osrs-dps/shortlink` to create a share link, which opens in your browser. |
| OSRS Wiki gear strategies | — | user-initiated only | Only when you click **Wiki gear** on the Loadout tab: the strategy/slayer-task page for your detected task or target is fetched (read-only) from `oldschool.runescape.wiki` and parsed for its recommended-equipment tables. Nothing about your account is sent — the request contains only the page name. |
| Wise Old Man / TempleOSRS | `womSync` | **off** | On logout (rate-limited): your display name only, sent as an update ping to `api.wiseoldman.net` and `templeosrs.com` so your trackers refresh. |
| Discord webhook | `discordWebhookUrl` | **off** (empty) | Level milestones (multiples of 10, and 99) and completed goals are posted as plain text to the webhook URL you provide. |

The Shortest Path integration uses RuneLite's local `PluginMessage` event bus only — no network, no plugin dependency; Path buttons are inert if Shortest Path isn't installed.

All tracked state is stored locally, per profile, under `~/.runelite/iron-hub/<account-hash>/`.

## Licenses & credits

Iron Hub bundles the [Loadout Lab](https://github.com/ajkatz/runelite-loadout-lab) plugin by Andrew Katz as the Loadout Lab module (BSD-2-Clause), which itself vendors code derived from best-dps and Dude Where's My Stuff. The collection log module's time-to-next-slot ranking, drop-rate tables and sync mechanism are ported from [Log Adviser](https://github.com/SFranciscoSouza/LogAdviser) by SFranciscoSouza (BSD-2-Clause). The farming module vendors the decode/predict engine of RuneLite's core Time Tracking plugin (BSD-2-Clause, regenerated by `tools/gen_timetracking.py`; Iron Hub only reads the core plugin's tracked data, never writes it — the reminder-infobox behaviour follows [Time Tracking Reminder](https://github.com/queicherius/runelite-time-tracking-reminder) by David Reess, BSD-2-Clause), its farm-run stops and teleport tables come from [Easy Farming](https://github.com/Speaax/Farming-Helper) by Speaax and JThomasDevs (BSD-2-Clause), and the farm-run bank setup (opening the bank during a run reorganises it to your saved gear + inventory, via a hidden Bank Tag layout) ports the bank-layout behaviour of [Inventory Setups](https://github.com/dillydill123/inventory-setups) by dillydill123 (BSD-2-Clause). The original license texts are retained under [licenses/](licenses/).

## Development

Requires JDK 11 (the Gradle toolchain provisions one automatically).

```
./gradlew build      # compile + all tests (includes data-pack schema validation)
./gradlew runClient  # launch a dev client with the plugin loaded
```

UI changes render offscreen during tests for mockup side-by-sides: see `build/reports/*.png`.

## Structure

- `com.ironhub` — plugin entry point and config
- `com.ironhub.state` — `AccountState`, the normalized single source of truth (profile-scoped persistence)
- `com.ironhub.requirements` — the shared requirement graph (skills / quests / items / unlocks / kill counts)
- `com.ironhub.modules` — one package per feature module
- `com.ironhub.ui` — side panel and shared UI components (all styling via `UiTokens`)
- `src/main/resources/data` — bundled static data packs (JSON, schema-validated in CI)
