# Iron Hub

A central companion hub for standard Ironman accounts on RuneLite: gear ladders, quest ordering, skill milestones, banked XP, best-in-bank loadouts, farming runs, dailies, loot & supplies tracking, combat achievements, diaries, and more.

See [DESIGN.md](DESIGN.md) for the full design document, [design/DESIGN-PACKAGE.md](design/DESIGN-PACKAGE.md) for the UI handoff (mockups: [design/iron-hub-mockups.html](design/iron-hub-mockups.html)), and [CLAUDE.md](CLAUDE.md) for the implementation handoff.

## Development

Requires JDK 11.

```
./gradlew build
```

Run `IronHubPluginTest` (src/test) to launch a RuneLite client with the plugin loaded in developer mode.

## Structure

- `com.ironhub` — plugin entry point and config
- `com.ironhub.state` — `AccountState`, the normalized single source of truth
- `com.ironhub.modules` — one package per feature module
- `com.ironhub.ui` — side panel and shared UI components
- `src/main/resources/data` — bundled static data pack (JSON)
