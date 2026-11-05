# Persisted NPCs on Folia

On Folia 26.1.2, 26.2 and 26.3, saved NPC definitions run on the region that owns each NPC's chunk. The commands, definitions, limits and events are the same as in the [persisted runtime](runtime.md). This page describes what differs. Live checks pass on all three Folia builds: activation, triggers, behavior actions, a patrol crossing chunk owners, and a clean disable. On Folia 26.3 riding across chunk owners, mounted groups crossing chunk owners, a guard killing a mob, a player hitting a combat NPC, NPC-versus-NPC hits and dragon wing phases also pass live. Two truly separate regions could not be observed with a real client, because Folia merges adjacent loaded chunks into one region.

## What runs

| Feature | On Folia |
| --- | --- |
| Activation, despawn, edits, skins, `/animus inspect`, `/animus status` | Yes |
| Presentation to every player in range: definition equipment, text lines and long names, nameplate visibility, poses including sitting, armor stand parts, and runtime equipment and appearance changes | Yes, built by the same code as on Paper |
| Use and attack interactions, `VirtualNpcInteractionEvent`, administrator selection | Yes, when the player's region also owns the NPC |
| Triggers: spawn, despawn, interaction, waypoint, proximity, timers | Yes |
| Built-in behavior actions that touch the world or a player | Yes |
| Navigation: `/animus navigate`, `look`, stop, waypoint patrols, reusable routes, waiting behaviors | Yes |
| Riding an NPC (`animus:riding`) | Yes; see [riding](#riding) |
| Mounted NPCs (`animus:mount`) | Yes; see [mounted groups](#mounted-groups) |
| Combat module, `/animus combat`, the `animus:combat` action, hiring | Yes; see [combat](#combat) |
| `/animus attack`, `/animus target` | No: the manual adapter drives a real zombie's goals from the NPC's region while the zombie's AI runs on its own region |
| Builds (`/animus build`, `animus:build`) | No: a build site, its NPC team and its material container can belong to different regions, and a build has no region handoff yet. Both answer that building is unavailable on Folia |
| Ender dragon wing phases | Yes: the dragon's owner works out its phase each turn and every viewer sends it with the rest of the dragon's presentation |

## Ownership

Each NPC has one writer: a repeating task on the region that owns the chunk under its feet. That task reads terrain, plans routes, moves the NPC and runs queued work for it. Before each turn it checks that its region owns every chunk the NPC's body covers and that all of them are loaded. Nothing ticks every NPC: the global region thread only reconciles definitions, admits file reads, expires lost handoffs and asks known-loaded chunks to wake waiting NPCs.

An NPC whose chunk is not loaded waits. It starts on its region when that chunk loads, or when a player whose region owns the chunk comes within view distance. Animus never loads or generates a chunk to start, move or show an NPC, and only schedules work at a chunk that a load event reported or that the NPC's owner has just seen loaded. A waiting NPC is not in `/animus status` `active`; `/animus inspect` shows it as waiting.

Players are read only on their own scheduler. Each player's presentation runs on that player's entity scheduler and reads NPCs only through an immutable published copy, so a player in another region still sees NPCs whose chunks the client has received. An interaction counts only when the player's region also owns the NPC; otherwise it is rejected and counted in `/animus status` `rejected`. Behavior actions run on the NPC's region. A message, sound, particle, choice or command for a player that another region owns is handed to that player's scheduler and is not awaited, so a rejected player command is not reported. A console command runs on the global region thread. `nearby` audiences include only players the NPC's region owns. `has-permission` and look-at-player fail with `player-in-other-region` when the player is on another region.

## Moving between chunks

When an NPC moves into another chunk, its owner hands it over:

1. The old owner stops work that cannot move: an unfinished terrain capture or path plan is cancelled.
2. A new epoch is issued. Every task, callback and queued step from the old epoch becomes a no-op.
3. The region that owns the new chunk adopts the NPC once, after checking that it owns the whole body and that the chunk is loaded, then continues.

These move with the NPC: the route and its goal, waypoint progress, a behavior waiting for the route, the current step sequence, queued behavior actions and pending skin or route file results. The new owner plans the route again from where the NPC stands. Each crossing pauses the NPC for at least one tick.

If the new chunk is not known to be loaded, the new owner refuses, does not hold the chunks, or has not adopted the NPC within 5 seconds, the NPC waits where it stands. A waiting behavior is told the route is unavailable; any other route stays and continues when the NPC starts again. Queued actions for a waiting NPC are refused with the reason. A later adoption from the expired epoch does nothing. If a chunk unloads under an NPC, the same rule applies. When a region splits or merges, the NPC's task moves with its chunk and keeps its epoch.

A route leg whose terrain belongs to more than one region waits and retries after 2 seconds; a waiting behavior is told the route is unavailable instead. Folia keeps neighbouring loaded chunks in one region, so this normally only happens while regions merge.

## Mounted groups

A vehicle and every NPC that rides it have one owner: the region that owns the group's root vehicle. When a passenger and its vehicle are active on the same region, the passenger joins the vehicle's group. It stops its own task, and the root's task then ticks it right after the vehicle moved, so it always sits on the current seat. The group's footprint covers every member. When the root moves into another chunk, the whole group is handed over in one transfer with one new epoch, adopted once by the destination after it checks that it owns the whole footprint. When the group goes dormant, every passenger goes dormant with it and wakes on its own later.

A passenger whose vehicle another region owns steps onto the vehicle's seat, taken from the vehicle's published position. That hands it to the vehicle's region like any chunk crossing, and it joins the group there. A passenger whose vehicle is not active waits where it stands; `/animus inspect` shows `mounted-on=<vehicle> (not seated yet ...)`. A chain of riders joins the root of the chain. When a relation ends, or its vehicle is removed, the passenger leaves the group, runs its own task again under a new epoch, and returns to the position in its definition. Work queued for a passenger runs on the group's owner. Cycles, chains deeper than 8 and full vehicles are refused as on Paper. Each viewer builds passenger lists from what it shows, as on Paper.

## Riding

A ride runs on the NPC's owner and moves with the NPC on every handoff. The rider stands at the NPC's feet, so the region that owns the NPC also owns the rider, and the NPC's owner reads the rider and its steering input only while that is so. If the NPC's region does not hold the rider for 2 seconds, the ride ends. A right-click mounts only when the player's region also owns the NPC, as for every interaction. The rider's own scheduler sends the attach, and the ride starts on the NPC's owner when the client has it; the session's epoch makes a late attach for an earlier ride do nothing.

Dismount, admin stop and the end of a ride run on the NPC's owner, which first ends the lease, then hands the rest to the rider's scheduler: the detach reaches the rider's client before the dismount teleport. A logout, death, teleport or world change is seen on the player's scheduler and handed to the NPC's owner with the ride's epoch; the rider is not moved further. A rider that something else moved more than 3 blocks from the NPC's feet, such as Folia's same-region teleport that fires no event, is dismounted where it is. Steering reads terrain only in chunks the NPC's region holds; a step into terrain it does not hold ends the ride as unavailable terrain. At most 8 players ride at once on the whole server. Rides end without a dismount teleport when Animus stops on Folia.

## Combat

Every combat NPC runs its own combat engine on its owner, and the engine and its state move with the NPC on every handoff. An NPC's engine reads only:

- its own NPC's live state,
- other combat NPCs through the view their owners publish,
- players and mobs that its region owns,
- snapshots of real entities that their owner delivered with a message, such as the entity a helper arrow hit.

A target another region owns is not chosen. A melee or ranged hit on a real entity this region owns applies at once. A hit on one another region holds goes to that entity's own scheduler, which checks that it is still alive, in the same world and within reach of where the NPC stood, and asks protection again before it applies. A hit on another NPC goes to that NPC's owner as a message, which checks that the attacker is still near, applies its own armor and defense, and makes it fight back. Healing potions for other NPCs and squad alerts are messages the same way. A fight reaches the guards of either side and the NPCs that watch fights through their own owners' mailboxes, at most 64 per fight; dropped messages are counted in `/animus combat status` as `messages-dropped`.

Helper projectiles are spawned only in a chunk the NPC's region holds and carry their NPC. Their hits are seen on the projectile's region, which cancels the vanilla effect, removes the helper and hands the hit with an observation of the struck entity to the NPC's owner. A helper that flies into another region keeps its last known position until it lands or expires; it is removed on its own scheduler. Protection is asked on the thread that owns the hit's target.

A player's hit on an NPC counts only when the player's region also owns the NPC, as every interaction does. `/animus combat <npc> status` reads the NPC's published combat state; `engage`, `disengage`, `release`, `target` and `guard` run on the NPC's owner and log their result. A guard teleport to its charge, a knockback and a respawn move the NPC onto ground that the destination's own region checked, which hands it over like any chunk crossing.

The `[combat]` limits `brainsPerTick`, `targetScansPerTick`, `pathRequestsPerTick`, `squadAlertsPerTick` and `helperUpdatesPerTick` apply to each region tick. `maximumNpcs` does not apply on Folia: an NPC that sleeps holds no work, and the per-region limits bound the work of those that fight. `helpersPerNpc` applies to each NPC. When a region is short of brain turns, the combat NPCs that waited longest go first. Damage and death triggers fire as on Paper.

## Viewers

A player's presentation is replaced after a teleport, including Folia's same-region teleport that fires no event, a world change, death and respawn, and is removed at logout. The teleport check compares the connection and its teleport counter on every viewer tick, as the [stationary preview](folia-preview.md) does. A player who leaves the range, or whose client loses the chunk, stops seeing the NPC. An NPC that starts waiting or is removed disappears for every viewer on their next tick.

## Limits

The per-tick limits of the persisted runtime apply to each region tick rather than to the whole server:

| Work per region tick | Limit |
| --- | ---: |
| Queued NPC actions run | 64 (8 per NPC) |
| Route turns | `[navigation] routesPerTick` (128) |
| Live collision cells | `[navigation] motionCellsPerTick` (16384) |
| New path plans | `[navigation] planningStartsPerTick` (16) |
| Terrain capture units | `[navigation] captureReadsPerTick` (2048) |
| Viewer updates | `updatesPerTick`; with more viewers, they take turns |
| Packet operations | `[npcs] packetsPerTick` (32), shared equally by the viewers updating |
| Look-close turns | `[npcs] lookUpdatesPerTick` (32) |
| NPC starts | 16 |
| Combat brains, target scans, path requests, squad alerts and helper updates | the `[combat]` limits of the same names |

`maximumViewers`, `viewDistance`, 10,000 active NPCs, 8 concurrent route and appearance reads, 8 definition skin reads and 8 path plans are server-wide. `maximumVisible` applies to each viewer, and each NPC queues at most 32 actions. Work over a limit waits for the next tick.

A region runs its NPCs in the same order every tick, so route turns go to the NPCs that waited longest when a region is short of turns: no patrol waits for ever. Every player marks the NPCs within `viewDistance` once a second, also on other regions; a patrol that nobody marked for 3 seconds sleeps until a player comes near again. `/animus status` shows `routes-sleeping` and `navigation-pressure`, and the console logs once when regions start to run short of route turns and once when it ends.
