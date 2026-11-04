# Persisted virtual NPC runtime

The initial runtime connects JSON definitions to independent virtual state and per-viewer packet presentations on Spigot and Paper **26.1.2, 26.2 and 26.3**. It creates no world-registered NPC body. Folia rendering and combat remain explicitly unavailable until region ownership and transfer are implemented. [Implementation evidence](implementation.md) identifies which artifact and scenarios were actually tested; source availability alone is not a release gate.

## Activation and editing

Create, clone and import produce `spawned: false`. `/animus spawn <id>` validates the world, type and profile-name constraints, then persists activation intent with the original document revision and hash. Actual delivery waits for a viewer in range whose client has received the chunk. `/animus despawn <id>` persists dormant intent and revokes combat authority immediately, before bounded packet cleanup finishes. A restart restores spawn intent, not arbitrary interrupted execution or combat health.

```text
/animus create guard minecraft:player world 0 64 0 town Town Guard
/animus spawn guard
/animus inspect guard
/animus select guard
/animus edit guard {"name":"Renamed Guard"}
/animus despawn guard
```

All management operations require `animus.admin`; `animus.status` remains available by default. An administrator's accepted interaction selects the NPC. Ordinary players can see and interact without receiving administrative selection messages. `spawn`, `despawn`, `inspect` and `delete` may omit the ID for a player with a selection. Console calls require the ID.

Live definitions use shared authoritative health and position. Valid appearance-only edits preserve that health and runtime position; an explicit definition position change updates the position. Despawn followed by a new activation creates fresh runtime state. Health and cooldowns are not written into hand-edited definitions.

Player profile names accept printable Unicode and spaces, with at most16 UTF-16 code units because of the native profile codec. Longer names and control characters are rejected explicitly; extended nameplates are pending. The initial non-player types are armor stand, horse, turtle, frog, llama, armadillo and ender dragon. Non-player nameplate rendering, inventories, poses and type-specific behavior other than the [dragon presentation](#ender-dragon-presentation) remain incomplete. Behaviors can show equipment with [`animus:set-equipment`](behaviors.md#actions); definitions cannot declare it yet.

Skin references identify content-hashed JSON documents in `skins/`. Reads use the bounded file worker and preserve texture/signature data. An invalid or missing live replacement retains the last valid active presentation and reports the field error. Accepted definitions also have durable recovery checkpoints: a known invalid source edit at restart retains its previous definition and reports the error without changing the edited file. Missing sources remain deleted. Referenced skin files are not included in that definition checkpoint. See [storage and recovery](storage.md).

## Work and visibility limits

Plugin settings are TOML; definitions, skins and generated data remain JSON. `[npcs]` settings and accepted ranges are:

| Setting | Default | Range |
| --- | ---: | ---: |
| `enabled` | `true` | Boolean |
| `viewDistance` |48|8–256 blocks|
| `maximumVisible` |64|1–128 per viewer|
| `maximumViewers` |256|1–1024|
| `updatesPerTick` |16|1–64|

The runtime bounds definition reconciliation, active bodies, packet operations, pending work and skin reads. Delivered-chunk checks use Paper's sent-chunk API or the exact Spigot native tracking/sender state. A loaded server chunk alone does not authorize presentation. The renderer never force-loads terrain. Dormant definitions do not create vanilla ticking bodies. This architecture is not a measured performance claim.

Viewer subscriptions account for world, actual chunk delivery, distance and session lifecycle. Re-entry obtains new native IDs; stale or foreign IDs cannot gain authority. Spawn/despawn acknowledgement ordering prevents reusing an NPC presentation while its old delivery is retiring. Use/attack interception validates ownership, reach, line of sight, rate and current virtual state, then emits cancellable `VirtualNpcInteractionEvent` with `preview=false`. Player attacks do not yet implement the full combat module.

## Initial movement and routes

Source and local tests cover the following commands. Their managed server results must be checked against the artifact recorded in [implementation evidence](implementation.md).

```text
/animus navigate guard 10 64 4 walk 2
/animus navigation guard status
/animus navigation guard stop
/animus look guard 12 65 4
```

Navigation uses the active NPC's current world and runtime position. Each leg permits at most32 blocks on each horizontal axis and8 vertically, speed1–5 blocks/second, and a bounded terrain capture of at most8192 voxels. Capture and live collision/support reads run on the Bukkit owner thread; path planning uses an immutable snapshot on one worker. Missing terrain suspends navigation without loading chunks. World changes, replacement, despawn, death, shutdown and conflicting authoritative movement cancel the route. Navigation status includes the current server position, route identity and arrival count.

Movement is conservative and kinematic: full-block walking, explicit step/jump/fall segments, swimming and flying have clearance checks on every committed step. Walking requires integer feet heights; partial-block support and vanilla ballistic physics are unavailable. Unknown block shapes are rejected, including their neighboring overhang area. Terrain checks use a central hull of at most 4 wide and 8 tall, so an ender dragon's wings and tail may overhang terrain. Busy work budgets may reduce movement rate. Folia movement remains unavailable.

For native waypoints, an NPC can use this JSON module:

```json
"modules": {
  "animus:navigation": {"enabled": true, "route": "town-patrol"}
}
```

The reusable route lives at `behaviors/routes/town-patrol.json`:

```json
{
  "schemaVersion": 1,
  "mode": "walk",
  "speed": 2,
  "loop": true,
  "waitTicks": 20,
  "points": [{"x": 10, "y": 64, "z": 4}, {"x": 16, "y": 64, "z": 4}]
}
```

Routes allow1–64 points in the NPC's world and at most64 KiB per reusable file. Mode, speed, loop, waitTicks and points may instead appear directly in the module; a referenced route keeps those fields in the route document. Reload replaces a valid changed route and reports an invalid replacement while retaining the active route in memory. Restart begins declared routes from their first point; it does not resume an interrupted route stack. A manual navigation or stop command overrides the current waypoint workflow until it is explicitly reapplied. Preserved import traits never execute automatically; a native navigation module on an imported definition also requires literal `reviewAccepted: true`.

## Mounted NPCs

An NPC rides another NPC through its definition:

```json
"modules": {
  "animus:mount": {"vehicle": "town-horse"}
}
```

`vehicle` is the other NPC's id. An imported Citizens `mountedon` reference applies the same way from `migration.references`, using the identity recorded at import. A native module takes priority, and `{"enabled": false}` turns an imported relation off without touching the preserved data.

Relations are worked out again whenever active definitions change, so they return after a restart. Each viewer's passenger list is built from what that viewer currently has, so relations return after a join, a world change or a chunk leaving and re-entering view. A passenger's authoritative position follows its vehicle's seat, taken from the exact native entity type, so hits land where the client draws it. A chain can be 8 deep and a vehicle can carry 16 passengers. A mounted NPC cannot navigate. When a relation ends, the NPC returns to the position in its definition.

A relation that cannot be honoured leaves the NPC standing at its own position. The definition is not changed. `/animus inspect <id>` shows `mount-problem=` with the exact field, `/animus status` counts them and the server log reports new ones.

| Problem | Effect |
|---|---|
| Vehicle missing or dormant, or an imported reference recorded as missing, ambiguous or invalid | Reported; activation still allowed. It attaches by itself once the vehicle becomes active |
| An imported vehicle id now belongs to a different NPC | Reported; never attached |
| Riding itself, a cycle, chain too deep or vehicle full | Refused by `/animus spawn` and by `/animus edit` of a spawned NPC. A hand-edited file that creates one is reported, and every member of a cycle stays unattached |

## Riding an NPC

A real player can ride an NPC whose definition opts in:

```json
"modules": {
  "animus:riding": {"enabled": true, "permission": "animus.ride.town", "speed": 4, "leash": 64, "lifetimeSeconds": 600}
}
```

| Field | Default | Range |
|---|---:|---:|
| `permission` | `animus.ride` (operators by default) | Lowercase permission node |
| `speed` | 4 | 0.5–8 blocks/second |
| `leash` | 64 | 4–256 blocks from where the ride began, horizontally |
| `lifetimeSeconds` | 600 | 30–3600 |

Imported definitions also need literal `"reviewAccepted": true`. An imported Citizens `controllable` trait stays preserved and does not turn this on.

The player mounts with an ordinary right-click on the NPC. It passes the same reach, line-of-sight and rate checks as any interaction, and a listener can stop it by cancelling `VirtualNpcInteractionEvent`. Sneak-right-click keeps its usual meaning, so an administrator can still select a rideable NPC. The NPC must stand on full-block ground. One player rides one NPC, one real player rides each NPC, and at most 8 players ride at once. Mounting cancels the NPC's route or waypoints until the definition is reapplied.

Press a movement key after mounting to start moving; a key already held while mounting does not count. Steering follows the player's look direction. Sprint and jump give no extra speed. The body steps up one full block and down at most three. Movement only happens on the server, through the same clearance check as navigation; the client's coordinates are never used. The check closes on any block shape it does not recognize, so plants, slabs, stairs and fences stop the ride next to them. Reaching the leash holds the NPC in place without ending the ride. The NPC stays where the ride ended. Flying and swimming control are unavailable.

Sneak dismounts. A ride also ends on excess input, a stale connection, expiry, losing permission, entering a real vehicle, sleeping, spectator mode, unavailable terrain, the NPC's despawn, death or definition change, and plugin shutdown. The player is placed beside the NPC, or at its feet when nothing beside it is clear, and is told the reason unless they chose to sneak off. Death, logout, an outside teleport and a world change end the ride without moving the player further. Rides do not survive a restart, and a player who logs out or crashes while riding is saved at the NPC's feet.

While riding, the player's server position is the NPC's feet, not its seat. Vanilla would otherwise treat the rider as flying and kick them. Block reach, other players' hits and anything else measured from the server position therefore use the ground, which is most noticeable high on a tall body. Plugins do not see `PlayerMoveEvent` for the ride's motion, so region plugins and movement-checking plugins may need an exemption.

```text
/animus ride status
/animus ride stop <player>
```

## Ender dragon presentation

A `minecraft:ender_dragon` definition is presented with packets only: no world entity, no boss bar and no dragon AI. Its model faces the opposite way from its rotation field, so Animus mirrors the rotation it sends and `yaw`, `/animus look` and route facing all mean the direction its head points. It sits with folded wings when resting on solid ground, flies in place when resting in the air and flaps while moving. Clients may play its normal wing and growl sounds.

Clients strike one of eight child parts rather than the dragon itself. Animus reserves those part IDs and measures each hit against part positions it works out from the authoritative position and facing, with a small allowance for client animation. The part a client claims to have hit is not trusted, and the broad 16-wide bounds do not count as a hit.

## Preview remote control

With the proof enabled, an administrator may use `/animus proof control <entity-id>`, `status` or `stop` for their own player-shaped preview. This is an unmounted remote-control experiment: the administrator remains a normal player. [Riding](#riding-an-npc) is a separate feature on persisted NPCs. No persisted route, command behavior or real combat is activated by this preview.

Control requires a fresh key transition after admission and native tick-end heartbeats. It bounds motion to0.1 block per eligible50 ms step, with an8-block anchor leash and30-second lease. Release stops movement; shift, excessive input, stale heartbeats, lifecycle changes or unavailable terrain revoke control. Sprint and jump keys do not grant extra speed or flight. There are at most8 controllers globally and one controlled body per viewer session. Exact live client assertions remain artifact-specific.

## Initial real-combat adapter

These explicit administrator commands operate on active persisted NPCs. Preview bodies cannot call this bridge.

```text
/animus combat status
/animus attack <npc-id> <loaded-real-target-uuid> [damage]
/animus target <npc-id> <loaded-zombie-uuid>
/animus target <npc-id> stop
```

An attack uses the supported Bukkit damage-source API and no real NPC attacker. `VirtualNpcDamageEvent` carries the virtual UUID and allows cancellation. Bukkit damage cancellation and immunity still determine observed damage. Reach, world, loaded chunks, obstruction and current state are revalidated after callbacks. The command reports applied damage and observed health; cancellation does not masquerade as a successful hit.

The only controlled vanilla type is currently an ordinary zombie. Its temporary lease suspends native goals, targeting and route, runs bounded pursuit of virtual coordinates, and restores its previous state when released where still safe. Autonomous native replanning is disabled during the lease. `VirtualNpcDamagedByMobEvent` allows cancellation of virtual damage. Defaults allow64 combat registrations,32 mob leases,4 lease updates and1 path plan per tick, with256 visited nodes per plan,20 ticks between plans/attacks and a600-tick lease lifetime. No helper entity is created by this adapter. Despawn, death, unload, cancellation of ownership and shutdown release leases.

Imported combat remains disabled. If `modules["animus:combat"]` is present, both `enabled` and `reviewAccepted` must be literal JSON `true`; strings are rejected by policy. Accepting these flags permits the manual bridge and does not translate Sentinel settings or start automatic targeting. When WorldGuard is installed, the bridge reports unavailable until a virtual-actor protection adapter exists.

This is not the full combat module: target rules, squads, guarding workflows, ranged weapons, armor policies, drops, respawn, NPC-versus-NPC combat and protection integrations remain open. Behaviors reach this same bridge through [`animus:combat-control`](behaviors.md#actions). Real damage and mob pursuit also require independent graphical and wider gameplay validation beyond the automated wire scenarios.
