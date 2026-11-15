package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.runtime.RuntimeHost;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One NPC on a real server entity on Folia: a horse a player leads, a boat, a minecart, any mob. The entity is read only
 * while the NPC's region owns it; then the NPC sits where the entity seats a passenger now. An entity that is dead,
 * removed, unloaded or in another world lets the NPC go. One on another region also lets it go, and its own scheduler
 * is asked where it seats a passenger; the NPC steps there, which hands it to that region like any chunk crossing, and
 * it gets on again there. One NPC rides each entity across the server. Owner only.
 *
 * @param <E> the server entity
 */
final class ActorVehicle<E> {
    /** What the vehicle binding reads; {@link #entity} may run on any thread, the rest on the NPC's owner. */
    interface Host<E> {
        /** The loaded entity with this UUID, wherever it is; only its owner may read it. */
        Optional<E> entity(UUID uuid);
        boolean owns(E entity);
        /** Owner of the entity: alive, valid, not a player and not another plugin's seat. */
        boolean usable(E entity);
        /** Owner of the entity: where it stands and seats its next passenger, and its own passengers. */
        RuntimeHost.RealEntity read(E entity);
        /** The nearest usable entity of this type within range that {@code free} accepts, searched only when this owner holds the whole area. */
        Optional<E> nearest(WorldPosition near, String type, double range, Predicate<UUID> free);
        /** Reads the entity on its own scheduler and hands the result over; false when that scheduler refused. */
        boolean ask(E entity, Consumer<RuntimeHost.RealEntity> seen);
        /** Takes the entity for this NPC; false when another NPC rides it. Any thread. */
        boolean claim(UUID entity);
        void unclaim(UUID entity);
        boolean free(UUID entity);
    }
    /** What the NPC does with its body this turn. */
    record Turn(Kind kind, WorldPosition at, float yaw) {
        /** BOARD gets on a vehicle, SIT stays on it, RELEASE gets off it, STEP moves onto a seat another region showed. */
        enum Kind { STAY, BOARD, SIT, RELEASE, STEP }
        static final Turn STAY = new Turn(Kind.STAY, null, 0);
    }
    /** Turns between two searches for a vehicle of a type, and between two questions to a vehicle on another region. */
    static final int SEARCH_TURNS = 20;
    /** An answer from another region is used for at most this many turns after it was asked. */
    static final int ANSWER_TURNS = 40;
    private final Host<E> host;
    final MountDeclaration.EntityMount declared;
    private final double attachmentY;
    /** The vehicle the NPC sits on now, as read this turn; null while it waits for one. */
    private RuntimeHost.RealEntity vehicle;
    /** The vehicle on another region the NPC follows, and that region's last answer. */
    private UUID following;
    private volatile RuntimeHost.RealEntity answer;
    private long nextSearch, nextAsk, asked = Long.MIN_VALUE / 2;
    private String problem;

    ActorVehicle(Host<E> host, MountDeclaration.EntityMount declared, double attachmentY) {
        this.host = host; this.declared = declared; this.attachmentY = attachmentY;
    }

    RuntimeHost.RealEntity vehicle() { return vehicle; }
    /**
     * The entity the NPC rides or is about to board: the one it sits on, the declared one, the one on another region it
     * boards again, or the nearest free one of its type near {@code home}. Owner only.
     */
    Optional<UUID> target(WorldPosition home) {
        if (vehicle != null) return Optional.of(vehicle.uuid());
        if (declared.uuid() != null) return Optional.of(declared.uuid());
        if (following != null) return Optional.of(following);
        if (declared.type() == null) return Optional.empty();
        return host.nearest(home, declared.type(), declared.range(), host::free).map((E entity) -> host.read(entity).uuid());
    }
    String summary() {
        return vehicle != null ? "; mounted-on=" + vehicle.type() + " " + vehicle.uuid()
                : "; mount=" + declared.describe() + (problem == null ? "" : "; mount-problem=" + declared.field() + ": " + problem);
    }

    /**
     * One owner turn. {@code unavailable} says why real vehicles cannot be shown here, or null; {@code world} is the
     * NPC's world, which a vehicle may not take it out of on Folia; a vehicle of a type is searched near {@code home}.
     */
    Turn tick(long turn, boolean active, String unavailable, String world, WorldPosition home) {
        if (!active) return vehicle != null ? release("the NPC is not active") : Turn.STAY;
        if (unavailable != null) return vehicle != null ? release(unavailable) : stay(unavailable);
        UUID wanted = declared.uuid() != null ? declared.uuid() : vehicle != null ? vehicle.uuid() : following;
        E found = null;
        if (wanted != null) {
            Optional<E> known = host.entity(wanted);
            if (known.isPresent() && !host.owns(known.get())) return elsewhere(turn, known.get(), wanted);
            E usable = known.filter(host::usable).orElse(null);
            String why = known.isEmpty() ? "entity " + wanted + " is not loaded" : usable == null ? "entity " + wanted + " is gone" : null;
            if (usable != null && !host.free(wanted)) { why = "entity " + wanted + " already carries another NPC"; usable = null; }
            if (usable != null && declared.type() != null && !host.read(usable).type().equals(declared.type())) usable = null;
            found = usable;
            if (found == null && declared.uuid() != null) return vehicle != null ? release(why) : stay(why);
        }
        following = null; answer = null;
        if (found == null) {
            RuntimeHost.RealEntity lost = vehicle;
            if (turn >= nextSearch) {
                nextSearch = turn + SEARCH_TURNS;
                found = host.nearest(home, declared.type(), declared.range(), host::free).orElse(null);
            }
            if (found == null) {
                if (lost != null) return release("its " + lost.type() + " is gone");
                return problem == null ? stay("no free " + declared.type() + " within " + blocks(declared.range()) + " blocks") : Turn.STAY;
            }
        }
        RuntimeHost.RealEntity read = host.read(found);
        if (!read.position().world().equals(world)) return vehicle != null ? release("its " + read.type() + " is in another world") : stay("its " + read.type() + " is in another world");
        boolean boarding = vehicle == null || !vehicle.uuid().equals(read.uuid());
        if (boarding && !host.claim(read.uuid())) return vehicle != null ? release("its " + read.type() + " carries another NPC") : stay("its " + read.type() + " carries another NPC");
        if (boarding && vehicle != null) host.unclaim(vehicle.uuid());
        vehicle = read; problem = null;
        return new Turn(boarding ? Turn.Kind.BOARD : Turn.Kind.SIT, seat(read), read.yaw());
    }
    private WorldPosition seat(RuntimeHost.RealEntity on) {
        return new WorldPosition(on.position().world(), on.seat().passengerFeet(on.position().point(), on.yaw(), attachmentY));
    }
    /** The vehicle is on another region: let go, ask its region where it is, and step onto its seat once that answer is in. */
    private Turn elsewhere(long turn, E entity, UUID uuid) {
        boolean riding = vehicle != null;
        if (riding) host.unclaim(vehicle.uuid());
        vehicle = null; following = uuid;
        problem = "its vehicle is on another region";
        if (turn >= nextAsk) {
            nextAsk = turn + SEARCH_TURNS; asked = turn;
            if (!host.ask(entity, (RuntimeHost.RealEntity seen) -> answer = seen)) problem = "the vehicle's region refused to say where it is";
        }
        RuntimeHost.RealEntity seen = answer;
        if (seen != null && seen.uuid().equals(uuid) && turn - asked <= ANSWER_TURNS && host.free(uuid)) {
            answer = null;
            return new Turn(Turn.Kind.STEP, seat(seen), seen.yaw());
        }
        return riding ? new Turn(Turn.Kind.RELEASE, null, 0) : Turn.STAY;
    }
    private Turn stay(String why) { problem = why; return Turn.STAY; }
    private Turn release(String why) {
        if (vehicle != null) host.unclaim(vehicle.uuid());
        vehicle = null; problem = why;
        return new Turn(Turn.Kind.RELEASE, null, 0);
    }
    /** Authority over the NPC ends here: the claim goes, nothing in the world is touched. */
    void forget() {
        if (vehicle != null) host.unclaim(vehicle.uuid());
        vehicle = null; following = null; answer = null;
    }
    private static String blocks(double range) { return range == Math.rint(range) ? String.valueOf((long) range) : String.valueOf(range); }
}
