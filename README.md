# Iron Hub

A central companion hub for standard Ironman accounts on RuneLite: gear ladders, quest ordering, skill milestones, banked XP, best-in-bank loadouts, farming runs, dailies, loot & supplies tracking, combat achievements, diaries, goal planning, and more.

See [DESIGN.md](DESIGN.md) for the full design document, [design/DESIGN-PACKAGE.md](design/DESIGN-PACKAGE.md) for the UI handoff (mockups: [design/iron-hub-mockups.html](design/iron-hub-mockups.html)), and [CLAUDE.md](CLAUDE.md) for the implementation handoff.

## Features

- **Dashboard** — composite account score (quests · diaries · combat achievements · QoL · collection log) with daily snapshots and trend, ranked "What now?" suggestions, active goal strip, alert chips, next best upgrades. Every component clicks through to its module.
- **Trackers** — quests (quest-cape hero chart, Quests/Miniquests split with difficulty/A-Z/started sorts, one-click goal-planner tracking that expands the full prerequisite chain, and click-to-open in Quest Helper when it's installed), achievement diaries (all 48 tiers), combat achievements (points and per-tier progress from the game's own thresholds), collection log adviser (every remaining activity ranked by expected time to its next slot at ironman rates, with a one-click Log Sync button on the log window and per-slot goal assignment), QoL unlock checklist.
- **Bank brain** — bank search from anywhere, banked XP by skill and method, best-in-bank loadout solver (beta) with bank-tag export and one-click export to the OSRS Wiki DPS calculator.
- **Loadout Lab** — exact-DPS best-in-slot sets from the gear you own, per enemy and combat style (the [Loadout Lab](https://github.com/ajkatz/runelite-loadout-lab) plugin by Andrew Katz, imported whole as a module — BSD-2-Clause, see `licenses/`).
- **Loadout planner** — live equipped gear + inventory view; detects your slayer task and the NPC you're fighting, fetches the wiki's recommended setups for it (per combat style, with the page's tips), picks the best gear you own per slot, lets you click any slot to swap items, saves your chosen setup per task/boss, and exports to the DPS calculator (target preselected) or the Inventory Setups plugin (clipboard import — no plugin dependency).
- **Daily loop** — full farming tracker (every patch category, bird houses and the farming contract predicted from the core Time Tracking plugin's data), guided farm runs built from the official wiki's run guides — Herb, All trees (trees + fruit trees + calquats + celastrus), Hardwood, Bird houses, Allotment/flower/herb, Hop and bush, and Supercompost — plus your own custom runs; each run is trimmed to what's worth doing: locked patches (behind quests, diaries or Farming level) are dropped, patches still growing from a previous run are skipped, and stops you have no sapling to plant are removed; teleports auto-picked from what you own or set per patch; a stop advances once you've planted and composted it, not on arrival; missing-item warnings, Farming XP and herbs tracked, Shortest Path routing stop to stop, and a saved gear+inventory setup that reorganises the bank itself when you open it so you can re-stock at a glance), per-category ready infoboxes, patch-ready and daily-reset notifications.
- **Dailies** — every repeatable event from the wiki's Repeatable events page that an ironman can actually travel to (Zaff, the Flax keeper, Cromperty, Bert, Rantz, Miscellania, Robin, Thirus, Tears of Guthix and Lundail), read live from the game's own claim varbits rather than hand-ticked; one checklist picks which ones you care about, and a guided run walks you through just those you can do right now — already claimed, locked and deselected events are dropped, each stop advances when the game says you claimed it (not when you arrive), Shortest Path routes you stop to stop, and the overlay tells you what to bring, scaled to your diary tier (120 battlestaves needs 840,000 coins). Outstanding-count and run-progress infoboxes.
- **Slayer suite** — the current task read from the game's own varps (name, master, Konar area, kill progress), assigned monsters outlined in-game with the core plugin's own matching, per-task records (kills, Slayer XP, loot value, duration, completed vs skipped) with history, per-task preferred locations routed via Shortest Path, required/suggested item checklists with live ownership, bundled monster stats, per-task notes and saved gear setups that lay themselves out over the bank on demand, the full Slayer Rewards unlock catalog against live varbits, per-master block/skip lists compared against the game's own block slots with point-cost advice when you visit a master, Turael-skipping spots with teleports, and a floating task overlay that auto-hides its directions once you arrive.
- **Clues & STASH** — every emote clue step from the game's own table with per-tier doable counts against the items you actually own (variants included), first-missing-item lines, and a one-click goal that tracks unlocking a step in the Goal planner; every STASH unit tracked built/filled per tier (a built STASH's object only renders for its builder — detection ported from [STASH Tracker](https://github.com/Nearvaas/S.T.A.S.H-Toolkit) by Nearvaas, BSD-2-Clause), with a count of outfit sets you own but haven't stored and a manual filled toggle for units filled before Iron Hub.
- **Intelligence** — goal planner whose steps auto-complete from account state, supplies runway (hours of stock at your usage rate), loot & supplies per kill.
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

Iron Hub bundles the [Loadout Lab](https://github.com/ajkatz/runelite-loadout-lab) plugin by Andrew Katz as the Loadout Lab module (BSD-2-Clause), which itself vendors code derived from best-dps and Dude Where's My Stuff. The collection log module's time-to-next-slot ranking, drop-rate tables and sync mechanism are ported from [Log Adviser](https://github.com/SFranciscoSouza/LogAdviser) by SFranciscoSouza (BSD-2-Clause). The dailies module's claim detection is ported from RuneLite's core Daily Task Indicator plugin by Infinitay and Shaun Dreclin (BSD-2-Clause), keeping its varbit semantics byte-faithful. The farming module vendors the decode/predict engine of RuneLite's core Time Tracking plugin (BSD-2-Clause, regenerated by `tools/gen_timetracking.py`; Iron Hub only reads the core plugin's tracked data, never writes it — the reminder-infobox behaviour follows [Time Tracking Reminder](https://github.com/queicherius/runelite-time-tracking-reminder) by David Reess, BSD-2-Clause), its farm-run stops and teleport tables come from [Easy Farming](https://github.com/Speaax/Farming-Helper) by Speaax and JThomasDevs (BSD-2-Clause), and the farm-run bank setup (opening the bank during a run reorganises it to your saved gear + inventory, via a hidden Bank Tag layout) ports the bank-layout behaviour of [Inventory Setups](https://github.com/dillydill123/inventory-setups) by dillydill123 (BSD-2-Clause). The bank module's banked-XP item and activity tables (which items train what, xp per action, level gates) are ported from [Banked Experience](https://github.com/TheStonedTurtle/banked-experience) by TheStonedTurtle (BSD-2-Clause, regenerated by `tools/gen_banked_xp.py`). The Design lab's Mystic theme samples its palette, border geometry and Character Summary icons from the [Mystic resource pack](https://github.com/melkypie/resource-packs/tree/pack-mystic-pack) by Drunken Monk, for the [Resource Packs](https://github.com/melkypie/resource-packs) plugin by Melky (BSD-2-Clause). The slayer module's per-task location lists, access requirements and recommended-item tables are ported from [Slayer Simplified](https://github.com/Wulfic/slayer-simplified) by Wulfic, building on Lee's original Slayer Assistant (BSD-2-Clause); its Turael-skipping kill spots, teleports and notes come from [Turael Skipping](https://github.com/BrastaSauce/turael-skipping) by BrastaSauce and wesley-221 (BSD-2-Clause); its NPC target-name matching and finisher-item thresholds mirror RuneLite's core Slayer plugin (BSD-2-Clause); master assignment tables, monster stats and Slayer Rewards unlock data come from the [OSRS Wiki](https://oldschool.runescape.wiki) (regenerated by `tools/gen_slayer.py`). The original license texts are retained under [licenses/](licenses/).

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
