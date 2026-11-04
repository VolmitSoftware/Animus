# Controllable NPC investigation

Investigated on 2026-11-04 using the cached exact Minecraft server and client jars, Java 25 `javap -p -c`, and the current packet/passenger adapter. No servers were started. The findings below are verified packet and code paths. [What was built from them](#real-rider-implementation) is described at the end; it has unit tests and exact-jar checks and has not yet run with a graphical client.

Implementation follow-up: the unmounted proof below is now present in source, with focused core tests and Bukkit compilation passing. Exact-version live control scenarios remain the acceptance gate. `ControlInput`, `ControlInbox` and `ControlLoop` separate semantic keys, a bounded latest-value mailbox and the authoritative proposal clock. The adapter reads keys and client-tick-end heartbeats without scheduling a callback for every packet; the owner polls at most eight active leases. Only tick-end heartbeats renew the two-second liveness deadline. An input rate above 80 packets per admission-aligned one-second window closes that lease; a coalesced shift still closes it.

`/animus proof control <id>|stop|status` admits detached player proof bodies on loaded, full-block, flat terrain. A lease begins without inherited input. Status retains its generation, simulation tick, accepted step count, input sequence, authoritative position and stop reason until the proof is cleared. Terrain is checked through the shared `LiveMovementProbe` with a 128-cell budget. A valid movement step publishes owner state before packet egress, while interaction authority remains withdrawn until delivery completes. An already accepted packet may finish after stop; its completion cannot advance coordinates or restart the lease. This remains an opt-in, viewer-isolated preview and has no real combat or command effects.

## Exact protocol findings

| Surface | 26.1.2 / 26.2 | 26.3 |
|---|---|---|
| `ServerboundPlayerInputPacket` | `record(Input input)` | Same |
| `Input` | Seven booleans: forward, backward, left, right, jump, shift, sprint | Same |
| `ServerboundMoveVehiclePacket` | `record(Vec3 position, float yRot, float xRot, boolean onGround)` | `record(PositionAndRotation movingTo, boolean onGround)` |
| `ClientboundMoveVehiclePacket` | `record(Vec3 position, float yRot, float xRot)`; `FriendlyByteBuf` codec | `record(PositionAndRotation movingTo)`; `ByteBuf` codec |
| `ServerboundPlayerCommandPacket` | `getId()`, `getAction()`, `getData()` | Same |
| Riding commands | `START_RIDING_JUMP`, `STOP_RIDING_JUMP`, `OPEN_INVENTORY` | Same |
| Teleport acknowledgment | Constructor `(int id)`, accessor `getId()` | Constructor `(int id, double x, double y, double z, float yRot, float xRot)`, record accessors |
| `ClientboundSetPassengersPacket` | Vehicle ID + passenger ID array; native-entity public constructor, private buffer constructor | Same |

The command enum also contains sleeping, sprinting and fall-flying actions. There are no press/release-shift commands in these inspected versions: shift is in `Input`. Neither input nor move-vehicle packets contain an NPC identifier. The connection's current control lease must determine which NPC, if any, is affected.

Verified behavior in all three versions:

- `LocalPlayer.tick()` in 26.1.2/26.2 and `sendChanges()` in 26.3 send input when its boolean state changes. Held keys do **not** produce a fresh input packet every tick. An input-age timeout alone would incorrectly stop a held direction; use a bounded lease and connection/client-tick liveness separately.
- While locally riding, the client sends player rotation and sends vehicle movement only when the root vehicle is locally authoritative. Do not assume every entity type becomes controllable merely because it has a passenger.
- `ServerGamePacketListenerImpl.handlePlayerInput()` stores the input and updates shift state; it does not locate or move a virtual NPC.
- `handleMoveVehicle()` requires the server player's real root vehicle, its controlling passenger, and the listener's `lastVehicle` to agree. A packet-only NPC has no such server vehicle relationship, so vanilla does not supply its movement authority.
- The client passenger handler resolves client entity IDs and calls local `startRiding`. That changes client presentation only.
- The client ignores ordinary player-position changes while it considers the player a passenger, while still acknowledging the teleport. In 26.3 that acknowledgment additionally contains the client's position/rotation. Repeated player teleports are therefore not a proven way to synchronize a virtual mount and real rider.
- Clientbound vehicle correction applies to the locally authoritative root vehicle and elicits a move-vehicle response. Neither direction should be treated as permission to move the server player or the virtual NPC to arbitrary client coordinates.

## Existing implementation boundary

`NativePacketAdapter.passengers` accepts visible virtual bodies owned by that viewer's adapter session, plus optionally one real player by its own server entity. Its small passenger graph rejects duplicate parents, cycles and lists over 16 passengers. `PacketProofService` rejects movement of mounted proof bodies and revokes their hit authority; clearing an involved body clears the mount presentation.

## Smallest useful next proof

Implement **unmounted remote control of one viewer-isolated proof NPC** first. This demonstrates input-derived authoritative virtual motion without pretending that a passenger packet grants server movement authority. The player's ordinary movement remains vanilla; this is explicitly remote control, not possession or riding.

The implementation design was:

1. Add a semantic `ControlInput` snapshot and an optional adapter input subscription. Read only the seven flags on Netty; keep the latest snapshot per admitted connection and one bounded scheduled drain. Pass ordinary input through to vanilla. Assign sequence/generation values on the server, since the input packet supplies none.
2. Add `/animus proof control <entity-id>|stop`, under the existing explicit proof setting and operator permission. Admit one body per controller, reject actual server passengers, and bind the connection generation, body generation, world, permission and active proof state. Begin with empty input; require a fresh input transition after admission to avoid inheriting an already-held key.
3. Simulate on the owning server scheduler at at most 20 fixed steps/second, independent of received packet count. Begin with a prepared, loaded, flat arena, horizontal movement only, 0.1 block/step and an eight-block leash. Normalize diagonals; opposite directions cancel. Jump/sprint do not grant extra speed; shift releases control. No chunk loads or pathfinding are needed for this bounded proof.
4. Validate each proposed authoritative hitbox against loaded terrain/collision before movement. Reuse the existing pending-move guard and generation checks; update virtual state and its interaction snapshot consistently with completed presentation. Backpressure, failed sends or stale completions revoke the lease. Client coordinates and `onGround` never establish position or collision results.
5. End within 30 seconds and revoke immediately on stop/shift, permission loss, teleport/world change, death, disconnect, body retirement, actual-vehicle entry, unavailable terrain or plugin shutdown. Drop pending input before cleanup so late callbacks cannot restart movement.

The exact native test clients can construct `Input` and `ServerboundPlayerInputPacket` on all three versions. Test held input, release, diagonal normalization, opposite flags, wall/leash limits, flooding without increased speed, another viewer's input, stale reconnect input, cancellation and teardown. Assert authoritative virtual coordinates as well as wire coordinates, and assert zero world-registered NPC bodies. A graphical client still has to demonstrate ordinary keyboard behavior and presentation.

## Real rider implementation

No helper vehicle or surrogate entity exists. The roster packet names the real player by its own entity ID, so a virtual `Body` is never made for it. Further exact findings on all three versions shaped the design:

- A client rides passively. `isLocalClientAuthoritative` needs a controlling passenger, which a non-saddled virtual body never has, so the client follows server position packets for the vehicle and sends only rotation and input, never move-vehicle.
- The server does not know the player is a passenger, so its rotation packets run the ordinary movement path. That path sets `clientIsFloating` when no block is near the player's box, and `tick()` kicks after the flying limit. The rider's server position is therefore the NPC's supported feet, never its seat. Admission requires ground and every step keeps it.
- `Entity.setPos` plus `ServerChunkCache.move` moves the server player and its chunk view with no client teleport. `tick()` snaps back to the movement baseline, and a rotation packet is measured against it, so `resetPosition()` follows at once. No acknowledgment is awaited or rewritten, and normal movement validation stays on.
- A move packet from before the client mounted can still arrive. The rider is put back at the NPC's feet every tick, so such a packet cannot move the ride.
- A detach is written ahead of an ordinary Bukkit teleport from the owner thread. Netty runs tasks in order, so the client stops riding, and only then applies the position, which it would otherwise ignore. That restores agreement with no special handling, including at shutdown.
- A foreign teleport fires its event before the packet goes out, so ending the ride in that handler orders the detach ahead of it too.
- Shift is in `Input` and reaches `ControlInbox`, which already closes on it, so it needs no special case. Other viewers need the roster again whenever vanilla starts tracking the rider, and nothing announces that, so it repeats once a second to them.
- `ServerboundMoveVehiclePacket` needs no handling: vanilla requires a real root vehicle and ignores it.

`RideSession`, `RideLedger` and `RideSteering` hold the decisions: attaching, riding and closed states, one-time attach completion, bounds, leash and terrain outcomes. `RiderService` applies them on the owner thread, and `MountRuntime` keeps them with viewer state. The vanilla-style support rule, in which any one full block under the body is enough, lives in `RideSteering` because `MotionValidator`'s every-cell rule would leave a wide body stuck at any ledge. Clearance still comes from `MotionValidator`.

Still unavailable: mount inventory and jump semantics, flying and swimming control, region transfer on Folia, camera possession, integrations with region or anti-cheat plugins, and persisting a ride across a restart. Reach from the seat is not simulated. Graphical-client behaviour still has to be confirmed for dismount keys, camera, seat placement and motion smoothing.

## Reproduction evidence

Sources are local exact jars at `~/.gradle/caches/fabric-loom/<version>/minecraft-extracted_server.jar` and `minecraft-client.jar`. Inspect the packet classes above plus `ServerGamePacketListenerImpl`, `LocalPlayer` and `ClientPacketListener` with Java 25 `javap -p -c -classpath <jar> <class>`. The inspected input/passenger signatures match across all three; the move-vehicle and teleport differences above are explicit adapter branches.

| Version | Server SHA-256 | Client SHA-256 |
|---|---|---|
| 26.1.2 | `4723380bd2a0a0206719b50f2e390383afdaf82b0a76a0d573baf788e6aa3e86` | `b1b3158572666445eff01e82fad8c7de2e4953db6d354f311730d77a8359d0b0` |
| 26.2 | `183c0499c5f855570ee487dd38e141a53f0121f83a0b07a3bac2d8b6698823e8` | `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290` |
| 26.3 | `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6` | `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d` |
