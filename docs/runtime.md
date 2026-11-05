# Persisted virtual NPC runtime

The initial runtime connects JSON definitions to independent virtual state and per-viewer packet presentations on Spigot and Paper **26.1.2, 26.2 and 26.3**, and on Fabric, NeoForge and Forge servers for the same versions; see [mod servers](mod-servers.md) for what differs there. It creates no world-registered NPC body. On Folia each NPC runs on the region that owns its chunk; see [persisted NPCs on Folia](folia-runtime.md) for what differs there, including riding, mounts and combat, which stay unavailable. [Implementation evidence](implementation.md) identifies which artifact and scenarios were actually tested; source availability alone is not a release gate.

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

Names accept printable Unicode and spaces, up to 256 UTF-16 code units, without control characters. The initial non-player types are armor stand, horse, turtle, frog, llama, armadillo and ender dragon. Equipment, text above the NPC, poses, armor stand parts, a stored inventory and an owner are declared in the definition; see [appearance](#appearance-text-and-inventory). Type-specific behavior other than the [dragon presentation](#ender-dragon-presentation) remains incomplete.

Skin references identify content-hashed JSON documents in `skins/`. Reads use the bounded file worker and preserve texture/signature data. An invalid or missing live replacement retains the last valid active presentation and reports the field error. Accepted definitions also have durable recovery checkpoints: a known invalid source edit at restart retains its previous definition and reports the error without changing the edited file. Missing sources remain deleted. Referenced skin files are not included in that definition checkpoint. See [storage and recovery](storage.md).

## Appearance, text and inventory

These definition fields are optional. Every viewer sees the same result, and it comes back from the definition after a restart, a rejoin or a chunk reload. A change takes effect through `/animus edit` or a reload, which gives viewers a fresh body.

```json
{
  "name": "Captain of the Northern Watch",
  "type": "minecraft:player",
  "equipment": {
    "main_hand": {"id": "minecraft:iron_sword"},
    "head": {"id": "minecraft:iron_helmet", "components": "[damage=12]"},
    "off_hand": {"id": "minecraft:shield"}
  },
  "hologram": {"lines": ["&6Quest giver", "Right-click to talk"]},
  "nameplate": {"visible": true},
  "pose": "sitting",
  "inventory": {"size": 36, "slots": {"0": {"id": "minecraft:bread", "count": 16}}},
  "owner": "8667ba71-b85a-4004-af54-457a9734eed7"
}
```

| Field | Accepted values |
|---|---|
| `equipment` | Slots `main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`, `body`, `saddle`. Each holds one item. |
| item | `id` (`namespace:item`), optional `count` (1–99, default 1), optional `components`: the text that follows the item id in `/give`, such as `[enchantment_glint_override=true]`, at most 4096 characters. `imported` holds source item data that has no equivalent; it is kept and never read. |
| `hologram.lines` | At most 16 lines of at most 256 characters each. `&` colour and format codes apply. |
| `nameplate.visible` | `false` hides the name. Default `true`. |
| `pose` | `standing` (default). Player bodies also accept `sneaking`, `sleeping`, `swimming` and `sitting`. |
| `armorStand` | Armor stand bodies only: `small`, `arms`, `basePlate` (default `true`), `invisible`, and `head`, `body`, `leftArm`, `rightArm`, `leftLeg`, `rightLeg` as `[x, y, z]` degrees within ±360. Parts without angles keep the client's default pose. |
| `inventory` | `size` is a multiple of 9 from 9 to 72; `slots` maps slot numbers below `size` to items. |
| `owner` | The owning player's UUID. |

A wrong value names its exact field, for example `$.equipment.head.count: expected integer 1..99`. `/animus spawn` also checks every equipment item against the running server, so an unknown item or bad component text is refused as `$.equipment.<slot>.id` or `.components`.

Equipment is sent as an equipment packet after the body appears. The [`animus:set-equipment`](behaviors.md#actions) action replaces a slot until restart or the next definition change.

A player name that fits the profile (16 characters, no `&` codes) stays the normal player nameplate. Longer or coloured player names, every non-player name and all hologram lines appear in one text display that rides the body, with the hologram lines above the name. A player body whose name moved to the text display, or whose nameplate is hidden, gets a short placeholder profile name that a client-side team hides. The text display and the team exist only in packets. Text lines are evenly spaced; per-line spacing, view ranges and placeholders such as `%player%` are not interpreted.

Sneaking, sleeping and swimming set the body's pose. A sitting player rides a hidden, packet-only armor stand at its position, so its body appears lower than it stands; hits are still measured against the standing body. A sitting NPC cannot also ride another NPC.

The inventory is stored data. Nothing renders it and no item moves in or out of it at run time. Modules read it from the definition snapshot, and Java code can use `NpcInventory.of(definition)`. The owner is exposed by `NpcDefinition.owner()`, `/animus inspect` and the definition snapshot, for plugins and modules to use in their own permission rules. Animus management commands still require `animus.admin`.

`/animus inspect` shows the pose, the number of equipped slots and text lines, the owner, and `presentation=unavailable` on a server build without the needed signatures. Without them NPCs still appear, with their plain body and profile name.

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

Movement is kinematic and uses real block collision shapes. Walking bodies stand on slabs, stairs, paths, snow layers and other partial surfaces, step and jump within their body's limits and fall at most 3 blocks. An NPC that is not following a route falls when a block under its feet is broken, burnt, blown up, pushed by a piston, taken by an entity, or changed by a build or its undo. It falls under gravity to the ground or water surface below and stays put over unloaded terrain. Flying and aquatic bodies do not fall. Only these changes are seen: a block that another plugin removes without an event leaves an idle NPC where it is. Ordinary bodies swim at the water surface, aquatic bodies dive and flying bodies fly. Every committed step is checked for clearance, support and hazards. Vanilla ballistic physics are not modelled. Terrain checks use a central hull of at most 4 wide and 8 tall, so an ender dragon's wings and tail may overhang terrain. The check budget for one step grows with the hull, so large bodies keep moving. Busy work budgets may reduce movement rate. On Folia, capture and collision reads run on the NPC's region and an NPC is handed to the next region's owner as it crosses chunks; see [moving between chunks](folia-runtime.md#moving-between-chunks).

For native waypoints, an NPC can use this JSON module:

```json
"modules": {
  "animus:navigation": {"enabled": true, "route": "town-patrol"}
}
```

The reusable route lives at `routes/town-patrol.json` in the Animus data folder, next to `npcs/` and `behaviors/`. Routes are not behaviors, so behavior validation never reads them:

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

The player mounts with an ordinary right-click on the NPC. It passes the same reach, line-of-sight and rate checks as any interaction, and a listener can stop it by cancelling `VirtualNpcInteractionEvent`. Sneak-right-click keeps its usual meaning, so an administrator can still select a rideable NPC. The NPC must have room for its body and rider where it stands: on ground, at a water surface it can swim, or in open air when it flies. One player rides one NPC, one real player rides each NPC, and at most 8 players ride at once. Mounting cancels the NPC's route or waypoints until the definition is reapplied.

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

Imported combat remains disabled. If `modules["animus:combat"]` is present, both `enabled` and `reviewAccepted` must be literal JSON `true`; strings are rejected by policy. Accepting these flags permits the manual bridge and also starts the combat module with the imported settings. Protection decisions, including WorldGuard, come from the combat module's protection adapter.

This adapter is separate from the [combat module](combat.md), which handles target rules, squads, guarding, arrows, armor, drops, respawn, NPC-versus-NPC combat and WorldGuard protection. The manual hit asks the same protection adapter; the zombie adapter's hits also respect the NPC's combat defense. Real damage and mob pursuit still require graphical and wider gameplay validation.
