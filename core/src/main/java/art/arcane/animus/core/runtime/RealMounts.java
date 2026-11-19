package art.arcane.animus.core.runtime;

import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * NPCs that ride real server entities: a horse a player leads, a boat, a minecart, any mob. Each tick the NPC sits where
 * its vehicle seats a passenger now, in the vehicle's world, and every viewer is shown it on the vehicle's own passenger
 * list (see {@link MountRuntime}). A vehicle that dies, unloads or is removed lets the NPC go: it stands at its own
 * position again and waits for the vehicle, or for the next one of its type. One NPC rides each real entity. Owner thread.
 */
final class RealMounts<P, I> {
    /** Why a server whose transport cannot carry bodies on entities has no mob riding. */
    static final String NO_CARRY = "NPCs ride mobs only on a server build that shows NPCs on real entities";
    /** Ticks between two searches for the nearest vehicle of a type while an NPC has none. */
    static final int SEARCH_TICKS = 20;
    final class Binding {
        final PersistedNpcService<P, I>.Value value;
        final MountDeclaration.EntityMount declared;
        /** The vehicle the NPC sits on now, as read this tick; null while it waits for one. */
        RuntimeHost.RealEntity vehicle;
        long nextSearch;
        String problem;
        Binding(PersistedNpcService<P, I>.Value value, MountDeclaration.EntityMount declared) { this.value = value; this.declared = declared; }
    }
    private final PersistedNpcService<P, I> service;
    /** In activation order, so NPCs looking for the same kind of vehicle take turns the same way every run. */
    private final Map<PersistedNpcService<P, I>.Value, Binding> bindings = new java.util.LinkedHashMap<>();
    /** Bumped when an NPC gets on or off a vehicle, so each viewer's real rosters are worked out again. */
    long revision;

    RealMounts(PersistedNpcService<P, I> service) { this.service = service; }

    void activated(PersistedNpcService<P, I>.Value value) {
        MountDeclaration.entity(value.spec.definition().json()).ifPresent((MountDeclaration.EntityMount declared) -> bindings.put(value, new Binding(value, declared)));
    }
    void removed(PersistedNpcService<P, I>.Value value) { if (bindings.remove(value) != null) revision++; }
    void clear() { bindings.clear(); revision++; }

    int attachedCount() { int count = 0; for (Binding binding : bindings.values()) if (binding.vehicle != null) count++; return count; }
    int problemCount() { int count = 0; for (Binding binding : bindings.values()) if (binding.problem != null) count++; return count; }
    /** The vehicle this NPC sits on now, or null. */
    RuntimeHost.RealEntity vehicle(PersistedNpcService<P, I>.Value value) { Binding binding = bindings.get(value); return binding == null ? null : binding.vehicle; }
    List<Binding> attached() {
        List<Binding> riding = new ArrayList<>();
        for (Binding binding : bindings.values()) if (binding.vehicle != null) riding.add(binding);
        return riding;
    }
    String summary(PersistedNpcService<P, I>.Value value) {
        Binding binding = bindings.get(value);
        if (binding == null) return "";
        return binding.vehicle != null ? "; mounted-on=" + binding.vehicle.type() + " " + binding.vehicle.uuid()
                : "; mount=" + binding.declared.describe() + (binding.problem == null ? "" : "; mount-problem=" + binding.declared.field() + ": " + binding.problem);
    }

    /** Before passengers follow their vehicles: every NPC on a real vehicle takes its seat, gets on or lets go. */
    void tick() {
        if (bindings.isEmpty()) return;
        RuntimeHost<P, I> host = service.host;
        String unavailable = host.realVehiclesUnavailable();
        if (unavailable == null && !service.packets.carryAvailable()) unavailable = NO_CARRY;
        for (Binding binding : bindings.values()) {
            if (!binding.value.state.snapshot().active()) { if (binding.vehicle != null) release(binding, "the NPC is not active"); continue; }
            if (unavailable != null) { if (binding.vehicle != null) release(binding, unavailable); binding.problem = unavailable; continue; }
            Optional<RuntimeHost.RealEntity> found;
            try { found = find(binding); }
            catch (RuntimeException failure) {
                // One unreadable vehicle stops only its own NPC's mount, never the runtime.
                String why = "the vehicle cannot be read: " + PersistedNpcService.concise(failure);
                if (binding.vehicle != null) release(binding, why); else binding.problem = why;
                continue;
            }
            if (found.isEmpty()) {
                if (binding.vehicle != null) release(binding, binding.declared.uuid() != null ? "entity " + binding.declared.uuid() + " is gone or not loaded" : "its " + binding.vehicle.type() + " is gone");
                else if (binding.problem == null) binding.problem = binding.declared.uuid() != null ? "entity " + binding.declared.uuid() + " is not loaded"
                        : "no free " + binding.declared.type() + " within " + blocks(binding.declared.range()) + " blocks";
                continue;
            }
            sit(binding, found.get());
        }
    }

    private Optional<RuntimeHost.RealEntity> find(Binding binding) {
        RuntimeHost<P, I> host = service.host;
        UUID wanted = binding.declared.uuid() != null ? binding.declared.uuid() : binding.vehicle == null ? null : binding.vehicle.uuid();
        if (wanted != null) {
            Optional<RuntimeHost.RealEntity> known = host.entity(wanted);
            if (known.isPresent() && (binding.declared.type() == null || known.get().type().equals(binding.declared.type())) && free(known.get().uuid(), binding)) return known;
            if (binding.declared.uuid() != null) {
                if (known.isPresent() && !free(known.get().uuid(), binding)) binding.problem = "entity " + wanted + " already carries another NPC";
                return Optional.empty();
            }
        }
        if (service.tickNumber < binding.nextSearch) return Optional.empty();
        binding.nextSearch = service.tickNumber + SEARCH_TICKS;
        return host.nearestEntity(binding.value.spec.position(), binding.declared.type(), binding.declared.range(), (UUID candidate) -> free(candidate, binding));
    }
    private static String blocks(double range) { return range == Math.rint(range) ? String.valueOf((long) range) : String.valueOf(range); }
    private boolean free(UUID vehicle, Binding asking) {
        for (Binding other : bindings.values()) if (other != asking && other.vehicle != null && other.vehicle.uuid().equals(vehicle)) return false;
        return true;
    }

    private void sit(Binding binding, RuntimeHost.RealEntity vehicle) {
        PersistedNpcService<P, I>.Value value = binding.value;
        boolean boarding = binding.vehicle == null || !binding.vehicle.uuid().equals(vehicle.uuid()) || binding.vehicle.entityId() != vehicle.entityId();
        binding.vehicle = vehicle; binding.problem = null;
        if (boarding) {
            revision++;
            service.reads.cancelRoute(value); value.waypoints = null;
            service.routes.stopRoute(value, "unavailable: this NPC rides a " + vehicle.type());
        }
        Vec3 seat = vehicle.seat().passengerFeet(vehicle.position().point(), vehicle.yaw(), value.seat.vehicleAttachmentY());
        WorldPosition at = new WorldPosition(vehicle.position().world(), seat);
        if (!at.equals(value.state.snapshot().position())) {
            value.state.move(at); service.index.upsert(value.state.snapshot()); value.poseRevision++;
        }
        PersistedNpcService.setPose(value, vehicle.yaw(), value.pitch, false);
    }
    /** The NPC gets off: it stands at its own position again, as one whose NPC vehicle is gone does. */
    private void release(Binding binding, String why) {
        PersistedNpcService<P, I>.Value value = binding.value;
        binding.vehicle = null; binding.problem = why; revision++;
        binding.nextSearch = service.tickNumber + SEARCH_TICKS;
        value.state.move(value.spec.position()); service.index.upsert(value.state.snapshot()); value.poseRevision++;
        PersistedNpcService.setPose(value, value.spec.yaw(), value.spec.pitch(), true);
    }
}
