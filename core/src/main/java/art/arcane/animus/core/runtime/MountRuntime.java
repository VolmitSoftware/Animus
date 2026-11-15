package art.arcane.animus.core.runtime;

import art.arcane.animus.core.control.RideLedger;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.control.SeatRules;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.MountRelations;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyTransport;
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

/**
 * Persisted passenger relations, NPCs on real vehicles, real riders, seated players and dragon phases of one runtime.
 * Owner thread only.
 */
final class MountRuntime<P, I> implements AutoCloseable {
    private static final int ROSTER_RESEND_TICKS = 20, MOUNT_LOG_LIMIT = 20;
    private final PersistedNpcService<P, I> host;
    private final RiderService<P, I> riders;
    private final RealMounts<P, I> real;
    private final SeatService<P, I> seats;
    private final Set<PersistedNpcService<P, I>.Value> dragons = new HashSet<>();
    private MountRelations.Resolution mounts = MountRelations.Resolution.EMPTY;
    private boolean dirty;
    private long revision;

    MountRuntime(PersistedNpcService<P, I> host, String riderUnavailable, art.arcane.animus.core.control.SeatToggles toggles) {
        this.host = host;
        real = new RealMounts<>(host);
        seats = new SeatService<>(host, new SeatPresentation(), toggles);
        riders = new RiderService<>(host.host, new RiderPresentation(), riderUnavailable, (UUID npc) -> seats.bottom(npc) != null);
    }

    RiderService<P, I> riders() { return riders; }
    SeatService<P, I> seats() { return seats; }
    int mountedCount() { return mounts.vehicleOf().size() + real.attachedCount(); }
    int problemCount() { return mounts.diagnostics().size() + real.problemCount(); }
    String vehicleOf(String id) { return mounts.vehicleOf().get(id); }
    boolean carries(String id) { return mounts.vehicleOf().containsValue(id); }
    /** The NPC rides another NPC or a real entity now, so it moves only with its vehicle. */
    boolean rides(PersistedNpcService<P, I>.Value value) { return mounts.vehicleOf().containsKey(value.spec.definition().id()) || real.vehicle(value) != null; }
    /** The real entity the NPC sits on now, or null. */
    RuntimeHost.RealEntity realVehicle(PersistedNpcService<P, I>.Value value) { return real.vehicle(value); }
    /** What the NPC rides, for a refusal: another NPC's id, a real entity's type, or null. */
    String ridden(PersistedNpcService<P, I>.Value value) {
        String vehicle = mounts.vehicleOf().get(value.spec.definition().id());
        if (vehicle != null) return vehicle;
        RuntimeHost.RealEntity entity = real.vehicle(value);
        return entity == null ? null : "a " + entity.type();
    }
    RiderService.Vehicle<P> vehicle(PersistedNpcService<P, I>.Value value) { return new RideVehicle(value); }

    void activated(PersistedNpcService<P, I>.Value value) {
        dirty = true;
        if (value.spec.type().equals(DragonBody.TYPE)) dragons.add(value);
        real.activated(value);
    }
    void removed(PersistedNpcService<P, I>.Value value) { dirty = true; dragons.remove(value); real.removed(value); }

    /**
     * Where players seated on this NPC stand for the server: the ground under the lowest vehicle of its chain, a real
     * vehicle's feet when it rides one, so vanilla never treats them as flying.
     */
    WorldPosition ground(PersistedNpcService<P, I>.Value value) {
        PersistedNpcService<P, I>.Value root = value;
        for (int depth = 0; depth <= MountRelations.MAX_DEPTH; depth++) {
            String vehicle = mounts.vehicleOf().get(root.spec.definition().id());
            PersistedNpcService<P, I>.Entry entry = vehicle == null ? null : host.entries.get(vehicle);
            if (entry == null || entry.value == null) break;
            root = entry.value;
        }
        RuntimeHost.RealEntity entity = real.vehicle(root);
        WorldPosition feet = entity != null ? entity.position() : root.state.snapshot().position();
        return SeatRules.ground(feet, host.host.collision(feet.world()));
    }

    /** Structural mount problems stop an edit. A missing or dormant vehicle does not: it stays preserved and reported. */
    List<String> activationBlockers(NpcDefinition definition) {
        Map<String, MountRelations.Node> nodes = new HashMap<>();
        host.entries.forEach((String id, PersistedNpcService<P, I>.Entry entry) -> { if (entry.value != null) nodes.put(id, node(entry.value)); });
        MountReference candidate = MountDeclaration.parse(definition.json()).orElse(null);
        nodes.put(definition.id(), new MountRelations.Node(definition.id(), definition.uuid(), candidate));
        return MountRelations.resolve(nodes.values(), this::knownUuid).diagnostics().stream()
                .filter((MountRelations.Diagnostic problem) -> problem.code().blocksActivation() && problem.id().equals(definition.id()))
                .map((MountRelations.Diagnostic problem) -> problem.field() + ": " + problem.message()).toList();
    }
    String summary(PersistedNpcService<P, I>.Value value) {
        String id = value.spec.definition().id();
        StringBuilder summary = new StringBuilder();
        String vehicle = mounts.vehicleOf().get(id);
        if (vehicle != null) summary.append("; mounted-on=").append(vehicle);
        List<String> passengers = mounts.passengersOf().get(id);
        if (passengers != null) summary.append("; passengers=").append(passengers);
        for (MountRelations.Diagnostic problem : mounts.diagnostics())
            if (problem.id().equals(id)) summary.append("; mount-problem=").append(problem.field()).append(": ").append(problem.message());
        if (value.ride != null) summary.append("; ride=").append(riders.describe(value.spec.definition().uuid()));
        summary.append(real.summary(value)).append(seats.describe(value.spec.definition().uuid()));
        return summary.toString();
    }
    String rideStatus() {
        if (!riders.available()) return "Riding unavailable: " + riders.unavailable();
        List<String> lines = riders.status();
        return "Riders " + riders.count() + "/" + RideLedger.MAX_RIDERS + (lines.isEmpty() ? "" : " | " + String.join(" | ", lines));
    }
    String seatStatus() {
        List<String> lines = seats.status();
        int maximum = host.runtime.isRunning() ? host.runtime.seatSettings().maximum() : art.arcane.animus.core.config.SeatSettings.DEFAULT.maximum();
        return "Seated " + seats.count() + "/" + maximum + (lines.isEmpty() ? "" : " | " + String.join(" | ", lines));
    }

    private UUID knownUuid(String id) {
        NpcDefinition known = host.runtime.npcs().snapshot().get(id);
        return known == null ? null : known.uuid();
    }
    /** Active, but its definition is already gone, replaced or despawned, so the runtime is about to remove it. */
    private boolean retiring(MountRelations.Node node) {
        NpcDefinition current = host.runtime.npcs().snapshot().get(node.id());
        return !DefinitionSpec.spawned(current) || !current.uuid().equals(node.uuid());
    }
    private MountRelations.Node node(PersistedNpcService<P, I>.Value value) {
        return new MountRelations.Node(value.spec.definition().id(), value.spec.definition().uuid(), value.mountReference);
    }

    /** Recomputed only when active definitions change; the graph holds at most the bounded active set. */
    void resolve() {
        if (!dirty) return;
        dirty = false;
        List<MountRelations.Node> nodes = new ArrayList<>(host.entries.size());
        for (PersistedNpcService<P, I>.Entry entry : host.entries.values()) if (entry.value != null) nodes.add(node(entry.value));
        MountRelations.Resolution next = MountRelations.resolve(nodes, this::knownUuid, this::retiring);
        for (String id : mounts.vehicleOf().keySet()) {
            if (next.vehicleOf().containsKey(id)) continue;
            PersistedNpcService<P, I>.Entry entry = host.entries.get(id);
            // Without a vehicle the NPC returns to its declared position instead of hanging at the old seat.
            if (entry != null && entry.value != null) {
                PersistedNpcService<P, I>.Value value = entry.value;
                value.state.move(value.spec.position()); host.index.upsert(value.state.snapshot()); value.poseRevision++;
                PersistedNpcService.setPose(value, value.spec.yaw(), value.spec.pitch(), value.onGround);
            }
        }
        for (String id : next.vehicleOf().keySet()) {
            PersistedNpcService<P, I>.Value value = host.entries.get(id).value;
            host.reads.cancelRoute(value); value.waypoints = null; host.routes.stopRoute(value, "unavailable: this NPC rides " + next.vehicleOf().get(id));
        }
        List<MountRelations.Diagnostic> reported = new ArrayList<>(next.diagnostics());
        reported.removeAll(mounts.diagnostics());
        reported.stream().limit(MOUNT_LOG_LIMIT).forEach((MountRelations.Diagnostic problem) -> host.host.warn("NPC mount: " + problem));
        mounts = next; revision++;
    }
    /** Vehicles come before their passengers, so a chain follows its root within one tick; NPCs on real vehicles take their seats first. */
    void syncPassengers() {
        real.tick();
        for (String id : mounts.order()) {
            PersistedNpcService<P, I>.Entry passenger = host.entries.get(id), carrier = host.entries.get(mounts.vehicleOf().get(id));
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
        for (PersistedNpcService<P, I>.Value dragon : dragons)
            dragon.dragonPhase = dragon.dragonMotion.observe(host.tickNumber, dragon.state.snapshot().position().point(), grounded(dragon),
                    DragonBody.restingPhase(dragon.spec.appearance().traits().variant()));
    }
    private boolean grounded(PersistedNpcService<P, I>.Value value) {
        WorldPosition position = value.state.snapshot().position();
        Vec3 point = position.point();
        int x = (int) Math.floor(point.x()), y = (int) Math.floor(point.y() - 0.000001), z = (int) Math.floor(point.z());
        return host.host.solid(position.world(), x, y, z);
    }

    /** Cosmetic work: dragon wing phases, after every viewer's authoritative work, within what it left. */
    int updateDragonPhases(ViewerPresenter<P, I>.Viewer viewer, int budget) {
        if (dragons.isEmpty() || budget <= 0) return 0;
        int sent = 0;
        for (PersistedNpcService<P, I>.Value dragon : dragons) {
            if (sent >= budget) break;
            ViewerPresenter<P, I>.Presented body = viewer.visible.get(dragon.spec.definition().uuid());
            if (body == null || body.value != dragon || !body.spawnFinished || body.retired || body.sentDragonPhase == dragon.dragonPhase) continue;
            if (!host.views.admit(viewer, PresentationPressure.Tier.COSMETIC)) break;
            int phase = dragon.dragonPhase;
            body.sentDragonPhase = phase; sent++;
            host.views.handOver(viewer, () -> host.packets.dragonPhase(viewer.player, body.body, phase)).whenComplete((Void nothing, Throwable failure) -> {
                if (failure != null) host.views.enqueue(viewer, () -> { if (host.views.current(viewer) && !body.retired) body.sentDragonPhase = -1; });
            });
        }
        return sent;
    }
    /**
     * Client passenger lists replace the whole roster and need both IDs known, so they are re-derived from what this viewer
     * has. Authoritative: they share the viewer's packet budget with moves and spawns.
     */
    int updatePassengers(ViewerPresenter<P, I>.Viewer viewer, int budget) {
        // Other clients only know a real rider while vanilla tracks it, and nothing announces that here, so its roster repeats.
        boolean resend = (riders.count() > 0 || seats.count() > 0) && host.tickNumber % ROSTER_RESEND_TICKS == 0;
        int sent = carries(viewer, budget, false);
        if (sent >= budget || (!resend && !viewer.rosterDirty && viewer.rosterRevision == revision)) return sent;
        boolean complete = true;
        // Detach first: the viewer's passenger graph refuses a body that still belongs to another vehicle.
        for (int pass = 0; pass < 2; pass++) {
            for (ViewerPresenter<P, I>.Presented vehicle : viewer.presentationOrder) {
                if (!vehicle.spawnFinished || vehicle.retired) continue;
                List<Integer> roster = roster(viewer, vehicle);
                boolean changed = !roster.equals(vehicle.sentRoster);
                boolean detaching = !roster.containsAll(vehicle.sentRoster);
                P rider = occupant(vehicle.body.uuid());
                boolean reveal = resend && rider != null && rider != viewer.player;
                if (pass == 0 ? !(changed && detaching) : !(changed || reveal)) continue;
                if (sent >= budget || !host.views.admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) { complete = false; continue; }
                host.views.handOver(viewer, () -> sendRoster(viewer, vehicle, rider, false)); sent++;
            }
        }
        if (complete) { viewer.rosterDirty = false; viewer.rosterRevision = revision; }
        return sent;
    }
    private List<ViewerPresenter<P, I>.Presented> virtualPassengers(ViewerPresenter<P, I>.Viewer viewer, ViewerPresenter<P, I>.Presented vehicle) {
        List<String> ids = mounts.passengersOf().get(vehicle.value.spec.definition().id());
        if (ids == null) return List.of();
        List<ViewerPresenter<P, I>.Presented> visible = new ArrayList<>(ids.size());
        for (String id : ids) {
            PersistedNpcService<P, I>.Entry entry = host.entries.get(id);
            ViewerPresenter<P, I>.Presented passenger = entry == null || entry.value == null ? null : viewer.visible.get(entry.value.spec.definition().uuid());
            if (passenger != null && passenger.value == entry.value && passenger.spawnFinished && !passenger.retired) visible.add(passenger);
        }
        return visible;
    }
    private List<Integer> roster(ViewerPresenter<P, I>.Viewer viewer, ViewerPresenter<P, I>.Presented vehicle) {
        List<Integer> roster = new ArrayList<>();
        for (ViewerPresenter<P, I>.Presented passenger : virtualPassengers(viewer, vehicle)) roster.add(passenger.body.entityId());
        P rider = occupant(vehicle.body.uuid());
        if (rider != null) roster.add(host.host.entityId(rider));
        return roster;
    }
    /** The real player on this body: its steering rider, or the lowest player seated on it. */
    private P occupant(UUID body) {
        P rider = riders.riderOf(body);
        return rider != null ? rider : seats.bottom(body);
    }

    /** What a real entity carries for one viewer: NPC bodies, then players stacked on it, after its own passengers. */
    private final class Carried {
        final UUID owner; final List<ViewerPresenter<P, I>.Presented> bodies = new ArrayList<>(); final List<Integer> riders = new ArrayList<>();
        Carried(UUID owner) { this.owner = owner; }
        List<Integer> ids() {
            List<Integer> ids = new ArrayList<>(bodies.size() + riders.size());
            for (ViewerPresenter<P, I>.Presented body : bodies) ids.add(body.body.entityId());
            ids.addAll(riders);
            return ids;
        }
    }
    /** The real entities that carry something for this viewer now, by native ID. */
    private Map<Integer, Carried> carried(ViewerPresenter<P, I>.Viewer viewer) {
        Map<Integer, Carried> wanted = new HashMap<>();
        for (RealMounts<P, I>.Binding binding : real.attached()) {
            ViewerPresenter<P, I>.Presented body = viewer.visible.get(binding.value.spec.definition().uuid());
            if (body == null || body.value != binding.value || !body.spawnFinished || body.retired) continue;
            wanted.computeIfAbsent(binding.vehicle.entityId(), (Integer ignored) -> new Carried(binding.vehicle.uuid())).bodies.add(body);
        }
        for (PersistedNpcService<P, I>.Entry entry : host.entries.values()) {
            if (entry.value == null) continue;
            List<P> stack = seats.stack(entry.value.spec.definition().uuid());
            if (stack.size() < 2) continue;
            ViewerPresenter<P, I>.Presented body = viewer.visible.get(entry.value.spec.definition().uuid());
            if (body == null || body.value != entry.value || !body.spawnFinished || body.retired) continue;
            for (int level = 1; level < stack.size(); level++) {
                P below = stack.get(level - 1);
                wanted.computeIfAbsent(host.host.entityId(below), (Integer ignored) -> new Carried(host.host.id(below)))
                        .riders.add(host.host.entityId(stack.get(level)));
            }
        }
        return wanted;
    }
    /** Sends the real rosters that changed for this viewer, releases first; {@code all} ignores the budget, as a detach must. */
    private int carries(ViewerPresenter<P, I>.Viewer viewer, int budget, boolean all) {
        if (viewer.carried.isEmpty() && real.attachedCount() == 0 && seats.count() < 2) return 0;
        if (!host.packets.carryAvailable()) return 0;
        Map<Integer, Carried> wanted = carried(viewer);
        int sent = 0;
        for (Map.Entry<Integer, List<Integer>> shown : List.copyOf(viewer.carried.entrySet())) {
            if (wanted.containsKey(shown.getKey())) continue;
            if (!admitted(viewer, sent, budget, all)) return sent;
            viewer.carried.remove(shown.getKey());
            sendCarry(viewer, shown.getKey(), new Carried(viewer.carriers.remove(shown.getKey())), shown.getValue()); sent++;
        }
        for (Map.Entry<Integer, Carried> want : wanted.entrySet()) {
            List<Integer> ids = want.getValue().ids();
            if (ids.equals(viewer.carried.get(want.getKey()))) continue;
            if (!admitted(viewer, sent, budget, all)) return sent;
            List<Integer> before = viewer.carried.put(want.getKey(), ids);
            viewer.carriers.put(want.getKey(), want.getValue().owner);
            sendCarry(viewer, want.getKey(), want.getValue(), before); sent++;
        }
        return sent;
    }
    /** A detach goes out whatever the budget, as a despawn does, so the teleport after it reaches the client behind it. */
    private boolean admitted(ViewerPresenter<P, I>.Viewer viewer, int sent, int budget, boolean all) {
        if (all) return host.views.admit(viewer, PresentationPressure.Tier.DESPAWN);
        return sent < budget && host.views.admit(viewer, PresentationPressure.Tier.AUTHORITATIVE);
    }
    private void sendCarry(ViewerPresenter<P, I>.Viewer viewer, int entity, Carried carry, List<Integer> before) {
        List<Integer> own = carry.owner == null ? List.of() : host.host.realPassengers(carry.owner);
        List<BodyTransport.Body> bodies = carry.bodies.stream().map((ViewerPresenter<P, I>.Presented body) -> body.body).toList();
        host.views.handOver(viewer, () -> host.packets.carry(viewer.player, entity, own, bodies, carry.riders)).whenComplete((Void nothing, Throwable failure) -> {
            if (failure != null) host.views.enqueue(viewer, () -> {
                if (!host.views.current(viewer)) return;
                // Sent again on a later tick.
                if (before == null) viewer.carried.remove(entity); else viewer.carried.put(entity, before);
                host.views.cosmeticFailed(viewer, "real passengers", null, failure);
            });
        });
    }
    private CompletionStage<Void> sendRoster(ViewerPresenter<P, I>.Viewer viewer, ViewerPresenter<P, I>.Presented vehicle, P rider, boolean authoritative) {
        List<BodyTransport.Body> bodies = virtualPassengers(viewer, vehicle).stream().map((ViewerPresenter<P, I>.Presented passenger) -> passenger.body).toList();
        List<Integer> roster = new ArrayList<>(bodies.size() + 1);
        for (BodyTransport.Body body : bodies) roster.add(body.entityId());
        if (rider != null) roster.add(host.host.entityId(rider));
        vehicle.sentRoster = List.copyOf(roster);
        return host.packets.passengers(viewer.player, vehicle.body, bodies, rider, authoritative).whenComplete((Void nothing, Throwable failure) -> {
            if (failure != null) host.views.enqueue(viewer, () -> {
                if (!host.views.current(viewer) || vehicle.retired) return;
                vehicle.sentRoster = List.of(art.arcane.animus.core.presentation.BodyRules.NO_REAL_RIDER); viewer.rosterDirty = true; host.views.cosmeticFailed(viewer, "passengers", vehicle.value, failure);
            });
        });
    }

    @Override public void close() { seats.close(); riders.close(); dragons.clear(); real.clear(); }

    private final class RideVehicle implements RiderService.Vehicle<P> {
        final PersistedNpcService<P, I>.Value value;
        RideVehicle(PersistedNpcService<P, I>.Value value) { this.value = value; }
        @Override public UUID uuid() { return value.spec.definition().uuid(); }
        @Override public String id() { return value.spec.definition().id(); }
        @Override public RideSettings settings() { return value.ride; }
        @Override public VirtualNpcState.Snapshot snapshot() { return value.state.snapshot(); }
        @Override public float yaw() { return value.yaw; }
        @Override public boolean current() { return host.valueCurrent(value); }
        @Override public boolean mounted() { return rides(value); }
        @Override public boolean presentedTo(P rider) {
            ViewerPresenter<P, I>.Viewer viewer = host.views.viewers.get(host.host.id(rider));
            ViewerPresenter<P, I>.Presented presented = viewer == null ? null : viewer.visible.get(uuid());
            return presented != null && presented.value == value && presented.spawnFinished && !presented.retired;
        }
        @Override public double hullWidth() { return Math.max(PersistedNpcService.moveWidth(value), host.playerWidth()); }
        @Override public double hullHeight() {
            return value.seat.riderClearance(PersistedNpcService.moveHeight(value), host.playerSeat().vehicleAttachmentY(), host.playerHeight());
        }
        @Override public MovementProfile body() { return BodyMobility.forBody(value.spec.type(), hullWidth(), hullHeight()); }
        @Override public void claim() { host.reads.cancelRoute(value); value.waypoints = null; host.routes.stopRoute(value, "cancelled: a player is riding"); }
        @Override public void released() { value.poseRevision++; }
        @Override public void commit(Vec3 position, float nextYaw) {
            WorldPosition before = value.state.snapshot().position();
            if (!before.point().equals(position)) {
                value.state.move(new WorldPosition(before.world(), position)); host.index.upsert(value.state.snapshot());
                value.poseRevision++;
            }
            PersistedNpcService.setPose(value, nextYaw, 0, true);
        }
    }
    private final class SeatPresentation implements SeatService.Presentation<P, I> {
        @Override public BodyTransport.ControlSubscription subscribe(P player) { return host.packets.subscribeControl(player); }
        @Override public void attach(P player, PersistedNpcService<P, I>.Value npc, P below, Runnable attached, Consumer<String> failed) {
            ViewerPresenter<P, I>.Viewer viewer = host.views.viewers.get(host.host.id(player));
            ViewerPresenter<P, I>.Presented presented = viewer == null ? null : viewer.visible.get(npc.spec.definition().uuid());
            if (presented == null || !presented.spawnFinished || presented.retired) { failed.accept("not presented"); return; }
            revision++;
            CompletionStage<Void> shown;
            if (below == null) shown = sendRoster(viewer, presented, player, true);
            else {
                int under = host.host.entityId(below);
                List<Integer> ids = List.of(host.host.entityId(player));
                viewer.carried.put(under, ids); viewer.carriers.put(under, host.host.id(below));
                shown = host.packets.carry(player, under, host.host.realPassengers(host.host.id(below)), List.of(), ids);
            }
            shown.whenComplete((Void nothing, Throwable failure) -> host.views.enqueue(viewer, () -> {
                if (failure == null) attached.run(); else failed.accept(PersistedNpcService.concise(failure));
            }));
        }
        @Override public void detach(P player, PersistedNpcService<P, I>.Value npc) {
            revision++;
            for (ViewerPresenter<P, I>.Viewer viewer : host.views.viewers.values()) {
                ViewerPresenter<P, I>.Presented presented = viewer.visible.get(npc.spec.definition().uuid());
                if (presented != null && presented.spawnFinished && !presented.retired) {
                    if (!presented.sentRoster.isEmpty() || occupant(npc.spec.definition().uuid()) != null) sendRoster(viewer, presented, occupant(npc.spec.definition().uuid()), true);
                }
                carries(viewer, Integer.MAX_VALUE, true);
            }
        }
        @Override public void place(P player, Vec3 feet) { host.packets.placeRider(player, feet.x(), feet.y(), feet.z()); }
        @Override public WorldPosition ground(PersistedNpcService<P, I>.Value npc) { return MountRuntime.this.ground(npc); }
        @Override public boolean presentedTo(P player, PersistedNpcService<P, I>.Value npc) {
            ViewerPresenter<P, I>.Viewer viewer = host.views.viewers.get(host.host.id(player));
            ViewerPresenter<P, I>.Presented presented = viewer == null ? null : viewer.visible.get(npc.spec.definition().uuid());
            return presented != null && presented.value == npc && presented.spawnFinished && !presented.retired;
        }
        @Override public boolean stacking() { return host.packets.carryAvailable(); }
        @Override public String occupied(PersistedNpcService<P, I>.Value npc) {
            if (riders.riderOf(npc.spec.definition().uuid()) != null) return "someone is riding this NPC";
            if (mounts.passengersOf().containsKey(npc.spec.definition().id())) return "it carries other NPCs";
            return null;
        }
    }
    private final class RiderPresentation implements RiderService.Presentation<P> {
        @Override public BodyTransport.ControlSubscription subscribe(P rider) { return host.packets.subscribeControl(rider); }
        @Override public void attach(P rider, RiderService.Vehicle<P> vehicle, Runnable attached, Consumer<String> failed) {
            ViewerPresenter<P, I>.Viewer viewer = host.views.viewers.get(host.host.id(rider));
            ViewerPresenter<P, I>.Presented presented = viewer == null ? null : viewer.visible.get(vehicle.uuid());
            if (presented == null) { failed.accept("not presented"); return; }
            revision++;
            sendRoster(viewer, presented, rider, true).whenComplete((Void nothing, Throwable failure) -> host.views.enqueue(viewer, () -> {
                if (failure == null) attached.run(); else failed.accept(PersistedNpcService.concise(failure));
            }));
        }
        @Override public void detach(P rider, RiderService.Vehicle<P> vehicle) {
            revision++;
            for (ViewerPresenter<P, I>.Viewer viewer : host.views.viewers.values()) {
                ViewerPresenter<P, I>.Presented presented = viewer.visible.get(vehicle.uuid());
                if (presented != null && presented.spawnFinished && !presented.retired && !presented.sentRoster.isEmpty()) sendRoster(viewer, presented, null, true);
            }
        }
        @Override public void place(P rider, Vec3 feet) { host.packets.placeRider(rider, feet.x(), feet.y(), feet.z()); }
    }
}
