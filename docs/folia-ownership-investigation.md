# Folia ownership investigation

Investigated on 2026-11-04 against the exact cached Folia API and expanded server jars, with Java 25 `javap -p -c`. The general runtime now follows this design; [persisted NPCs on Folia](folia-runtime.md) describes what was built and what differs from the proposal below. No server was started and no production source changed during this investigation. Existing Folia evidence covers startup, file commands, restart and explicit runtime-disable diagnostics only.

## Verified APIs and current boundaries

The scheduler method signatures below are identical in the three inspected API jars. Their presence establishes binding feasibility, not correctness of Animus execution on a region thread.

| Target | Build/channel | API SHA-256 |
|---|---|---|
| 26.1.2 | 8 / STABLE | `f10a91acaf5e3aa3f2aa27f8f23b932281ff71fce13ee6c9c1b53999ebe93e60` |
| 26.2 | 7 / BETA | `b91778aa8a60aa04082e520431e0dff15ebebbe562b5eedaf386d2e980fbdd8b` |
| 26.3 | 6 / ALPHA | `03e1fef6cdd29a75880d58e0922a3272b95afd8cd9e35f681988310356504dfa` |

| API available on all three | Required use |
|---|---|
| `Entity.getScheduler()`; `EntityScheduler.execute(Plugin,Runnable,Runnable,long)`; nullable `run`, `runDelayed`, `runAtFixedRate` task handles | Player location, permissions, visibility and connection admission on that player's owner. Handle both scheduling rejection and later retirement. |
| `Server.getRegionScheduler()`; `RegionScheduler.run/runDelayed/runAtFixedRate(Plugin,World,int,int,Consumer,...ticks)` | Location-bound world sampling and eventual NPC actors. The chunk coordinates are a scheduling address, not a permanent region identity. |
| `Server.isOwnedByCurrentRegion(Entity)` | Check entity ownership before reading its location; a location read cannot safely be used to establish ownership. |
| `Server.isOwnedByCurrentRegion(World,minChunkX,minChunkZ,maxChunkX,maxChunkZ)` | Verify ownership of the complete collision, support or interaction footprint before world access. |
| `Player.isChunkSent(long)` | Per-viewer delivery check on the player's owner, independent of whether the server has loaded that chunk. |
| `ScheduledTask.cancel()` and execution state | Track individual repeating tasks; cancellation is accompanied by an Animus generation change so already-running work cannot publish stale state. |
| `GlobalRegionScheduler` and async execution | Bounded control-plane bookkeeping and file/CPU work respectively. Neither grants ownership of arbitrary world data. |

Folia's entity scheduler follows an entity across region/world transitions. Its retired callback runs in a restricted context: use it to invalidate tokens and release queue slots, without world access, entity removal or chunk operations. The current `ReplyScheduler` supplies an empty retirement callback, which suffices for a best-effort command reply but cannot express completion of a runtime lease. [EntityScheduler contract](https://jd.papermc.io/folia/26.1.2/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html)

Ownership must be checked at execution time. The entity overload is the documented way to establish entity ownership; retrieving an unowned entity's location is undefined. The rectangle overload covers all chunks in a required footprint. [Server ownership API](https://jd.papermc.io/folia/26.1.2/org/bukkit/Server.html#isOwnedByCurrentRegion(org.bukkit.entity.Entity))

The inspected `CraftPlayer.isChunkSent` implementation enters an async-access check and then reads native player delivery state. Keep `ChunkDelivery.sent` on the player owner. `NativePacketAdapter.watch` also resolves the player's native connection during admission; `spawn`/`move` check the player's world. Those calls need an owner-thread boundary even though their queued packet writes and pipeline changes run on Netty. The adapter's Netty `current(Session)` check itself uses session atomics/maps rather than player world reads.

Current Animus obstacles are explicit:

- `PersistedNpcService`, `PacketProofService`, `BukkitNavigationBridge` and combat reject Folia; `LiveMovementProbe` requires `Bukkit.isPrimaryThread()`. Removing those checks would leave unsafe world access and concurrent mutation.
- The persisted runtime's `HashMap` registries, `ArrayDeque` queues, `SpatialNpcIndex`, `VirtualNpcState`, viewer guards and route cursors have one owner today. A concurrent outer map alone would not make their contents safe.
- `CombatBinding` currently exposes mutable state and owner-thread callbacks. A region adapter must replace cross-owner use with commands and immutable results.
- The public cancellable interaction event has ordinary synchronous-event semantics. Its proposed Folia dispatch site and the rules for listeners accessing other entities must be tested on all exact targets.

## Scheduling does not imply a no-load operation

All three inspected native implementations route `FoliaRegionScheduler.execute`, and off-owner delayed/repeating admission, through `RegionizedTaskQueue.queueTickTaskQueue`. Its `WorldRegionTaskData` maintains a `TASK_QUEUE_TICKET` at `ChunkHolderManager.MAX_TICKET_LEVEL` and processes ticket updates. This establishes that remote scheduling participates in region/chunk infrastructure; it does **not** establish that it loads a fully generated chunk, nor that it is a harmless way to probe arbitrary unloaded terrain.

The official overview likewise describes location task queues as able to create a region if needed. Therefore an off-owner scheduler hop must not be advertised as a proven implementation of Animus's unavailable-chunk policy. A prior loaded-cache observation has a race with unload. [Folia inter-region operations](https://docs.papermc.io/folia/reference/overview/#inter-region-operations)

For the first slice, perform only work already owned by a live viewer's region. Do not submit location tasks for absent or unowned terrain. Later location actors need a measured dispatch contract: callbacks recheck ownership and loaded state, and QA must prove that scheduling/cancellation around unload does not generate or retain terrain. `getChunkAtAsync`, synchronous chunk retrieval and plugin chunk tickets are not part of that default path.

## Smallest executable slice

Implement a separate opt-in `FoliaStationaryProofService`, initially one stationary player-type virtual body per admitted viewer and at most eight viewers. Preserve the existing general Folia runtime-disable gates. Use no helper entity or fabricated NPC body.

1. Enter through the player's entity scheduler. Bind the exact packet adapter and current viewer object/connection generation there; load signed skin data on the existing bounded I/O executor if requested. Return through the same entity scheduler and recheck the session token after the read.
2. Choose a nearby fixed position. Require the player owner to own the entire body/support footprint and conservative line-of-sight footprint, then check loaded terrain and `isChunkSent`. Refuse unowned or unavailable terrain explicitly. The proof can be constrained to an interior chunk to keep the first footprint small.
3. Create the body and send spawn packets through the existing semantic adapter. Publish visibility only after successful transport completion, delivered back to the entity scheduler. Check the original generation again before binding hit authority. A packet write completion is not a graphical-client acknowledgment.
4. Poll a bounded lease on `EntityScheduler.runAtFixedRate`. Recheck ownership, viewer world/permission/health, chunk delivery and range. Cancel and despawn when the viewer's region no longer owns the stationary NPC footprint. Do not follow the viewer by teleporting the NPC or creating a new region.
5. Route native attack/use to the viewer scheduler. Validate current session, virtual ID, authoritative box, reach/rate and loaded, owned line of sight; fire the preview interaction event there, then repeat authority checks after listeners. Keep preview events free of real commands/combat.
6. Retirement invalidates the session immediately using thread-safe tokens. Cleanup may discard pure state and use the existing network-only pipeline/despawn retirement path; it must not read the removed player's world. Stop must tolerate already-stopped region/entity schedulers without blocking for an owner callback.

This demonstrates real region-thread rendering/interaction for a viewer-private stationary body. It does not establish persistent multi-viewer NPC ownership, autonomous movement, remote control, mounts, real-target damage or mob retargeting on Folia.

## Concrete interfaces for the subsequent runtime

These are proposed contracts, not implemented APIs. Keep Bukkit objects in the platform implementation.

| Contract | Responsibility and limit |
|---|---|
| `ViewerDispatch.submit(viewerLease, action, retired)` | Exactly one terminal result: executed, rejected or retired. Carries viewer UUID plus connection generation; owner action rechecks the actual player. |
| `NpcOwnerToken(npcUuid, incarnation, epoch, worldUuid, anchorChunk)` | Identifies the single writer of mutable NPC state. An anchor chunk is not a Folia region ID; ownership is checked anew on every callback. |
| `NpcMailbox.offer(token, command)` | Bounded immutable commands; coalesce replaceable pose requests, reserve cancellation/despawn admission, and never block a region thread awaiting another owner. |
| `OwnedTerrain.capture(token, bounds, budget)` | Owner-only, already-loaded samples plus capture provenance. Return `UNOWNED`/`UNAVAILABLE` explicitly. No Bukkit block/chunk objects escape to planning workers. |
| `ViewerPresentation.publish(viewerLease, npcSnapshot)` | Per-viewer owner performs delivery/distance rules, maintains IDs/guards, and sends semantic packet work. Publish immutable snapshots, never another owner's mutable `VirtualNpcState`. |
| `OwnerTransfer.begin(token, destination)` | Freeze the source writer, exchange a bounded immutable handoff, acknowledge destination adoption, and revoke obsolete callbacks by epoch. Failure suspends the NPC without creating terrain. |

A global or dedicated serial coordinator may maintain immutable definition publications, admission permits and directory snapshots. It must not iterate live players to read positions, sample blocks or mutate region-owned NPC state. Each owner publishes spatial updates; the first implementation can use coarse immutable spatial shards with bounded scans rather than making the existing mutable index concurrently accessible.

For player interaction with a production NPC, enqueue an immutable request to the NPC owner. At execution, require that owner to own the requesting player as well as the full interaction footprint, then re-read the player eye/world/permission and validate against current NPC state. Refuse/defer an interaction if those ownership checks fail. This avoids treating an old eye-position snapshot as authority and avoids an unsafe cross-region synchronous event. Wider cross-owner combat remains a separate gate.

## Snapshots, transfer and visibility

Capture only immutable cells with owner epoch, world, bounds, monotonic capture times and local pump sequence. Regions tick independently, so tick numbers from different owners are not a shared ordering clock. A multi-part capture is not an atomic world snapshot. Recheck ownership and loaded state for every resumed slice, cancel on lease change, plan off-thread, then validate each proposed swept body plus its halo on the current owner before committing movement. [Independent Folia regions](https://docs.papermc.io/folia/reference/overview/#ticking-for-independent-regions)

A future handoff should proceed as follows:

1. The old owner marks the NPC transferring, increments its epoch, cancels planning/control and publishes a non-interactive immutable snapshot. Every pending skin, route, input and packet callback carries the old epoch and can no longer mutate authority.
2. Transfer to an already admitted destination owner mailbox. That owner checks the full destination footprint, world, loaded terrain and current definition generation before atomically adopting the handoff. No public stable-region identity is assumed; scheduler ownership is checked by coordinates.
3. The source relinquishes mutation permanently for that token. Destination acknowledgment publishes the new authoritative snapshot; lost, rejected or timed-out adoption leaves the NPC suspended. A resumed transfer gets another epoch, preventing delayed acknowledgments from resurrecting a prior owner.
4. Viewer entity schedulers independently consume the new snapshot, verify their own loaded/sent chunks and update or despawn presentation. A player teleport/dimension change invalidates that viewer's session regardless of the NPC transfer outcome.

Initially suspend at an ownership boundary when no destination pump is already available. Implementing arbitrary remote location dispatch, region split/merge wakeup and movement across independently ticking owners follows the stationary proof. Public region schedulers are tied to locations; real entities continue to use their own schedulers. [RegionScheduler contract](https://jd.papermc.io/folia/26.1.2/io/papermc/paper/threadedregions/scheduler/RegionScheduler.html)

## Verification gates

- Bind and run the stationary proof on Folia **26.1.2 build8 STABLE**, **26.2 build7 BETA**, and **26.3 build6 ALPHA**, recording artifact/server hashes and exact APIs. Keep prerelease labels in reports.
- Run two players in separate ticking regions concurrently; assert each callback's entity/rectangle ownership before world reads. Verify private bodies, skins, native attack/use, cancellation and absence from world entity lookup/registration.
- Exercise login, logout during queued spawn/skin read, reconnect, respawn, teleport, dimension change, permission removal and scheduler retirement. All terminal outcomes release permits and leave no handler, body ID or repeating task behind.
- Make the target/LOS/support halo unowned or unloaded. Refuse before sampling; compare loaded/generated chunk state and ticket observations before/after. Never equate a passing startup with this gate.
- Test region boundary movement, split/merge, competing transfer/cancellation, blocked destination, route replacement, delayed completions, slow viewer queues and shutdown during pending work before enabling production actors or navigation.
- Test event dispatch/cancellation on the actual Folia region thread and document listener ownership restrictions. Real combat and protection integrations remain disabled until their separate owner-safe mechanics pass.
- Keep file-only Folia operation and explicit capability diagnostics working throughout; do not remove a disable gate merely because matching reflection signatures were found.

## Reproduction

The API jars are under `.qa/platform-tests/folia-26.1.2-8/libraries/dev/folia/folia-api/`, `folia-beta/folia-26.2-7/libraries/dev/folia/folia-api/`, and `folia-alpha/folia-26.3-6/libraries/dev/folia/folia-api/`. Expanded native jars are in each corresponding `versions/<version>/folia-<version>.jar` directory. Their SHA-256 values, distinct from downloadable launcher hashes, are:

```text
26.1.2  1c6040315d5b608916ca6811f3a93c72ac950d0ca14cc2dd64aaa85fb27b124d
26.2    a43ba2b1d4e829ab30bb9198660542d58fd054e592dc608f852130cfe1726875
26.3    4268a906fe28842ee8566a908c8486e656f6baf9d5947f512791c2a635cd3df6
```

Re-run Java 25 `javap -p -c -classpath <jar> <class>` for the listed scheduler interfaces, `Server`, `Player`, `Entity`, native `FoliaRegionScheduler`, `FoliaEntityScheduler`, `RegionizedTaskQueue$WorldRegionTaskData`, and `CraftPlayer`. Local inspection output is currently `/tmp/animus-folia-api-<version>.txt` and `/tmp/animus-folia-<version>-<class>.txt`. These temporary files aid reproduction; this document records the conclusions independently of them.
