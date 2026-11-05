package art.arcane.animus.core.runtime;

import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.RideLedger;
import art.arcane.animus.core.control.RideSession;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.control.RideSteering;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Authoritative real-player rider sessions on the owner thread. The rider's client is told it is a passenger
 * by packets alone and follows the virtual body. Its server position stays at the body's supported feet: a seat
 * position would read as floating to vanilla's flight check, because a riding client keeps sending rotation packets.
 */
final class RiderService<P, I> implements AutoCloseable {
    /** One accepted active NPC. Only the owner thread may call these, and {@link #current()} revokes a stale handle. */
    interface Vehicle<P> {
        UUID uuid();
        String id();
        RideSettings settings();
        VirtualNpcState.Snapshot snapshot();
        float yaw();
        boolean current();
        boolean mounted();
        boolean presentedTo(P rider);
        double hullWidth();
        double hullHeight();
        /** What the body can do when a player steers it, sized to {@link #hullWidth()} and {@link #hullHeight()}. */
        MovementProfile body();
        /** Takes the body from any route or conflicting movement owner. */
        void claim();
        /** Publishes one accepted step before any packet is sent. */
        void commit(Vec3 position, float yaw);
        /**
         * The ride ended and its detach is written. A saddled body is steered by the rider's own client, which ignores
         * server positions while it rides, so every viewer needs the authoritative position again; otherwise the
         * rider's client keeps the body where it predicted it and later clicks miss the real one.
         */
        void released();
    }
    /** The presentation side, kept with viewer state. */
    interface Presentation<P> {
        BodyTransport.ControlSubscription subscribe(P rider);
        /** Runs {@code attached} or {@code failed} on the owner thread once the rider's own attach packet settled. */
        void attach(P rider, Vehicle<P> vehicle, Runnable attached, Consumer<String> failed);
        /** Writes the detach to every viewer now, so that a teleport issued afterwards reaches clients behind it. */
        void detach(P rider, Vehicle<P> vehicle);
        void place(P rider, Vec3 feet);
    }
    private enum Exit { RESTORE_POSITION, LEAVE_POSITION }
    private final class Ride {
        final RideSession session; final P player; final Vehicle<P> vehicle; final BodyTransport.ControlSubscription input;
        Ride(RideSession session, P player, Vehicle<P> vehicle, BodyTransport.ControlSubscription input) {
            this.session = session; this.player = player; this.vehicle = vehicle; this.input = input;
        }
    }
    private static final int MOTION_CELLS = 1024, STAND_CELLS = 256;
    private static final double PLAYER_WIDTH = 0.6, PLAYER_HEIGHT = 1.8;
    private final RuntimeHost<P, I> host;
    private final Presentation<P> presentation;
    private final String unavailable;
    private final RideLedger ledger = new RideLedger();
    private final Map<UUID, Ride> rides = new HashMap<>();
    private boolean restoring, closed;

    RiderService(RuntimeHost<P, I> host, Presentation<P> presentation, String unavailable) {
        this.host = host; this.presentation = presentation; this.unavailable = unavailable;
    }

    int count() { return ledger.size(); }
    boolean available() { return unavailable == null && !closed; }
    String unavailable() { return closed ? "closed" : unavailable; }
    boolean restoring() { return restoring; }
    boolean riding(P player) { return rides.containsKey(host.id(player)); }
    /** The real rider to present on this vehicle, or null. */
    P riderOf(UUID vehicle) {
        RideSession session = ledger.ofNpc(vehicle);
        Ride ride = session == null ? null : rides.get(session.rider());
        return ride == null ? null : ride.player;
    }
    String describe(UUID vehicle) {
        RideSession session = ledger.ofNpc(vehicle);
        if (session == null) return "free";
        Ride ride = rides.get(session.rider());
        return session.state().name().toLowerCase(Locale.ROOT) + " by " + (ride == null ? session.rider() : host.name(ride.player)) + "; steps=" + session.steps();
    }
    List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Ride ride : rides.values()) {
            Vec3 at = ride.vehicle.snapshot().position().point();
            lines.add(host.name(ride.player) + " on " + ride.vehicle.id() + " state=" + ride.session.state() + " generation=" + ride.session.generation()
                    + " steps=" + ride.session.steps() + " x=" + at.x() + " y=" + at.y() + " z=" + at.z());
        }
        return lines;
    }

    /** @return null once the ride is admitted, otherwise a message for the player */
    String request(P player, Vehicle<P> vehicle) {
        if (!available()) return "riding is unavailable: " + unavailable();
        RideSettings settings = vehicle.settings();
        if (settings == null) return "this NPC cannot be ridden";
        if (!host.permitted(player, settings.permission())) return "you do not have permission";
        if (!vehicle.current() || !vehicle.snapshot().active()) return "this NPC is not available";
        if (vehicle.mounted()) return "this NPC is itself riding";
        if (host.dead(player) || host.sleeping(player) || host.inVehicle(player) || host.spectator(player)) return "you cannot mount right now";
        Vec3 feet = vehicle.snapshot().position().point();
        String world = host.position(player).world();
        if (!world.equals(vehicle.snapshot().position().world())) return "this NPC is in another world";
        RideSteering.Result ground = probe(world, vehicle).check(feet, feet);
        if (ground.outcome() != RideSteering.Outcome.MOVED) return "the NPC needs ground or open space with room for its rider (" + ground.detail() + ")";
        RideLedger.Admission admission = ledger.admit(host.id(player), vehicle.uuid(), feet, settings,
                vehicle.body().supports(MovementProfile.Mode.FLY), System.nanoTime());
        if (admission.rejection() != null) return switch (admission.rejection()) {
            case ALREADY_RIDING -> "you are already riding";
            case OCCUPIED -> "someone else is riding this NPC";
            case CAPACITY -> "the rider limit of " + RideLedger.MAX_RIDERS + " is reached";
        };
        RideSession session = admission.session();
        BodyTransport.ControlSubscription input;
        try { input = presentation.subscribe(player); }
        catch (RuntimeException failure) { ledger.remove(session); session.close("input-unavailable"); return "your connection is not ready: " + failure.getMessage(); }
        Ride ride = new Ride(session, player, vehicle, input);
        UUID id = host.id(player);
        rides.put(id, ride);
        vehicle.claim();
        presentation.attach(player, vehicle, () -> {
            if (session.attached()) place(ride);
        }, (String reason) -> {
            if (rides.get(id) == ride) end(ride, "attach-failed", Exit.LEAVE_POSITION);
        });
        return null;
    }

    void tick() {
        if (rides.isEmpty()) return;
        long now = System.nanoTime();
        for (Ride ride : new ArrayList<>(rides.values())) {
            try {
                String stop = lifecycleStop(ride);
                if (stop != null) { end(ride, stop, Exit.RESTORE_POSITION); continue; }
                steer(ride, now);
            } catch (RuntimeException failure) {
                host.warn("Rider session failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                end(ride, "failed", Exit.RESTORE_POSITION);
            }
        }
    }
    private String lifecycleStop(Ride ride) {
        P player = ride.player;
        if (!host.online(player)) return "offline";
        if (host.dead(player)) return "death";
        if (!ride.vehicle.current()) return "definition-changed";
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        if (!npc.active()) return "npc-retired";
        if (ride.vehicle.mounted()) return "npc-mounted";
        if (!host.permitted(player, ride.session.settings().permission())) return "permission-revoked";
        if (host.inVehicle(player)) return "real-vehicle";
        if (host.sleeping(player) || host.spectator(player)) return "player-state";
        if (!host.position(player).world().equals(npc.position().world())) return "world-changed";
        if (!ride.vehicle.presentedTo(player)) return "npc-not-visible";
        return null;
    }
    private void steer(Ride ride, long now) {
        String world = host.position(ride.player).world();
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        Vec3 position = npc.position().point();
        ControlInbox.Snapshot input = ride.input.latest();
        RideSession.Step step = ride.session.tick(now, position, ride.vehicle.yaw(), host.yaw(ride.player),
                host.pitch(ride.player), input, probe(world, ride.vehicle));
        if (step.action() == RideSession.Action.STOP) { end(ride, step.reason(), RideSession.leavesRiderInPlace(step.reason()) ? Exit.LEAVE_POSITION : Exit.RESTORE_POSITION); return; }
        if (ride.session.state() != RideSession.State.RIDING) return;
        if (!step.position().equals(position) || step.yaw() != ride.vehicle.yaw()) ride.vehicle.commit(step.position(), step.yaw());
        place(ride);
    }
    /** Runs every tick so that a late movement packet from before the client mounted cannot move the rider away. */
    private void place(Ride ride) {
        Vec3 feet = ride.vehicle.snapshot().position().point();
        Vec3 at = host.position(ride.player).point();
        if (at.x() != feet.x() || at.y() != feet.y() || at.z() != feet.z()) presentation.place(ride.player, feet);
    }
    private RideSteering.Probe probe(String world, Vehicle<P> vehicle) {
        return RideSteering.probe(vehicle.body(), MOTION_CELLS, host.collision(world));
    }

    /** A vehicle was removed or replaced. Presentation is still live here, so the rider is detached and put back on the ground. */
    void vehicleGone(UUID vehicle, String reason) {
        RideSession session = ledger.ofNpc(vehicle);
        Ride ride = session == null ? null : rides.get(session.rider());
        if (ride != null) end(ride, reason, Exit.RESTORE_POSITION);
    }
    /** Quit, death, foreign teleport or world change: the destination already belongs to someone else. */
    void playerLeaving(P player, String reason) {
        Ride ride = rides.get(host.id(player));
        if (ride != null) end(ride, reason, Exit.LEAVE_POSITION);
    }
    /** The viewer's presentation is being rebuilt or failed while the player stays online. */
    void viewerLost(P player) {
        Ride ride = rides.get(host.id(player));
        if (ride != null) end(ride, "viewer-lost", Exit.RESTORE_POSITION);
    }
    boolean stop(String playerName, String reason) {
        for (Ride ride : new ArrayList<>(rides.values()))
            if (host.name(ride.player).equalsIgnoreCase(playerName)) { end(ride, reason, Exit.RESTORE_POSITION); return true; }
        return false;
    }

    private void end(Ride ride, String reason, Exit exit) {
        // Every revocation happens before any callback or packet, so nothing can re-enter a live session.
        UUID id = host.id(ride.player);
        if (rides.get(id) != ride) return;
        rides.remove(id);
        ledger.remove(ride.session);
        ride.session.close(reason);
        ride.input.close();
        try { presentation.detach(ride.player, ride.vehicle); }
        catch (RuntimeException failure) { host.warn("Rider detach failed: " + failure.getMessage()); }
        ride.vehicle.released();
        if (exit == Exit.RESTORE_POSITION && host.online(ride.player) && !host.dead(ride.player)) restore(ride);
        if (host.online(ride.player) && !"shift".equals(reason) && !"shutdown".equals(reason) && !"death".equals(reason) && !"offline".equals(reason))
            host.tell(ride.player, "Dismounted: " + reason);
    }
    /** The client stops following once the detach arrives, then applies this ordinary teleport, so both sides agree again. */
    private void restore(Ride ride) {
        P player = ride.player;
        WorldPosition target = standingPlace(ride, host.position(player));
        restoring = true;
        try {
            if (!host.teleport(player, target, host.yaw(player), host.pitch(player)))
                host.warn("Dismount teleport was cancelled for " + host.name(player));
        } catch (RuntimeException failure) {
            host.warn("Dismount teleport failed for " + host.name(player) + ": " + failure.getMessage());
        } finally { restoring = false; }
    }
    private WorldPosition standingPlace(Ride ride, WorldPosition from) {
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        Vec3 feet = npc.position().point();
        if (!from.world().equals(npc.position().world())) return from;
        WorldPosition fallback = new WorldPosition(from.world(), feet);
        RideSteering.Probe stand = RideSteering.probe(BodyMobility.forBody("minecraft:player", PLAYER_WIDTH, PLAYER_HEIGHT), STAND_CELLS,
                host.collision(from.world()));
        for (Vec3 candidate : RideSteering.dismountCandidates(feet, ride.vehicle.yaw(), ride.vehicle.hullWidth() / 2)) {
            RideSteering.Result placed = stand.check(candidate, candidate);
            if (placed.outcome() == RideSteering.Outcome.MOVED && placed.grounded()) return new WorldPosition(from.world(), placed.position());
        }
        return fallback;
    }

    @Override public void close() {
        for (Ride ride : new ArrayList<>(rides.values())) end(ride, "shutdown", Exit.RESTORE_POSITION);
        closed = true;
    }
}
