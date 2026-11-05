# Animus metrics for React

Animus measures its own network traffic and server-thread time all the time, at low cost. Operators read the numbers with `/animus debug`. When React is installed, Animus also publishes them to React. Every value describes the server it runs on.

Byte counts are estimates. Animus counts packets by kind and multiplies each count by a typical size for that kind. It never encodes a packet to measure it. Per-NPC time is sampled on every 4th tick and scaled up.

## How Animus reaches React

Animus has no compile or runtime dependency on React.

- `plugin.yml` lists React under `softdepend`, so React enables first when it is installed.
- On enable, and again when React enables later, Animus loads `art.arcane.react.api.metric.ReactMetricSource`, `ReactMetric`, `ReactMetricKind` and `ReactMetrics` through React's class loader. It implements `ReactMetricSource` with a `java.lang.reflect.Proxy` and registers that proxy with Bukkit's `ServicesManager` under React's own interface class.
- `sourceId()` returns `animus`. `metrics()` returns the 24 declarations below. React accepts at most 24 metrics per source.
- Once a second, after Animus rolls its counters, it calls `ReactMetrics.publish("animus", key, value)` for every key while `ReactMetrics.accepting("animus")` is true. React shows a value as unavailable when it is older than 15 seconds.
- When Animus disables, it unregisters the proxy.

React turns each key into a sampler ID by replacing every non-alphanumeric character with `-`. For example, `animus.npcs.active` becomes the sampler `animus-npcs-active` and the placeholder `%react_sampler.animus-npcs-active%`.

React carries single server-wide numbers only. Per-chunk NPC density, per-player bandwidth and per-NPC cost are available in `/animus debug` only.

## Metrics

Rates and times are rolling averages over the last 10 seconds. Counts and pressures are taken at the last roll, once a second.

| Key | React kind | Unit | Meaning |
| --- | --- | --- | --- |
| `animus.cost.presentation_ms` | MILLIS | ms | Milliseconds per second of server-thread time spent on viewer updates, spawns, moves, looks and passengers |
| `animus.cost.navigation_ms` | MILLIS | ms | Milliseconds per second on route turns, motion, footing and riders |
| `animus.cost.combat_ms` | MILLIS | ms | Milliseconds per second on combat brains |
| `animus.cost.behaviors_ms` | MILLIS | ms | Milliseconds per second on behavior triggers, behavior ticks and hire desks |
| `animus.cost.rigs_ms` | MILLIS | ms | Milliseconds per second on rig playback and animation planning |
| `animus.cost.gui_ms` | MILLIS | ms | Milliseconds per second on shop screens, holograms and holographic menus |
| `animus.cost.hotload_ms` | MILLIS | ms | Milliseconds per second on catalog reloads after a change. This work runs on the file thread, not the tick thread. |
| `animus.cost.editor_ms` | MILLIS | ms | Milliseconds per second on editor API requests. This work runs on the editor's threads, not the tick thread. |
| `animus.pressure.navigation` | PERCENT | % | 0 to 100: the share of the per-tick motion budget used over the last second. It is 100 while routes wait for a turn. |
| `animus.pressure.presentation` | PERCENT | % | 0 to 100: the share of the per-tick packet budget used over the last second |
| `animus.pressure.combat` | PERCENT | % | 0 to 100: combat slots in use out of `[combat] maximumNpcs`. It is 100 while any NPC waits for a slot. |
| `animus.net.bytes_per_second` | BYTES | B | Estimated bytes Animus sent per second |
| `animus.net.packets_per_second` | RATE | pkt | Packets Animus sent per second |
| `animus.net.player_peak.bytes_per_second` | BYTES | B | Estimated bytes per second sent to the busiest player, last second |
| `animus.net.kind.spawn.bytes_per_second` | BYTES | B | Body spawns: add-entity, player info, initial metadata, team, seat and nameplate packets |
| `animus.net.kind.despawn.bytes_per_second` | BYTES | B | Remove-entities, player-info removal and team cleanup |
| `animus.net.kind.move.bytes_per_second` | BYTES | B | Position sync, seat moves and head rotation |
| `animus.net.kind.metadata.bytes_per_second` | BYTES | B | Pose, swing, dragon-phase, equipment and passenger-roster updates |
| `animus.net.kind.hologram.bytes_per_second` | BYTES | B | Display spawns, updates and removals for holograms and holographic menus |
| `animus.net.kind.rig_bones.bytes_per_second` | BYTES | B | Rig bone spawns, transforms and removals |
| `animus.net.kind.gui_window.bytes_per_second` | BYTES | B | Shop and menu window opens |
| `animus.npcs.active` | GAUGE | | NPCs with a live body |
| `animus.npcs.sleeping` | GAUGE | | Patrols that sleep because no player is near |
| `animus.npcs.viewers` | GAUGE | | Players that watch NPCs |

On Folia:

- Every `animus.cost.*` value is summed across all regions. It is not the load of one region thread.
- `animus.pressure.navigation` and `animus.pressure.presentation` are 100 while any region waits for budget, and 0 otherwise.
- `animus.pressure.combat` is 0, because each region keeps its own combat queue.

## Typical packet sizes

| Kind | Typical bytes per packet |
| --- | --- |
| spawn | 96 |
| despawn | 16 |
| move | 32 |
| metadata | 28 |
| equipment | 48 |
| passengers | 16 |
| hologram | 72 |
| rig_bones | 40 |
| gui_window | 256 |
