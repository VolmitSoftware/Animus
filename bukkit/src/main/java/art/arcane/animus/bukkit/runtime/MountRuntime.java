package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.control.RideLedger;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.MountRelations;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Persisted passenger relations, real riders and dragon phases of one runtime. Owner thread only. */
final class MountRuntime implements AutoCloseable {
    private static final int DRAGON_REST_TICKS = 20, ROSTER_RESEND_TICKS = 20, MOUNT_LOG_LIMIT = 20;
    private final PersistedNpcService host;
    private final RiderService riders;
    private final Set<PersistedNpcService.Value> dragons = new HashSet<>();
    private MountRelations.Resolution mounts = MountRelations.Resolution.EMPTY;
    private boolean dirty;
    private long revision;

    MountRuntime(PersistedNpcService host, String riderUnavailable) {
        this.host = host;
        riders = new RiderService(host.plugin, new RiderPresentation(), riderUnavailable);
    }

    RiderService riders() { return riders; }
    int mountedCount() { return mounts.vehicleOf().size(); }
    int problemCount() { return mounts.diagnostics().size(); }
    String vehicleOf(String id) { return mounts.vehicleOf().get(id); }
    RiderService.Vehicle vehicle(PersistedNpcService.Value value) { return new RideVehicle(value); }

    void activated(PersistedNpcService.Value value) {
        dirty = true;
        if (value.spec.type().equals(DragonBody.TYPE)) dragons.add(value);
    }
    void removed(PersistedNpcService.Value value) { dirty = true; dragons.remove(value); }

    /** Structural mount problems stop an edit. A missing or dormant vehicle does not: it stays preserved and reported. */
    List<String> activationBlockers(NpcDefinition definition) {
        Map<String, MountRelations.Node> nodes = new HashMap<>();
        host.entries.forEach((id, entry) -> { if (entry.value != null) nodes.put(id, node(entry.value)); });
        MountReference candidate = MountDeclaration.parse(definition.json()).orElse(null);
        nodes.put(definition.id(), new MountRelations.Node(definition.id(), definition.uuid(), candidate));
        return MountRelations.resolve(nodes.values(), this::knownUuid).diagnostics().stream()
                .filter(problem -> problem.code().blocksActivation() && problem.id().equals(definition.id()))
                .map(problem -> problem.field() + ": " + problem.message()).toList();
    }
    String summary(PersistedNpcService.Value value) {
        String id = value.spec.definition().id();
        StringBuilder summary = new StringBuilder();
        String vehicle = mounts.vehicleOf().get(id);
        if (vehicle != null) summary.append("; mounted-on=").append(vehicle);
        List<String> passengers = mounts.passengersOf().get(id);
        if (passengers != null) summary.append("; passengers=").append(passengers);
        for (MountRelations.Diagnostic problem : mounts.diagnostics())
            if (problem.id().equals(id)) summary.append("; mount-problem=").append(problem.field()).append(": ").append(problem.message());
        if (value.ride != null) summary.append("; ride=").append(riders.describe(value.spec.definition().uuid()));
        return summary.toString();
    }
    String rideStatus() {
        if (!riders.available()) return "Riding unavailable: " + riders.unavailable();
        List<String> lines = riders.status();
        return "Riders " + riders.count() + "/" + RideLedger.MAX_RIDERS + (lines.isEmpty() ? "" : " | " + String.join(" | ", lines));
    }

    private UUID knownUuid(String id) {
        NpcDefinition known = host.runtime.npcs().snapshot().get(id);
        return known == null ? null : known.uuid();
    }
    private static MountRelations.Node node(PersistedNpcService.Value value) {
        return new MountRelations.Node(value.spec.definition().id(), value.spec.definition().uuid(), value.mountReference);
    }

    /** Recomputed only when active definitions change; the graph holds at most the bounded active set. */
    void resolve() {
        if (!dirty) return;
        dirty = false;
        List<MountRelations.Node> nodes = new ArrayList<>(host.entries.size());
        for (PersistedNpcService.Entry entry : host.entries.values()) if (entry.value != null) nodes.add(node(entry.value));
        MountRelations.Resolution next = MountRelations.resolve(nodes, this::knownUuid);
        for (String id : mounts.vehicleOf().keySet()) {
            if (next.vehicleOf().containsKey(id)) continue;
            PersistedNpcService.Entry entry = host.entries.get(id);
            // Without a vehicle the NPC returns to its declared position instead of hanging at the old seat.
            if (entry != null && entry.value != null) {
                PersistedNpcService.Value value = entry.value;
                value.state.move(value.spec.position()); host.index.upsert(value.state.snapshot()); value.poseRevision++;
                PersistedNpcService.setPose(value, value.spec.yaw(), value.spec.pitch(), value.onGround);
            }
        }
        for (String id : next.vehicleOf().keySet()) {
            PersistedNpcService.Value value = host.entries.get(id).value;
            host.cancelRouteRead(value); value.waypoints = null; host.stopRoute(value, "unavailable: this NPC rides " + next.vehicleOf().get(id));
        }
        List<MountRelations.Diagnostic> reported = new ArrayList<>(next.diagnostics());
        reported.removeAll(mounts.diagnostics());
        reported.stream().limit(MOUNT_LOG_LIMIT).forEach(problem -> host.plugin.getLogger().warning("NPC mount: " + problem));
        mounts = next; revision++;
    }
    /** Vehicles come before their passengers, so a chain follows its root within one tick. */
    void syncPassengers() {
        for (String id : mounts.order()) {
            PersistedNpcService.Entry passenger = host.entries.get(id), carrier = host.entries.get(mounts.vehicleOf().get(id));
            if (passenger == null || carrier == null || passenger.value == null || carrier.value == null) continue;
            VirtualNpcState.Snapshot vehicle = carrier.value.state.snapshot();
            if (!vehicle.active() || !passenger.value.state.snapshot().active()) continue;
            Vec3 seat = carrier.value.seat.passengerFeet(vehicle.position().point(), carrier.value.yaw, passenger.value.seat.vehicleAttachmentY());
            WorldPosition current = passenger.value.state.snapshot().position();
            if (!current.point().equals(seat) || !current.world().equals(vehicle.position().world())) {
                passenger.value.state.move(new WorldPosition(vehicle.position().world(), seat));
                host.index.upsert(passenger.value.state.snapshot()); passenger.value.poseRevision++;
            }
            PersistedNpcService.setPose(passenger.value, carrier.value.yaw, passenger.value.pitch, false);
        }
    }
    void tickDragons() {
        for (PersistedNpcService.Value dragon : dragons) {
            Vec3 point = dragon.state.snapshot().position().point();
            if (dragon.restingPoint == null) dragon.restingPoint = point;
            else if (!point.equals(dragon.restingPoint)) { dragon.restingPoint = point; dragon.lastMotionTick = host.tickNumber; }
            boolean moving = dragon.lastMotionTick != 0 && host.tickNumber - dragon.lastMotionTick < DRAGON_REST_TICKS;
            dragon.dragonPhase = DragonBody.phase(moving, grounded(dragon));
        }
    }
    private boolean grounded(PersistedNpcService.Value value) {
        WorldPosition position = value.state.snapshot().position();
        World world = host.plugin.getServer().getWorld(UUID.fromString(position.world()));
        Vec3 point = position.point();
        int x = (int) Math.floor(point.x()), y = (int) Math.floor(point.y() - 0.000001), z = (int) Math.floor(point.z());
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block below = world.getBlockAt(x, y, z);
        return below.getType().isSolid();
    }

    /** One viewer's presentation pass. Dragon phases and passenger lists share the viewer's packet budget. */
    int update(PersistedNpcService.Viewer viewer, int budget) {
        int sent = updateDragonPhases(viewer, budget);
        return sent + updatePassengers(viewer, budget - sent);
    }
    private int updateDragonPhases(PersistedNpcService.Viewer viewer, int budget) {
        if (dragons.isEmpty() || budget <= 0) return 0;
        int sent = 0;
        for (PersistedNpcService.Value dragon : dragons) {
            if (sent >= budget) break;
            PersistedNpcService.Presented body = viewer.visible.get(dragon.spec.definition().uuid());
            if (body == null || body.value != dragon || !body.spawnFinished || body.retired || body.sentDragonPhase == dragon.dragonPhase) continue;
            int phase = dragon.dragonPhase;
            body.sentDragonPhase = phase; sent++;
            host.packets.dragonPhase(viewer.player, body.body, phase).whenComplete((nothing, failure) -> {
                if (failure != null) host.enqueue(viewer, () -> { if (host.current(viewer) && !body.retired) body.sentDragonPhase = -1; });
            });
        }
        return sent;
    }
    /** Client passenger lists replace the whole roster and need both IDs known, so they are re-derived from what this viewer has. */
    private int updatePassengers(PersistedNpcService.Viewer viewer, int budget) {
        // Other clients only know a real rider while vanilla tracks it, and nothing announces that here, so its roster repeats.
        boolean resend = riders.count() > 0 && host.tickNumber % ROSTER_RESEND_TICKS == 0;
        if (budget <= 0 || (!resend && !viewer.rosterDirty && viewer.rosterRevision == revision)) return 0;
        int sent = 0;
        boolean complete = true;
        // Detach first: the viewer's passenger graph refuses a body that still belongs to another vehicle.
        for (int pass = 0; pass < 2; pass++) {
            for (PersistedNpcService.Presented vehicle : viewer.presentationOrder) {
                if (!vehicle.spawnFinished || vehicle.retired) continue;
                List<Integer> roster = roster(viewer, vehicle);
                boolean changed = !roster.equals(vehicle.sentRoster);
                boolean detaching = !roster.containsAll(vehicle.sentRoster);
                Player rider = riders.riderOf(vehicle.body.uuid());
                boolean reveal = resend && rider != null && rider != viewer.player;
                if (pass == 0 ? !(changed && detaching) : !(changed || reveal)) continue;
                if (sent >= budget) { complete = false; continue; }
                sendRoster(viewer, vehicle, rider, false); sent++;
            }
        }
        if (complete) { viewer.rosterDirty = false; viewer.rosterRevision = revision; }
        return sent;
    }
    private List<PersistedNpcService.Presented> virtualPassengers(PersistedNpcService.Viewer viewer, PersistedNpcService.Presented vehicle) {
        List<String> ids = mounts.passengersOf().get(vehicle.value.spec.definition().id());
        if (ids == null) return List.of();
        List<PersistedNpcService.Presented> visible = new ArrayList<>(ids.size());
        for (String id : ids) {
            PersistedNpcService.Entry entry = host.entries.get(id);
            PersistedNpcService.Presented passenger = entry == null || entry.value == null ? null : viewer.visible.get(entry.value.spec.definition().uuid());
            if (passenger != null && passenger.value == entry.value && passenger.spawnFinished && !passenger.retired) visible.add(passenger);
        }
        return visible;
    }
    private List<Integer> roster(PersistedNpcService.Viewer viewer, PersistedNpcService.Presented vehicle) {
        List<Integer> roster = new ArrayList<>();
        for (PersistedNpcService.Presented passenger : virtualPassengers(viewer, vehicle)) roster.add(passenger.body.entityId());
        Player rider = riders.riderOf(vehicle.body.uuid());
        if (rider != null) roster.add(rider.getEntityId());
        return roster;
    }
    private CompletionStage<Void> sendRoster(PersistedNpcService.Viewer viewer, PersistedNpcService.Presented vehicle, Player rider, boolean authoritative) {
        List<NativePacketAdapter.Body> bodies = virtualPassengers(viewer, vehicle).stream().map(passenger -> passenger.body).toList();
        List<Integer> roster = new ArrayList<>(bodies.size() + 1);
        for (NativePacketAdapter.Body body : bodies) roster.add(body.entityId());
        if (rider != null) roster.add(rider.getEntityId());
        vehicle.sentRoster = List.copyOf(roster);
        return host.packets.passengers(viewer.player, vehicle.body, bodies, rider, authoritative).whenComplete((nothing, failure) -> {
            if (failure != null) host.enqueue(viewer, () -> {
                if (!host.current(viewer) || vehicle.retired) return;
                vehicle.sentRoster = List.of(NativePacketAdapter.NO_REAL_RIDER); viewer.rosterDirty = true; host.noteFailure(failure);
            });
        });
    }

    @Override public void close() { riders.close(); dragons.clear(); }

    private final class RideVehicle implements RiderService.Vehicle {
        final PersistedNpcService.Value value;
        RideVehicle(PersistedNpcService.Value value) { this.value = value; }
        @Override public UUID uuid() { return value.spec.definition().uuid(); }
        @Override public String id() { return value.spec.definition().id(); }
        @Override public RideSettings settings() { return value.ride; }
        @Override public VirtualNpcState.Snapshot snapshot() { return value.state.snapshot(); }
        @Override public float yaw() { return value.yaw; }
        @Override public boolean current() { return host.valueCurrent(value); }
        @Override public boolean mounted() { return mounts.vehicleOf().containsKey(id()); }
        @Override public boolean presentedTo(Player rider) {
            PersistedNpcService.Viewer viewer = host.viewers.get(rider.getUniqueId());
            PersistedNpcService.Presented presented = viewer == null ? null : viewer.visible.get(uuid());
            return presented != null && presented.value == value && presented.spawnFinished && !presented.retired;
        }
        @Override public double hullWidth() { return Math.max(PersistedNpcService.moveWidth(value), host.playerWidth()); }
        @Override public double hullHeight() {
            return value.seat.riderClearance(PersistedNpcService.moveHeight(value), host.playerSeat().vehicleAttachmentY(), host.playerHeight());
        }
        @Override public void claim() { host.cancelRouteRead(value); value.waypoints = null; host.stopRoute(value, "cancelled: a player is riding"); }
        @Override public void commit(Vec3 position, float nextYaw) {
            WorldPosition before = value.state.snapshot().position();
            if (!before.point().equals(position)) {
                value.state.move(new WorldPosition(before.world(), position)); host.index.upsert(value.state.snapshot());
                value.poseRevision++;
            }
            PersistedNpcService.setPose(value, nextYaw, 0, true);
        }
    }
    private final class RiderPresentation implements RiderService.Presentation {
        @Override public NativePacketAdapter.ControlSubscription subscribe(Player rider) { return host.packets.subscribeControl(rider); }
        @Override public void attach(Player rider, RiderService.Vehicle vehicle, Runnable attached, Consumer<String> failed) {
            PersistedNpcService.Viewer viewer = host.viewers.get(rider.getUniqueId());
            PersistedNpcService.Presented presented = viewer == null ? null : viewer.visible.get(vehicle.uuid());
            if (presented == null) { failed.accept("not presented"); return; }
            revision++;
            sendRoster(viewer, presented, rider, true).whenComplete((nothing, failure) -> host.enqueue(viewer, () -> {
                if (failure == null) attached.run(); else failed.accept(PersistedNpcService.concise(failure));
            }));
        }
        @Override public void detach(Player rider, RiderService.Vehicle vehicle) {
            revision++;
            for (PersistedNpcService.Viewer viewer : host.viewers.values()) {
                PersistedNpcService.Presented presented = viewer.visible.get(vehicle.uuid());
                if (presented != null && presented.spawnFinished && !presented.retired && !presented.sentRoster.isEmpty()) sendRoster(viewer, presented, null, true);
            }
        }
        @Override public void place(Player rider, Vec3 feet) {
            try { host.packets.placeRider(rider, feet.x(), feet.y(), feet.z()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot position rider: " + PersistedNpcService.concise(failure), failure); }
        }
    }
}
