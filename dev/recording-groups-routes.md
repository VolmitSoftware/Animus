# Performance recordings, trait groups and random routes

Design for three features, written 2026-11-05 against master `eaa4564`. Integration branch `features-recording-groups`. The command layer is still the hand-rolled `AnimusCommands` with `Extra` subcommands; Director has not landed, so new subcommands are `Extra` implementations and get converted if Director lands before the merge.

Conventions carried over from the code: every document kind has a folder, a schema, a Java validator, last-valid retention on invalid edits, and hotload through `RuntimeCatalogs`; every new piece of per-tick work has a budget in `config.toml` that applies live and never drops work in flight; nothing loads a chunk; on Folia all NPC work runs on the owner of the NPC's chunk and all player work on the player's owner.

## 1. Performance recordings

### Document: `recordings/<id>.json`

`DocumentKind.RECORDING("recording", "recordings", APPLY_NPCS)`, hand-written `schema/recording-v1.schema.json`, validator `core/.../recording/RecordingDocument`. Id pattern `[a-z0-9][a-z0-9_-]{0,63}`, same as routes.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "market-loop",
  "recordedBy": "8667ba71-b85a-4004-af54-457a9734eed7",
  "world": "world",
  "origin": {"x": 100.5, "y": 64, "z": -20.5, "yaw": 90, "pitch": 0},
  "sampleTicks": 2,
  "durationTicks": 1180,
  "frames": [[0, 0, 0, 0, 90, 0, 1], [40, 3.2, 0, 0.1, 92, -3, 5]],
  "events": [
    {"tick": 60, "type": "swing"},
    {"tick": 80, "type": "equipment", "slot": "main_hand", "item": {"id": "minecraft:iron_sword"}},
    {"tick": 200, "type": "use", "x": 104, "y": 64, "z": -18, "block": "minecraft:oak_door"}
  ]
}
```

- `frames[i]` is `[tick, dx, dy, dz, yaw, pitch, flags]`: tick since start (strictly increasing, first 0, last `durationTicks`), position relative to `origin` rounded to 0.01 blocks, yaw and pitch rounded to 0.5 degrees, and `flags` a bitmask: 1 onGround, 2 sneaking, 4 sprinting, 8 swimming, 16 gliding, 32 jumping. Frames are the compressed form: arrays, no keys.
- `events` are objects sorted by `tick` then `type`. Types: `swing`, `equipment` (slot and item in the definition's item shape, or `"item": null` for an emptied slot), `use` (a door, fence gate or trapdoor the recorder opened or closed; nothing else is recorded).
- Limits: `durationTicks` at most `[recordings] maximumTicks`; at most `durationTicks / sampleTicks + 1` frames; at most 4096 events; serialized UTF-8 at most `maximumFileBytes`; the file is refused above `maximumFileBytes` and never read into memory beyond that bound (same pattern as `RouteDocuments.read`).
- Deterministic serialization: fixed key order as above, numbers written with `BigDecimal.stripTrailingZeros().toPlainString()`, no whitespace variance (one frame per line, events one per line). `RecordingDocument.serialize(parse(text))` reproduces the bytes of a document the server wrote, and a fixture under `schema/fixtures/recordings/` pins this on both sides.
- Simplification before save: a frame is dropped when the pose it holds lies within 0.02 blocks and 2 degrees of the straight interpolation between its neighbours and no flag changes, the same idea as rig segment planning. A frame is always kept at every event tick and at every flag change.

### Capture

`bukkit/.../recording/RecordingSessions` (one session per recording player, at most `maximumSessions`). A session runs on the recorder's owner thread: the main thread on Paper, `regions.repeat(player, ...)` on Folia. No session work touches a chunk the player's region does not own; it only reads the player.

- Every `sampleTicks` ticks it reads location, rotation, on-ground, sneaking, sprinting, swimming, gliding and a jump (vertical velocity positive while on ground last sample) directly from the player. `PlayerMoveEvent` is not used for sampling; it is noisy and not tick-aligned.
- `PlayerAnimationEvent` (arm swing) adds a `swing` event at the current session tick.
- Equipment is diffed every sample against the previous sample's six slots; a change becomes an `equipment` event. `PlayerItemHeldEvent` is covered by the diff.
- `PlayerInteractEvent` right-click on an `Openable` block adds a `use` event. Other interactions are ignored.
- The session ends and saves when the player runs `/animus record stop`, or when `maximumTicks` is reached. It ends and saves what it has when the player quits, dies, changes world, or is teleported more than 8 blocks in one sample (playback cannot teleport); the reason is reported. Fewer than 20 ticks of frames are discarded. `/animus record cancel` discards. A save whose serialized size exceeds `maximumFileBytes` is refused with the size, and the session stays until cancelled or stopped again after the limit is raised.
- Saving goes through the file lane as a `DocumentStore` transaction on `recordings/<id>.json` with the revision rules of other kinds, so hotload, editor undo and rollback all see it.

Commands (`Extra` "record", `animus.admin`, player only except `status`):

```text
/animus record start <id>
/animus record stop
/animus record cancel
/animus record status
/animus record ghost on|off
/animus record preview <id> [stop]
```

### Ghost

`bukkit/.../recording/GhostBodies`: a viewer-private player body shown only to one player, built on the same per-viewer `BodyTransport` path that `PacketProofService` and `PreviewBodies` use. It is packets only; it never registers an entity, never changes shared runtime state and never appears for anyone else.

- Skin: the recorder's own signed textures, read from the player's game profile (Paper `PlayerProfile` property `textures`; the reflective `NativeBindings` profile path on Spigot). A player without signed textures (offline mode) gets a plain body.
- Look: `glowing` with `teamColor = ghostColor` when `ghostGlow` is on, nameplate hidden, not listed in the tab list, fresh UUID per appearance, name = the recorder's name. Player bodies have no alpha, so "opacity" is the glow colour, not a transparency.
- While recording with ghost on: every `ghostIntervalTicks` the ghost appears at the frame `ghostStretchTicks` ago and replays that stretch at recorded speed, so it walks behind the recorder, then despawns. One ghost per recorder, so a replay still running when the next interval comes round is skipped.
- `/animus record preview <id>`: the ghost plays the saved recording once at its recorded origin for the requesting player only, then despawns; `stop` ends it early. At most `maximumPreviews` previews at once, one per player. A preview ends on quit, death, world change, teleport, loss of `animus.admin` or shutdown.
- Budget: a ghost sends at most one position sync and one head packet per tick and a spawn or despawn, inside the transport's per-viewer pending cap of 32 operations. Ghost ticks run on the recorder's owner thread, interleaved with the session.

### Playback

A recording is a third movement source of the `animus:navigation` module, exclusive with `route` and inline points:

```json
"modules": {"animus:navigation": {"enabled": true, "recording": "market-loop", "playback": "loop"}}
```

`playback` is `once`, `loop` (default) or `pingpong`. The behavior action `animus:play-recording` takes `recording`, `playback`, `wait` and `timeoutTicks` with the same semantics as `animus:navigate`, and `animus:stop-navigation` stops it. A new action `animus:resume-patrol` restarts the declared route or recording, so a behavior can stop for dialogue and resume afterwards. A route step may also play a recording segment (section 3).

Runtime (`core/.../recording/RecordingPlayback`, pure; driven from the existing route slot in `PersistedNpcService.workRoute` and `RegionActor.navigate`):

- Playback is a route in every sense the runtime already has: it takes a turn from `RouteScheduler` (Paper) or `RegionBudget.takeRoute` (Folia), sleeps under the same `viewDistance` and 3-second watch rules, uses `motionCellsPerTick` for its step check, raises `poseRevision`, and fires `awaitRoute` completion when it ends. Nothing new is scheduled.
- Each turn advances a cursor by one tick, interpolates the frame pose, and offers the step to `MotionValidator.check` like a route step. Flags become metadata: sneaking, swimming and gliding set the pose, sprinting sets the shared flag; `BodyTransport.look(viewer, body, look)` is a new metadata update sent per viewer so a pose change does not respawn the body. Events at the cursor tick run in order, at most 8 per tick, the rest next tick: `swing` through `BodyTransport.swing`, `equipment` through the runtime equipment override that `animus:set-equipment` already uses, `use` through `Doors.open/close` with the protection check, which ignores a refused door.
- The NPC first walks to the first frame with a normal planned leg (path-safe), then plays. When a step is refused (terrain changed, body would stand inside a block, no support) the playback switches to catch-up: a normal planned leg to the frame 40 ticks ahead, then the cursor jumps to that frame. If that goal is unreachable it skips another 40 ticks, five times at most, then the `movement.stuck` rules apply: `teleport: true` places the NPC at the frame and continues; otherwise status becomes `blocked`, the playback waits 100 ticks and tries catch-up again. A `once` playback that is blocked ends.
- Chunk not loaded at the next frame pauses the cursor with status `unavailable`, exactly as a route leg waits for terrain. Sleeping freezes the cursor.
- Interruption: combat and `/animus navigate` stop the playback as they stop a route; the cursor is kept in the runtime value, and `resumePatrol` (combat release, `animus:resume-patrol`) continues from it instead of from the start. A definition or recording change restarts from the first frame.
- Folia: the cursor, playback mode and direction travel with the NPC in the same handoff that carries route progress. The new owner re-plans the walk to the current frame from where the NPC stands, then continues. Transfer timeout and unloaded chunks behave as for routes.
- Per-player visibility is unchanged: the NPC body is shared and the pose is the same for every viewer; `maximumVisible` and eligibility rules apply as before. Only the ghost is per-player.
- Reads: a recording file is read through the same bounded owner-side read queue as routes (`MAX_ROUTE_READS = 8` shared), and a changed recording file hotloads the way a route does: `RECORDING` joins the `NPCS` reload set, the forced publish reconciles each NPC, and an NPC whose settings did not change re-reads its recording and restarts if the content changed. A missing or invalid recording is reported in `route-error` and the NPC keeps its last valid recording, or stands at its position when it never had one.

### `[recordings]` settings

| Key | Default | Range |
|---|---:|---|
| `enabled` | `true` | Boolean; off refuses `record` and `play-recording` as unavailable and stops ghosts |
| `sampleTicks` | 2 | 1-20 |
| `maximumTicks` | 12000 | 200-72000 |
| `maximumFileBytes` | 1048576 | 65536-4194304 |
| `maximumSessions` | 4 | 1-16 |
| `ghostEnabled` | `true` | Boolean |
| `ghostIntervalTicks` | 100 | 40-1200 |
| `ghostStretchTicks` | 100 | 20-600 |
| `ghostGlow` | `true` | Boolean |
| `ghostColor` | `aqua` | One of the sixteen chat colours |
| `maximumPreviews` | 8 | 1-32 |

All keys apply live; lowering a cap finishes sessions and previews already running.

## 2. Trait groups

### Document: `groups/<id>.json`

`DocumentKind.GROUP("group", "groups", APPLY_NPCS)`, hand-written `schema/group-v1.schema.json`, validator `core/.../npc/GroupDocument`. At most 256 KiB. No nesting: a group cannot reference another group, so there are no cycles.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "town-guards",
  "description": "Watch uniform, look-close and the patrol",
  "values": {
    "modules": {
      "animus:look-close": {"range": 12},
      "animus:combat": {"enabled": true, "profile": "guard"},
      "animus:navigation": {"enabled": true, "route": "town-patrol"}
    },
    "behaviors": ["town:greeting"],
    "equipment": {"head": {"id": "minecraft:iron_helmet"}},
    "nameplate": {"visible": true},
    "teamColor": "gold",
    "skins": ["<sha256>", "<sha256>"]
  }
}
```

`values` accepts every optional definition field: `modules`, `behaviors`, `tags`, `equipment`, `hologram`, `nameplate`, `pose`, `poses`, `movement`, `rig`, `armorStand`, `glowing`, `teamColor`, `baby`, `usingItem`, `listed`, `gravity`, `skin`, and `skins`, a pool of skin hashes from which each NPC takes one by a stable hash of its UUID. Identity and state fields (`schemaVersion`, `revision`, `uuid`, `id`, `group`, `name`, `type`, `spawned`, `position`, `owner`, `migration`, `groups`) are refused with `$.values.<field>: not allowed in a group`. The values are validated with the same field validators an NPC uses, so a bad item or range fails the group file, not every NPC.

### NPC reference and merge

An NPC lists groups in order: `"groups": ["town-guards", "harbor"]`, at most 16, each id matching the group pattern and naming an existing group document.

Effective definition = stored definition applied over the groups in list order:

1. Start from an empty object. For each group in order, then for the NPC's own fields, apply: objects merge key by key, recursively (`modules.animus:combat.enabled` from the NPC replaces only that key of the group's combat object); arrays replace whole, except `behaviors` and `tags`, which append in order without duplicates; scalars replace. Later always wins, and the NPC's own values win over every group.
2. `skins` resolves to one `skin` for the NPC (stable per UUID) unless the NPC sets `skin` itself. `skins` never appears in the effective definition.
3. The effective definition keeps the NPC's `groups` list and gains nothing else; it is validated as a whole with `NpcDefinition.validate`, so a combination that is invalid is refused with the same `$.field` message and the NPC keeps its last valid effective definition.

Provenance: the merger also returns a map from top-level and `modules.<id>` paths to the source (`npc` or a group id). There is no way to delete a group key from an NPC; set `{"enabled": false}` or an explicit value instead.

The merge happens in `NpcRepository.reload()`: the stored documents stay the drafts that `/animus edit` and the editor patch, and `snapshot()` returns effective definitions, so the runtime, behaviors, combat and the editor's server-side validation all see merged values without further change. A `GroupCatalog` (last-valid retention per file, like routes) is loaded first. `GROUP` joins the `NPCS` reload set, so saving a group file reloads the repository, every NPC that uses it gets a new definition identity, and the normal reconcile rebuilds those NPCs while keeping state for the same UUID. An NPC that references a missing group is refused at creation or edit (`$.groups[0]: unknown group`); if the group is deleted later the NPC keeps its last valid effective definition, `inspect` shows `group-problem=`, `/animus status` counts them and the log reports each once.

`/animus inspect <id>` prints, after the stored JSON and the runtime line, `Effective (groups: town-guards, harbor)` followed by the effective JSON, and one line `from: modules.animus:combat<-town-guards, equipment<-npc, ...`. The editor gets the same merge in Dart (`lib/logic/npc/group_merge.dart`) and the shared fixtures `schema/fixtures/groups/cases/*.json` (npc, groups, expected effective, expected provenance) are checked by Java and Dart tests; the Effective view and the bulk-assign dialog are built after `editor-revamp` lands, on top of that logic.

### Commands (Director group `groups`, `TraitGroupCommands`, `animus.admin`)

```text
/animus groups list [page=1]
/animus groups inspect <group-id>
/animus groups members <group-id>
/animus groups assign <group-id> [folder=] [tag=] [ids=]
/animus groups unassign <group-id> [folder=] [tag=] [ids=]
```

Exactly one of `folder=`, `tag=` or `ids=` is given; `@selected` inside `ids=` is the sender's selection, like every other NPC argument.

Assign appends the group to each matching NPC's `groups` (no duplicates), unassign removes it, each as one file-lane transaction with one rollback receipt, refused whole if any resulting definition is invalid, and reported as `assigned 12, unchanged 3`.

### Citizens import

`/animus import citizens preview factor-groups=true` (stage option; also `--factor-groups` on the converter CLI). With the flag, after conversion the preview finds every set of at least two converted NPCs whose groupable fields (`modules` except `citizens:review`, `behaviors`, `equipment`, `hologram`, `nameplate`, `pose`, `movement`, `glowing`, `teamColor`) are identical, writes one `groups/citizens-<8 hex of the set hash>.json` per set, removes those fields from the member NPCs and adds the group reference. The review summary lists each factored group with its member count; rollback removes the group files with the import. Without the flag nothing changes.

## 3. Shared routes with randomness

All of this lives in `WaypointSettings` and `WaypointProgress`, so it applies to inline module routes and to shared `routes/<id>.json` alike, and through group values to every NPC in a group.

| Field | Where | Values | Meaning |
|---|---|---|---|
| `seed` | route | `npc` (default), `random` | `npc` seeds the route's generator from the NPC UUID and route identity, so each NPC makes its own stable choices and the same choices after a restart; `random` mixes the clock as today |
| `startOffset` | route | `first` (default), `random` | `random` starts at a random point index and waits a random `0..waitTicks` before the first leg, so NPCs sharing a route do not stack |
| `waitTicks` | route, point | integer or `[min, max]` | A range draws a wait per arrival |
| `jitter` | route, point | 0-8 blocks, default 0 | The leg goal is offset by a random whole-block x/z offset within the radius; when that goal cannot be planned the exact point is used |
| `weight` | point | 1-1000, default 1, `random` only | Weighted choice of the next point |
| `next` | point | 1-8 of `{"to": index, "weight": 1-1000}`, `sequence` only | A junction: after this point, the next point is drawn from `next` by weight instead of `index + 1` |
| `recording` | point | `{"recording": id, "fromTick", "toTick", "playback"}` | A step that plays a recording segment: the NPC walks to the segment's first frame, plays it (`once` by default), then continues to the next point from the last frame. A point may be either a position or a recording step |

`loop` with `next` returns to point 0 only from a point that has no `next`. Validation names the field: `$.points[3].next[1].to: out of range`, `$.points[2].waitTicks: min above max`. The sum of weights is never zero because each weight is at least 1. The waypoint trigger reports a recording step as its point index. Random choices cost nothing per tick; jitter adds no planning.

## 4. Ownership, budgets, visibility and failure modes in one place

| Concern | Rule |
|---|---|
| Thread | Recording sessions and ghosts: the recorder's owner thread. Playback: the NPC's owner, inside its existing route turn. Group merge: the file lane during `reload()`. Random routes: the NPC's owner |
| Folia handoff | Playback cursor, mode and direction move with route progress; a ghost never crosses regions because it belongs to the player, whose region owns both |
| Chunks | Nothing loads a chunk; a playback whose next frame is in an unloaded chunk waits; a session only reads the player |
| Per-tick budgets | Playback uses the route turn and `motionCellsPerTick`; events 8 per NPC per tick; ghost 2 packets per tick per viewer; reads share the 8 concurrent route reads; merges happen only on reload |
| Memory | Sessions: `maximumSessions` x (`maximumTicks / sampleTicks` frames + 4096 events); recordings `maximumFileBytes` each; groups 256 KiB each, 16 per NPC |
| Visibility | Only the ghost is per-player. NPC playback poses are shared like route poses. `maximumVisible` and chunk delivery unchanged |
| Invalid files | Last valid recording, group or route is retained; the field error is reported through hotload and `inspect` |
| Missing references | Unknown group refused on create/edit, retained with `group-problem=` when deleted later; missing recording reported as `route-error`, NPC stays put |
| Blocked playback | Catch-up plan 40 ticks ahead, five skips, then `movement.stuck` rules; `blocked` retries after 100 ticks |
| Session loss | Quit, death, world change, long teleport save what exists (20 ticks minimum) and report the reason |
| Disabled | `recordings.enabled = false` refuses recording, previews and `play-recording`, and NPCs with a recording source stand at their position with `route-error=recordings disabled` |

## 5. Lanes and file ownership

| Lane | Scope | Owns |
|---|---|---|
| A groups | Group document, catalog, merge, repository integration, `groups` commands, inspect, import factoring, schema and fixtures, Dart merge, wiki page | `core/.../npc/Group*`, `NpcRepository`, `NpcDefinition` (`groups` field), `Administration` inspect, `CitizensImporter`/`CitizensDropIn` factoring, `schema/group-v1.schema.json`, `schema/fixtures/groups/`, `animus-web/lib/logic/npc/group_merge.dart` |
| B routes | Seed, start offset, wait ranges, jitter, weights, junctions, validation, both runtimes, generated schema, wiki | `WaypointSettings`, `WaypointProgress`, leg goal code in `PersistedNpcService` and `RegionActor`, `BehaviorSchemaGenerator.navigation()` |
| C1 recording core | Document, serializer, simplifier, playback cursor, `[recordings]` settings, schema and fixtures, kind registration | `core/.../recording/*`, `PluginSettings`, `schema/recording-v1.schema.json` |
| C2 capture and ghost | Sessions, listeners, ghost bodies, skin lookup, `record` commands, transport `look` | `bukkit/.../recording/*`, `BodyTransport.look`, `NativePacketAdapter` metadata, `AnimusPlugin` wiring |
| C3 playback runtime | Navigation module source, playback in both runtimes, resume, actions, wiki | `PersistedNpcService` and `RegionActor` route slot, `BuiltinCapabilities`, `BehaviorSchemaGenerator` |
| D route steps | Recording steps in routes, after B and C3 | `WaypointSettings` point parsing, leg code |

Shared registration files (`DocumentKind`, `HotloadTargets`, `EditorDocuments`, `RuntimeCatalogs`, `HotloadService`, `editor-v1.endpoints.json`, `editor-v1.schema.json`, `animus-web` `DocumentKind`, `locateDocument`, `kindPurpose`) are touched by A and C1 on different lines and merged by the lead.
