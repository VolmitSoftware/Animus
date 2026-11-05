# Persisted virtual NPC runtime

The initial runtime connects JSON definitions to independent virtual state and per-viewer packet presentations on Spigot and Paper **26.1.2, 26.2 and 26.3**, and on Fabric, NeoForge and Forge servers for the same versions; see [mod servers](mod-servers.md) for what differs there. It creates no world-registered NPC body. On Folia each NPC runs on the region that owns its chunk; see [persisted NPCs on Folia](folia-runtime.md) for what differs there, including how riding, mounted groups and combat follow region owners. [Implementation evidence](implementation.md) identifies which artifact and scenarios were actually tested; source availability alone is not a release gate.

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

### Hand edits apply live

Saving any data file applies it within about a second, without `/animus reload`: `config.toml`, NPCs (and the module settings inside them), behaviors, packs, routes, skins, shops, chest GUIs, menus, holograms, text animations, rigs, animations and schematics. Each change is checked with the same validators the editor uses. Online players with `animus.hotload.notify` (operators by default; permission level 2 on mod servers) hear a note block ding and see one line per changed key, at most 12 per file:

```text
[Animus]: Animus Hotloaded: [npcs/town/guard.json] [name] ["Guard" -> "Captain Guard"]
```

An invalid edit is refused with its field error and the server keeps the last valid version. Each NPC file applies on its own: a broken file keeps only its own last valid version and reports only its own error, and valid edits to other NPC files still apply, in the same save or later. `/animus reload` works the same way. Every `config.toml` key applies live. Each service takes the new value on its own thread by its next tick (on Folia, each region on its next region tick), and `/animus status` shows the main budgets in effect. A lowered cap never stops work in flight: visible NPCs, viewers, routes, fights, helpers, builds, behavior runs, queued actions and file operations above the new cap finish or retire normally, and new work waits until usage is under it. `viewDistance` is checked again on the next tick and NPCs now out of range despawn the normal way. Turning `[npcs]`, `[combat]`, `[builds]`, `[behaviors]`, triggers, `[rigs]`, modules, the editor listener or the relay off and on restarts only that part.

What still needs a restart, and the line ends in `[restart required]`:

- `npcs.enabled` on Folia: NPCs and viewers retire on their own regions in no fixed order, so the runtime cannot stop and start again safely in place.
- Adding, removing or replacing a Java module jar: module archives are read once per server start.
- Replacing TLS certificate files at the same paths: the settings do not change, so nothing notices; change a path instead. Animus's own writes (editor applies, imports, migrations, state) are never reported, and `state/`, `backups/`, `logs/` and `imports/` are not watched.

| `[hotload]` key | Default | Meaning |
|---|---|---|
| `enabled` | true | Watch the data files; when false only `config.toml` is watched |
| `sound` | true | Play the ding before each message |
| `maximumFiles` | 20000 | Files watched at most |
| `readsPerPoll` | 256 | Changed files read per 500 ms poll |
| `maximumFileBytes` | 4194304 | Larger files are refused |

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
| `tags` | At most 32 distinct labels of 1–32 lowercase letters, digits, `_` or `-`, starting with a letter or digit. The web editor uses them to find and group NPCs. The server stores them and does not act on them. `NpcDefinition.tags()` returns them. |

A wrong value names its exact field, for example `$.equipment.head.count: expected integer 1..99`. `/animus spawn` also checks every equipment item against the running server, so an unknown item or bad component text is refused as `$.equipment.<slot>.id` or `.components`.

Equipment is sent as an equipment packet after the body appears. The [`animus:set-equipment`](behaviors.md#actions) action replaces a slot until restart or the next definition change.

A player name that fits the profile (16 characters, no `&` codes) stays the normal player nameplate. Longer or coloured player names, every non-player name and all hologram lines appear in one text display that rides the body, with the hologram lines above the name. A player body whose name moved to the text display, or whose nameplate is hidden, gets a short placeholder profile name that a client-side team hides. The text display and the team exist only in packets. Text lines are evenly spaced; placeholders such as `%player%` are not interpreted.

`hologram` may also set `billboard` (`center`, `fixed`, `vertical` or `horizontal`; `center` by default), `shadowed`, `seeThrough`, `viewRange` (1–512 blocks; the client hides the text farther away), `interpolationDelay` and `interpolationDuration` (0–1200 ticks), and `lineHeight` (0–4 blocks). A line may be an object `{"text": "...", "marginTop": 0.25, "marginBottom": 0}` with margins of up to 4 blocks. With a line height or any margin, each line and the name get their own display, stacked from the bottom: each line sits its bottom margin plus the line height above the one below and adds its top margin, as Citizens holograms do (0.25 blocks when only margins are set).

A definition may also set `glowing` (an outline), `teamColor` (one of the sixteen chat colours; it colours the glow and the name), `baby` (the baby form of ageable, zombie, piglin and zoglin bodies), `usingItem` (the main-hand item is raised, as when blocking or drawing a bow), `listed` (a player body shows in the tab list) and `gravity: false` (the body hovers instead of falling). Each shows to every viewer from packets only.

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
| `updatesPerTick` |16|1–64 viewers updated per tick|
| `packetsPerTick` |32|1–1024 NPC packets per tick, shared by the viewers updated (per region on Folia)|
| `pendingSpawnsPerViewer` |8|1–64 spawns a viewer may have unanswered|
| `lookUpdatesPerTick` |32|1–1024 look-close NPCs that may turn per tick (per region on Folia)|

When a tick has less packet budget than work, updates give way in a fixed order:

1. Despawns go first. Nothing else is sent to a viewer while one of its despawns waits.
2. Moves, equipment, mounts and new spawns come next.
3. Cosmetic updates come last: look-close turns, rig segment sampling and bone updates, and timed hologram text refreshes such as text animations. They spend only the budget that every viewer's despawns, moves and spawns left over.

After a tick where despawns or moves had to wait, cosmetic updates pause for 20 ticks, and the pause starts again if the budget runs short again. Clip endings still fire `animation_finished`, and a hologram still redraws at once when it first shows or its NPC moves. `/animus status` shows `presentation=normal` or `presentation=strained` with a count of deferred cosmetic updates. On Folia each region keeps its own pause.

Lookups for players near a point (route sleep checks, look-close, combat, behavior audiences) use online players grouped by chunk, rebuilt at most once per tick on the owner thread or per Folia region tick, and visit only the chunks around the point.

The runtime bounds definition reconciliation, active bodies, packet operations, pending work and skin reads. Delivered-chunk checks use Paper's sent-chunk API or the exact Spigot native tracking/sender state. A loaded server chunk alone does not authorize presentation. The renderer never force-loads terrain. Dormant definitions do not create vanilla ticking bodies. This architecture is not a measured performance claim.

Viewer subscriptions account for world, actual chunk delivery, distance and session lifecycle. Re-entry obtains new native IDs; stale or foreign IDs cannot gain authority. Spawn/despawn acknowledgement ordering prevents reusing an NPC presentation while its old delivery is retiring. Use/attack interception validates ownership, reach, line of sight, rate and current virtual state, then emits cancellable `VirtualNpcInteractionEvent` with `preview=false`. An NPC with the [combat module](combat.md) takes player hits as combat damage.

## Initial movement and routes

Source and local tests cover the following commands. Their managed server results must be checked against the artifact recorded in [implementation evidence](implementation.md).

```text
/animus navigate guard 10 64 4 walk 2
/animus navigation guard status
/animus navigation guard stop
/animus look guard 12 65 4
```

Navigation uses the active NPC's current world and runtime position, at speed 1–10 blocks/second. A leg may be any length. Each plan reads a terrain capture of at most 8192 voxels: a leg within 32 blocks on each horizontal axis and 8 vertically that fits one capture is planned whole; a longer leg is planned in segments, each capture reaching from the NPC towards the goal, and the NPC walks each segment to the place closest to the goal before it plans the next. Climbs and descents of any height go segment by segment over stairs and slopes, or by swimming or flying when the route's mode allows it. A segment that gets no closer, or is blocked while walked, is planned again in a capture turned 45, 90 and then 135 degrees to either side. A turned plan that leads somewhere new is walked as a detour and later turns keep to that side, so the NPC follows a cliff, wall or shore until it can head for the goal again. After 12 plans in a row that get no closer the leg is blocked, and the server log names the NPC and the leg once. Segments take the same `[navigation]` planning turns and capture reads as any other plan. Capture and live collision/support reads run on the Bukkit owner thread; path planning uses an immutable snapshot on one worker. Missing terrain suspends navigation without loading chunks. World changes, replacement, despawn, death, shutdown and conflicting authoritative movement cancel the route. Navigation status includes the current server position, route identity and arrival count.

Movement is kinematic and uses real block collision shapes. Walking bodies stand on slabs, stairs, paths, snow layers and other partial surfaces, step and jump within their body's limits and fall at most 3 blocks. An NPC that is not following a route falls when a block under its feet is broken, burnt, blown up, pushed by a piston, taken by an entity, or changed by a build or its undo. It falls under gravity to the ground or water surface below and stays put over unloaded terrain. A walking NPC whose route leg is about to start while it stands in the air (its ground changed, or it was imported onto different terrain) first falls to the ground the same way, then plans the leg. Flying and aquatic bodies do not fall. Only these changes are seen: a block that another plugin removes without an event leaves an idle NPC where it is. Ordinary bodies swim at the water surface, aquatic bodies dive and flying bodies fly. Every committed step is checked for clearance, support and hazards. Vanilla ballistic physics are not modelled. Terrain checks use a central hull of at most 4 wide and 8 tall, so an ender dragon's wings and tail may overhang terrain. The check budget for one step grows with the hull, so large bodies keep moving. Busy work budgets may reduce movement rate; see [how many routes run](#how-many-routes-run). On Folia, capture and collision reads run on the NPC's region and an NPC is handed to the next region's owner as it crosses chunks; see [moving between chunks](folia-runtime.md#moving-between-chunks).

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

Mode, speed, order, loop, waitTicks, points, guides, linkDistance and wander may instead appear directly in the module; a referenced route keeps those fields in the route document, which is at most 64 KiB.

| Field | Values | Meaning |
| --- | --- | --- |
| `order` | `sequence` (default), `pingpong`, `random`, `wander` | How the next point is chosen. |
| `loop` | boolean, `sequence` only | `false` walks the points once and stops; `true` returns to the first point after the last. |
| `waitTicks` | 0–12000, default 20 | Wait before the first leg and after each arrival. |
| `points` | 1–256 of `{x, y, z}`; 0–64 for `wander` | Points in the NPC's world. Each may carry its own `waitTicks` (0–12000), the wait after arriving there instead of the route's. |
| `guides` | 0–1024 of `{x, y, z}`, `random` only | Helper points a path may pass through. They have no waits. |
| `linkDistance` | 1–256, default 32, `random` only | The longest link between two points or guides. |
| `wander` | `{"radius": 1–32, "height": 0–8}`, default 25 and 3, `wander` only | How far from a centre a wander target may be. |

Points and guides together allow at most 1280 coordinates. Walking routes need whole-block heights for points and guides. A field used with the wrong order is reported against that field.

- `sequence` walks 0, 1, …, n-1, then stops or, with `loop`, starts again at 0.
- `pingpong` walks 0, 1, …, n-1, n-2, …, 1, 0, 1, … forever without repeating an end point. With one point it walks there once and stops.
- `random` picks one of the points at random each time, never the point it just reached, and walks a shortest path to it over points and guides. Two of them are linked when they are at most `linkDistance` apart. When links do not reach the chosen point, the NPC follows links to the reachable point or guide closest to it and walks the rest as one long leg, so every point is visited when the terrain allows. From a position that is not a point or guide, the first step goes to a guide when the route has guides. Points and guides passed on the way get no wait; the chosen point applies its own wait or the route's.
- `wander` picks a centre at random each leg, a point or the NPC's definition position when there are no points, and walks to a random block within `radius` on x and z and within `height` blocks up or down of the centre. A centre's own `waitTicks` applies after reaching a target around it.

The `waypoint` trigger and `VirtualNpcWaypointEvent` report point `i` as `i` and guide `j` as the number of points plus `j`. A wander arrival reports the centre it used, or 0 without points.

A `sequence` or `pingpong` leg that terrain blocks after its tries stops the route. A `random` route instead waits the larger of its `waitTicks` and 100 ticks and picks a new destination, also when no path reaches the chosen point; a `wander` route waits the larger of its `waitTicks` and 20 ticks and picks a new target. Neither stops on its own. Missing terrain suspends any route as above. A behavior that waits for a reusable route refuses one that never finishes: a looping `sequence`, a `pingpong` over more than one point, and every `random` or `wander` route. Reload replaces a valid changed route and reports an invalid replacement while retaining the active route in memory. Restart begins declared routes from their first point; it does not resume an interrupted route stack. A manual navigation or stop command overrides the current waypoint workflow until it is explicitly reapplied. Preserved import traits never execute automatically; a native navigation module on an imported definition also requires literal `reviewAccepted: true`.

### How many routes run

There is no limit on how many NPCs follow a route. A patrol whose NPC has no player within `[npcs] viewDistance` sleeps: it holds no turn and no path plan, its status says `sleeping`, and it continues from where it stood when a player comes near (checked about once a second). A manual `/animus navigate` route and a route a behavior waits for never sleep. The routes that are awake take turns: each tick at most `routesPerTick` of them plan or take a step, and a route that missed its turn goes first on the next tick, so with more walking NPCs than the budget they all move, only slower. On Folia these budgets apply to each region tick, and the NPCs that waited longest go first.

`[navigation]` settings:

| Setting | Default | Range |
| --- | ---: | ---: |
| `routesPerTick` |128|1–4096 routes that plan or take a step per tick|
| `motionCellsPerTick` |16384|512–1048576 collision cells their steps check per tick|
| `planningStartsPerTick` |16|1–1024 new path plans per tick|
| `wakeChecksPerTick` |64|1–4096 sleeping routes that look for a nearby player per tick (not used on Folia, where players mark nearby NPCs)|
| `planningJobs` |32|1–256 path plans in progress at once|
| `captureReadsPerTick` |2048|256–65536 terrain reads those plans make per tick (on Folia, per region)|

`/animus status` shows `routes` (all routes), `routes-sleeping`, `routes-waiting` (waiting out a `waitTicks` pause or a retry) and `navigation-pressure`: `none`, or how many awake routes were left without a turn, or without a free planning job, in the last tick. When routes are left without a turn for a second, the console logs one warning with the budgets to raise, and one line when the shortage has been gone for 5 seconds.

### Looking at nearby players

An NPC with this module turns toward the nearest player in range while it is spawned:

```json
"modules": {
  "animus:look-close": {"range": 10, "lineOfSight": false, "whileNavigating": false, "npcs": false,
                        "randomLook": {"enabled": true, "delayTicks": 60, "yaw": [0, 360], "pitch": [-8, 8]}}
}
```

`enabled` is true when the object is present. `range` is 1–64 blocks, default 10. With `lineOfSight` the player must be visible from the NPC's eye through non-collidable blocks. With `whileNavigating` the NPC also looks while planning or moving along a route; it always looks while it waits at a point. It turns its yaw and pitch toward the player's eyes as `/animus look` does, prefers players who already see it, and keeps its facing when nobody is in range. With `npcs` it then looks at the nearest other NPC in range. With `randomLook` enabled, an NPC that has nobody to look at turns to a random yaw and pitch from its ranges every `delayTicks` (1–12000). An NPC that rides another NPC or carries a player does not look. Each NPC looks again at most every 5 ticks and a bounded number look per tick. The module never edits the definition, loads no chunks, and needs no `reviewAccepted` on imported definitions. `animus:look` still turns the NPC; look-close turns it back on its next look. On Folia only players held by the NPC's region count, a player within view distance counts as seeing it, and a sight line must stay within that region.

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

A saddled body is steered by the rider's own client while it rides, and that client ignores the server's positions for it. When the ride ends every viewer is sent the NPC's position again, so the rider sees it where the server holds it and can click it to ride again.

## Hiring an NPC

`modules["animus:hire"]` turns a combat NPC into a bodyguard players hire. A right-click opens a chest window that shows the NPC, its cost, how long a hire lasts and the time left, with Hire (or Renew), Dismiss and Cancel buttons. A sneaking administrator's click keeps its selection meaning.

```json
"animus:hire": {"items": [{"id": "minecraft:emerald", "count": 5}], "price": 0, "durationMinutes": 30, "permission": "animus.hire.guard"}
```

| Field | Meaning |
| --- | --- |
| `items` | Up to 6 items taken from the hirer's inventory. They work on every platform. |
| `price` | Money taken through the economy (Vault on Bukkit). With no economy a priced hire fails closed and takes nothing. Mod servers have no economy, so use `items` there. |
| `durationMinutes` | How long one hire lasts, 1-10080, default 60. A renewal adds this much to the time left. |
| `permission` | Needed to hire, when set. |
| `enabled` | `false` turns the module off. |

Payment is all or nothing: items and money are put back if any part fails or the NPC cannot guard now. A hire engages the NPC and makes it guard the hirer through its `animus:combat` module, so the NPC needs one. One player hires an NPC at a time; others see who it works for. Only the hirer can renew or dismiss. Contracts are kept in `state/hire/contracts.json` and survive restarts; when the hirer comes back online the NPC guards them again. When the time runs out the NPC stops guarding and the hirer is told.

## Rigs and animations

A definition with `"rig": {"id": "town:golem"}` shows the rig at `rigs/town/golem.json` on its body. Animations live at `animations/<namespace>/<path>.json`. Both formats are in [editor-design.md](editor-design.md) and `schema/rig-v1.schema.json` and `schema/animation-v1.schema.json`; sampling follows [editor-contracts.md](editor-contracts.md#rig-sampling). Player bodies keep their vanilla body; a rig with `"body": "shown"` adds parts to it, and `"body": "hidden"` (the default) makes the body invisible while it keeps the hitbox.

Each visible bone is a packet-only item, block or text display that rides the body in its passenger list, so it follows movement with no teleports. The server plans segments of the animation and sends each changed bone's transform once per segment with client interpolation. Segments start at `sampleTicks` and grow to 20 ticks while every bone moves in a straight line within 0.01 blocks and 0.5 degrees. A turn of the body plans a new segment at once. Bones are removed with the body, when the viewer leaves and at shutdown.

Rigs and animations load at startup and on `/animus reload` or `/animus rigs reload`. A broken or refused edit keeps the last valid document, a changed document needs a higher `revision`, and after a restart the last valid copy comes from `state/rig-catalog/`. `/animus rigs status` and `/animus rigs problems` show what was refused. `/animus rigs play <npc> <animation> [layer] [speed] [loop]` and `/animus rigs stop <npc> [layer] [fadeTicks]` test clips; behaviors use [`animus:play-animation` and `animus:stop-animation`](behaviors.md#actions) and the `animation_finished` trigger. Layer 0 plays the rig's `idle` clip, and `walk` while the NPC follows a route, unless a played clip uses it. Animation `events` are checked but not run yet.

`[rigs]` settings:

| Setting | Default | Range |
| --- | ---: | ---: |
| `enabled` | `true` | Boolean |
| `maximumBones` |512|1–4096 bones shown per viewer|
| `updatesPerTick` |64|1–512 bone transform packets per viewer per tick|
| `sampleTicks` |2|1–20 ticks, the shortest segment|
| `plansPerTick` |64|1–1024 NPCs that plan a segment per tick (per region on Folia)|

A viewer short of budget sends the newest transforms on later ticks with the interpolation that is left. Bones are cosmetic: hits use the body's hitbox.

### Resource pack layout

Bones that show vanilla items and blocks need no pack. A custom bone shows `minecraft:paper` with `item_model="animus:<rig path>/<bone>"`, where the rig path is the rig id after its colon (`town:golem` gives `animus:golem/head`). The pack sources live in the data folder under `pack/assets/`, laid out as a resource pack's `assets/`:

| Data-folder path | What it is |
| --- | --- |
| `pack/assets/animus/items/<rig path>/<bone>.json` | Item definition: `{"model": {"type": "minecraft:model", "model": "animus:item/<rig path>/<bone>"}}` |
| `pack/assets/animus/models/item/<rig path>/<bone>.json` | The bone's cubes as an item model, centred on the bone's pivot at (8, 8, 8) and shrunk to the -16..32 model bounds when needed; the bone's visual `scale` makes up the difference |
| `pack/assets/animus/textures/item/<rig path>/<texture>.png` | Embedded Blockbench textures |

The editor's Blockbench import and rig export write exactly these files; copy an exported `<rig>-server-files.zip` into the data folder. The server reads `models/<namespace>/<name>.bbmodel` too and converts each into rig `namespace:name` with the same rules as the editor: every group is a bone, a cube rotation that is not one axis in 22.5 degree steps up to 45 becomes a bone of its own, and the model turns 180 degrees to face +Z (pivots `(-x, y, -z) / 16`, rotations `(-x, y, -z)`; item displays turn their models back). `schema/fixtures/pack/` pins the result for both.

`/animus pack build` makes `pack/out/animus-rigs.zip`, a resource pack of `pack.mcmeta` plus every file under `pack/assets/` (as `assets/...`) and the files made from `models/`. A file already under `pack/assets/` wins over one made from a model. The zip holds stored entries in sorted path order, each dated 1980-01-01 00:00, so the same files give the same bytes; its SHA-1 is written beside it as `animus-rigs.zip.sha1`. `pack.mcmeta` is `{"pack": {"description": "Animus rig models", "min_format": 84, "max_format": 97}}`, covering 26.1.2 (84), 26.2 (88) and 26.3 (97). The same files are written as a folder in `pack/out/pack/` for merging into a server pack, and the rigs and animations made from models go to `pack/out/documents/`; copy them into `rigs/` and `animations/`. Only embedded PNG textures are read and nothing is fetched. Models over 16 MiB, more than 64 models, 64 bones, 4,096 cubes, 16 textures of 4 MiB or 1,024 pixels wide, 64 animations, 256 keyframes per channel or 16,384 source files are refused. Molang keyframe values play as 0, and catmullrom and bezier keyframes play as `ease_in_out`.

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

## NPC movement

A definition's `movement` object applies wherever the NPC walks a route:

```json
"movement": {"speed": 1.29, "avoidWater": false, "fly": false, "openDoors": true, "stepHeight": 1, "maximumFall": 1, "pathRange": 60,
             "arrivalMargin": 0.5, "stuck": {"afterTicks": 120, "teleport": true}}
```

`speed` (1–5 blocks per second) is used by a route that sets no speed of its own. `avoidWater: false` lets a walking route cross water at the surface; `fly` makes routes fly. With `openDoors` a walking route plans through closed wooden and copper doors, fence gates and upper trapdoors: the NPC opens each one for real just before it reaches it, after protection allows it (WorldGuard's `use` flag and any module protection hook; a failed check refuses), and closes it once it is clear, or when the route ends, is cancelled, sleeps or the NPC despawns. A refused door ends the leg as blocked. Iron doors and trapdoors stay closed, and a door the NPC did not open is never closed by it. On Folia the region that owns the door's chunk changes it. `stepHeight` (0–1.5) and `maximumFall` (0–16) replace the body's own climb and drop. Each plan reaches at most `pathRange` (1–256 blocks); a longer leg is walked in segments of that reach. A point within `arrivalMargin` (0–4 blocks) counts as reached.  A point within `arrivalMargin` (0–4 blocks) counts as reached. With `stuck`, a waypoint leg that has not moved closer for `afterTicks` (1–12000), or cannot be planned, either teleports the NPC to its point and goes on (`teleport: true`, Citizens' default stuck action) or ends as before. Every field is optional; without `movement` routes behave as they always have.

A definition's `poses` names look directions the behavior action `animus:pose` turns the NPC to:

```json
"poses": {"rest": {"yaw": 90, "pitch": 0}, "door": {"yaw": -180, "pitch": 10}}
```

Names are 1–32 lowercase letters, digits, `_` or `-`; at most 64 poses. `yaw` is -360–360 degrees and `pitch` -90–90, 0 by default. A pose only turns the head and body like `animus:look`; look-close turns the NPC back on its next look. Citizens' named poses convert here.
