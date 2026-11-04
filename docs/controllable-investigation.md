# Controllable NPC investigation

Investigated on 2026-11-04 using the cached exact Minecraft server and client jars, Java 25 `javap -p -c`, and the current packet/passenger adapter. No production source changed and no servers were started for this investigation. These are verified packet/code paths and proposed proofs, not a claim that riding or controllable movement works today.

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

`NativePacketAdapter.passengers(Player, Body, List<Body>)` currently accepts only visible virtual bodies owned by that viewer's adapter session. Its small passenger graph rejects duplicate parents, cycles and lists over 16 passengers. It cannot attach the real viewer. `PacketProofService` rejects movement of mounted proof bodies and revokes their hit authority; clearing an involved body clears the mount presentation. The existing wire test establishes a static virtual-to-virtual passenger relationship only.

## Smallest useful next proof

Implement **unmounted remote control of one viewer-isolated proof NPC** first. This demonstrates input-derived authoritative virtual motion without pretending that a passenger packet grants server movement authority. The player's ordinary movement remains vanilla; this is explicitly remote control, not possession or riding.

The implementation design was:

1. Add a semantic `ControlInput` snapshot and an optional adapter input subscription. Read only the seven flags on Netty; keep the latest snapshot per admitted connection and one bounded scheduled drain. Pass ordinary input through to vanilla. Assign sequence/generation values on the server, since the input packet supplies none.
2. Add `/animus proof control <entity-id>|stop`, under the existing explicit proof setting and operator permission. Admit one body per controller, reject actual server passengers, and bind the connection generation, body generation, world, permission and active proof state. Begin with empty input; require a fresh input transition after admission to avoid inheriting an already-held key.
3. Simulate on the owning server scheduler at at most 20 fixed steps/second, independent of received packet count. Begin with a prepared, loaded, flat arena, horizontal movement only, 0.1 block/step and an eight-block leash. Normalize diagonals; opposite directions cancel. Jump/sprint do not grant extra speed; shift releases control. No chunk loads or pathfinding are needed for this bounded proof.
4. Validate each proposed authoritative hitbox against loaded terrain/collision before movement. Reuse the existing pending-move guard and generation checks; update virtual state and its interaction snapshot consistently with completed presentation. Backpressure, failed sends or stale completions revoke the lease. Client coordinates and `onGround` never establish position or collision results.
5. End within 30 seconds and revoke immediately on stop/shift, permission loss, teleport/world change, death, disconnect, body retirement, actual-vehicle entry, unavailable terrain or plugin shutdown. Drop pending input before cleanup so late callbacks cannot restart movement.

The exact native test clients can construct `Input` and `ServerboundPlayerInputPacket` on all three versions. Test held input, release, diagonal normalization, opposite flags, wall/leash limits, flooding without increased speed, another viewer's input, stale reconnect input, cancellation and teardown. Assert authoritative virtual coordinates as well as wire coordinates, and assert zero world-registered NPC bodies. A graphical client still has to demonstrate ordinary keyboard behavior and presentation.

## Separate riding gate

After remote control works, add a separate, initially stationary **real-viewer passenger presentation** proof. Introduce an explicit owner-viewer passenger reference; do not manufacture a virtual `Body` for the real player or expose arbitrary passenger IDs. A real player already exists in the world; no helper vehicle or surrogate NPC entity is required or permitted by this proof.

Attach/detach must be a generation-bound operation. Send an empty passenger list before retiring the virtual vehicle, restore a validated safe real-player position through the platform, and preserve any real vehicle relationship created externally. Record both client attachment and actual server position. Disconnect/world transitions must retire the control lease even if the detach packet cannot be delivered.

Only then attempt one bounded movement step with the real rider. This requires a distinct server-side player movement bridge, validated collision/seat offsets, ordered vehicle corrections, normal teleport acknowledgment handling, and agreement between the real player's authoritative location and the presented seat. Cancel if that agreement cannot be maintained. Do not disable normal movement validation, trust vehicle coordinates, rewrite acknowledgments, or advertise riding based on a decoded passenger packet. Graphical-client tests are required for dismount keys, local prediction, corrections and camera behavior.

Currently unavailable: controlled real riders, moving passenger assemblies, mount inventory/jump semantics, flying/swimming control, arbitrary entity-type steering, region transfer on Folia, camera possession, and production control permissions/integrations. These remain separate acceptance items.

## Reproduction evidence

Sources are local exact jars at `~/.gradle/caches/fabric-loom/<version>/minecraft-extracted_server.jar` and `minecraft-client.jar`. Inspect the packet classes above plus `ServerGamePacketListenerImpl`, `LocalPlayer` and `ClientPacketListener` with Java 25 `javap -p -c -classpath <jar> <class>`. The inspected input/passenger signatures match across all three; the move-vehicle and teleport differences above are explicit adapter branches.

| Version | Server SHA-256 | Client SHA-256 |
|---|---|---|
| 26.1.2 | `4723380bd2a0a0206719b50f2e390383afdaf82b0a76a0d573baf788e6aa3e86` | `b1b3158572666445eff01e82fad8c7de2e4953db6d354f311730d77a8359d0b0` |
| 26.2 | `183c0499c5f855570ee487dd38e141a53f0121f83a0b07a3bac2d8b6698823e8` | `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290` |
| 26.3 | `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6` | `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d` |
