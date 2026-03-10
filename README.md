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
- Compares primary route preference vs dynamic Create path and schedule-derived alternative station paths.
- Applies scoring using:
  - primary-route preference bonus
  - path distance/cost
  - occupancy awareness
  - conflict-complexity proxy
  - cooldown/hysteresis
- If no better safe option is found, train waits.
- Proactive platform assignment planner can pre-bias routing before hard signal stops.
- Interlocking override mode can be activated by placing a `Stellwerk Controller` block.

## Schedule UI Driven Setup (Create-native)

You can configure lines directly inside the Create schedule item UI:

1. Add normal `Destination` instructions in the schedule.
2. Optional: add a `Change Title` instruction with metadata.

Supported `Change Title` formats:

- `line:<line_id>`
- `cts:line=<line_id>;name=<display>;service=ICN;min_interval=200;min_dwell=100;dwell_extension=20;safety_buffer=40;resync=0.25;route_wait=60;route_cooldown=120`

Notes:

- Destination filters from the schedule become line station filters.
- `TimedWaitCondition` / `ScheduledDelay` values can be adopted as line minimum dwell baseline.
- If no `line` metadata is present, a stable line id is derived from destination filters.
- `service` / `class` metadata sets train service class (`S`, `IR`, `RE`, `IC`, `ICN`, `ICE`) and routing priority.

### Schedule UI Alternative Tracks (Main + Fallback)

You can define alternatives directly in the Create schedule editor:

1. Add a normal `Travel to Station` entry for the main target.
2. Add another entry and change its instruction type to `Travel to Alternative Station`.
3. Set one or more alternative stations using normal filters (wildcards supported).
4. Keep routing enabled:
   - `routing.enableScheduleAlternativeInstruction = true`
   - `routing.useScheduleDestinationAlternatives = true`
5. Optional fallback mode without explicit alt entries:
   - station name families like `Track Station`, `Track Station 2`, `Track Station 3`
   - `routing.enableNumericStationFamilyFallback = true`

Behavior:

- The selected destination remains the preferred/main target.
- Before departure, blocked main targets can switch to an explicit alternative entry immediately (no long signal wait required).
- If that path is blocked long enough, Train Sloth evaluates explicit alternative entries and can switch.
- Alternative entries are automatically skipped in normal schedule progression; they are used as fallback candidates.
- Cooldown + improvement threshold prevent route flapping.
- High-priority classes are favored in platform conflict resolution (e.g. `ICE` > `ICN` > `IC` > `RE` > `IR` > `S`).

### Interlocking Block (Routing Authority)

Train Sloth now provides a dedicated block:

- `create_train_sloth:interlocking_block` (`Stellwerk Controller`)

When `routing.requireInterlockingBlockForOverride = true`, alternative-routing override logic is only active if at least one loaded interlocking block is present in the same dimension.

### Station Hubs (Bahnhof mit mehreren Gleisen)

You can group multiple Create track stations into one logical station hub:

- define one hub id (e.g. `bern_hbf`)
- assign multiple platform stations (`Bern Hbf 1`, `Bern Hbf 2`, `Bern Hbf 3`)
- in Create schedule use `Travel to Station` with filter `bern_hbf` (or the hub display name)

Behavior:

- Train Sloth resolves the hub to all configured platform stations in the graph.
- Interlocking/platform planning picks one of the available platform tracks.
- The train can be routed directly to the selected platform station instead of waiting for one fixed station target.

## Commands

Root: `/trainsloth`

- `/trainsloth line create <line_id> [display_name]`
- `/trainsloth line delete <line_id>`
- `/trainsloth line list`
- `/trainsloth line station add <line_id> <station_name>`
- `/trainsloth line station remove <line_id> <station_name>`
- `/trainsloth line setting <line_id> <key> <value>`
- `/trainsloth hub create <hub_id> [display_name]`
- `/trainsloth hub delete <hub_id>`
- `/trainsloth hub list`
- `/trainsloth hub platform add <hub_id> <station_name>`
- `/trainsloth hub platform remove <hub_id> <station_name>`
- `/trainsloth assign <train_uuid> <line_id>`
- `/trainsloth unassign <train_uuid>`
- `/trainsloth service <train_uuid> <S|IR|RE|IC|ICN|ICE>`
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
