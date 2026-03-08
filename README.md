# Create Train Sloth

Create Train Sloth is a NeoForge addon for **Minecraft 1.21.1** that extends Create trains with:

- Automatic line-based dispatch/headway control
- Alternative route fallback when preferred track is blocked

This project is an addon, not a Create fork.

## Target Matrix

- Minecraft: `1.21.1`
- Loader: `NeoForge`
- Required dependency: `Create` (`[6.0.4,)`, tested with `6.0.10-272`)

## What Works Right Now

### Automatic dispatch (Phase 3 first version)

- Lines can be created from Create schedule data automatically.
- Trains can be assigned automatically from schedule or manually with commands.
- Dispatch computes target headway from:
  - assigned train count
  - Create schedule prediction ticks (when available)
  - observed round-trip/travel+dwell telemetry (fallback)
  - minimum interval
  - safety buffer
- Dispatch uses **station hold** (schedule cooldown) as the primary control lever.
- Deterministic release order (sorted train UUIDs).
- Re-synchronization support via aggressiveness setting.

### Alternative routing (Phase 4 first version)

- When a train is waiting for a signal long enough, route reconsideration runs.
- Compares primary route preference vs dynamic Create path.
- Applies scoring using:
  - primary-route preference bonus
  - path distance/cost
  - occupancy awareness
  - conflict-complexity proxy
  - cooldown/hysteresis
- If no better safe option is found, train waits.

## Schedule UI Driven Setup (Create-native)

You can configure lines directly inside the Create schedule item UI:

1. Add normal `Destination` instructions in the schedule.
2. Optional: add a `Change Title` instruction with metadata.

Supported `Change Title` formats:

- `line:<line_id>`
- `cts:line=<line_id>;name=<display>;min_interval=200;min_dwell=100;dwell_extension=20;safety_buffer=40;resync=0.25;route_wait=60;route_cooldown=120`

Notes:

- Destination filters from the schedule become line station filters.
- `TimedWaitCondition` / `ScheduledDelay` values can be adopted as line minimum dwell baseline.
- If no `line` metadata is present, a stable line id is derived from destination filters.

## Commands

Root: `/trainsloth`

- `/trainsloth line create <line_id> [display_name]`
- `/trainsloth line delete <line_id>`
- `/trainsloth line list`
- `/trainsloth line station add <line_id> <station_name>`
- `/trainsloth line station remove <line_id> <station_name>`
- `/trainsloth line setting <line_id> <key> <value>`
- `/trainsloth assign <train_uuid> <line_id>`
- `/trainsloth unassign <train_uuid>`
- `/trainsloth debug train <train_uuid>`

Line setting keys:

- `min_interval`
- `target_interval`
- `min_dwell`
- `dwell_extension`
- `safety_buffer`
- `resync`
- `route_cooldown`
- `route_wait`

## Config

Common config file: `config/create_train_sloth-common.toml`

See example values in:

- `example-config/create_train_sloth-common.toml.example`

## Implementation Notes

### Verified Create integration points

The current implementation is intentionally tied to verified 1.21.1 classes:

- `Create.RAILWAYS` (`GlobalRailwayManager`)
- `Train`
- `Navigation`
- `ScheduleRuntime`
- `GlobalStation`

### TODO integration markers

Two TODO markers are left in:

- `CreateIntegrationHooks#onLevelTickPre`
- `CreateIntegrationHooks#onLevelTickPost`

These mark places where clean upstream Create extension points would be preferred over addon-side cooldown/repath orchestration.

## Limitations (current first version)

- Routing candidate enumeration is conservative (primary vs dynamic best path) to keep compatibility and avoid brittle hooks.
- No GUI yet; command/config driven.
- Debug overlay is server-side debug state + command output (no full client HUD yet).

## Roadmap

- Richer route candidate enumeration and merge/conflict graph analysis
- Platform assignment logic
- Priority lines
- Express/local dispatch policy
- Depot insertion/removal
- Timetable mode
- Optional client debug UI overlay

## Development

Compile:

```powershell
$env:JAVA_HOME='C:\Program Files\JetBrains\IntelliJ IDEA 2023.3.5\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat compileJava
```
