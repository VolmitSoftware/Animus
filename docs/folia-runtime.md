# Persisted NPCs on Folia

On Folia 26.1.2, 26.2 and 26.3, saved NPC definitions run on the region that owns each NPC's chunk. The commands, definitions, limits and events are the same as in the [persisted runtime](runtime.md). This page describes what differs. Live checks pass on all three Folia builds: activation, triggers, behavior actions, a patrol crossing chunk owners, riding, mounts and combat reported unavailable, and a clean disable. Two truly separate regions could not be observed with a real client, because Folia merges adjacent loaded chunks into one region.

## What runs

| Feature | On Folia |
| --- | --- |
| Activation, despawn, edits, skins, `/animus inspect`, `/animus status` | Yes |
| Presentation to every player in range: definition equipment, text lines and long names, nameplate visibility, poses including sitting, armor stand parts, and runtime equipment and appearance changes | Yes, built by the same code as on Paper |
| Use and attack interactions, `VirtualNpcInteractionEvent`, administrator selection | Yes, when the player's region also owns the NPC |
| Triggers: spawn, despawn, interaction, waypoint, proximity, timers | Yes |
| Built-in behavior actions that touch the world or a player | Yes, except `animus:combat` |
| Navigation: `/animus navigate`, `look`, stop, waypoint patrols, reusable routes, waiting behaviors | Yes |
| Riding an NPC (`animus:riding`) | No: the rider and the NPC have separate owners and a ride has no region handoff yet |
| Mounted NPCs (`animus:mount`) | No: a vehicle and its passengers have separate owners and seat coupling has no region handoff yet. The NPC stands at its own position and `/animus inspect` shows `mount=unavailable on Folia` |
| Combat module, `/animus attack`, `/animus target` | No: combat damages and retargets real players and mobs that other regions own |
| Builds (`/animus build`, `animus:build`) | No: a build site, its NPC team and its material container can belong to different regions, and a build has no region handoff yet. Both answer that building is unavailable on Folia |
| Ender dragon wing phases | Not sent: dragons always show the hovering pose |

Damage and death triggers need combat, so they do not fire on Folia.

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

`maximumViewers`, `viewDistance`, 10,000 active NPCs, 8 concurrent route and appearance reads, 8 definition skin reads and 8 path plans are server-wide. `maximumVisible` applies to each viewer, and each NPC queues at most 32 actions. Work over a limit waits for the next tick.

A region runs its NPCs in the same order every tick, so route turns go to the NPCs that waited longest when a region is short of turns: no patrol waits for ever. Every player marks the NPCs within `viewDistance` once a second, also on other regions; a patrol that nobody marked for 3 seconds sleeps until a player comes near again. `/animus status` shows `routes-sleeping` and `navigation-pressure`, and the console logs once when regions start to run short of route turns and once when it ends.
