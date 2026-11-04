package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.RideLedger;
import art.arcane.animus.core.control.RideSession;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.control.RideSteering;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Authoritative real-player rider sessions on the Bukkit owner thread. The rider's client is told it is a passenger
 * by packets alone and follows the virtual body. Its server position stays at the body's supported feet: a seat
 * position would read as floating to vanilla's flight check, because a riding client keeps sending rotation packets.
 */
final class RiderService implements AutoCloseable {
    /** One accepted active NPC. Only the owner thread may call these, and {@link #current()} revokes a stale handle. */
    interface Vehicle {
        UUID uuid();
        String id();
        RideSettings settings();
        VirtualNpcState.Snapshot snapshot();
        float yaw();
        boolean current();
        boolean mounted();
        boolean presentedTo(Player rider);
        double hullWidth();
        double hullHeight();
        /** Takes the body from any route or conflicting movement owner. */
        void claim();
        /** Publishes one accepted step before any packet is sent. */
        void commit(Vec3 position, float yaw);
    }
    /** The presentation side, kept with viewer state. */
    interface Presentation {
        NativePacketAdapter.ControlSubscription subscribe(Player rider);
        /** Runs {@code attached} or {@code failed} on the owner thread once the rider's own attach packet settled. */
        void attach(Player rider, Vehicle vehicle, Runnable attached, java.util.function.Consumer<String> failed);
        /** Writes the detach to every viewer now, so that a teleport issued afterwards reaches clients behind it. */
        void detach(Player rider, Vehicle vehicle);
        void place(Player rider, Vec3 feet);
    }
    private enum Exit { RESTORE_POSITION, LEAVE_POSITION }
    private static final class Ride {
        final RideSession session; final Player player; final Vehicle vehicle; final NativePacketAdapter.ControlSubscription input;
        Ride(RideSession session, Player player, Vehicle vehicle, NativePacketAdapter.ControlSubscription input) {
            this.session = session; this.player = player; this.vehicle = vehicle; this.input = input;
        }
    }
    private static final int MOTION_CELLS = 1024, STAND_CELLS = 256;
    private static final double PLAYER_WIDTH = 0.6, PLAYER_HEIGHT = 1.8;
    private final JavaPlugin plugin;
    private final Presentation presentation;
    private final String unavailable;
    private final RideLedger ledger = new RideLedger();
    private final Map<UUID, Ride> rides = new HashMap<>();
    private boolean restoring, closed;

    RiderService(JavaPlugin plugin, Presentation presentation, String unavailable) {
        this.plugin = plugin; this.presentation = presentation; this.unavailable = unavailable;
    }

    int count() { return ledger.size(); }
    boolean available() { return unavailable == null && !closed; }
    String unavailable() { return closed ? "closed" : unavailable; }
    boolean restoring() { return restoring; }
    boolean riding(Player player) { return rides.containsKey(player.getUniqueId()); }
    /** The real rider to present on this vehicle, or null. */
    Player riderOf(UUID vehicle) {
        RideSession session = ledger.ofNpc(vehicle);
        Ride ride = session == null ? null : rides.get(session.rider());
        return ride == null ? null : ride.player;
    }
    String describe(UUID vehicle) {
        RideSession session = ledger.ofNpc(vehicle);
        if (session == null) return "free";
        Ride ride = rides.get(session.rider());
        return session.state().name().toLowerCase(java.util.Locale.ROOT) + " by " + (ride == null ? session.rider() : ride.player.getName()) + "; steps=" + session.steps();
    }
    List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Ride ride : rides.values()) {
            Vec3 at = ride.vehicle.snapshot().position().point();
            lines.add(ride.player.getName() + " on " + ride.vehicle.id() + " state=" + ride.session.state() + " generation=" + ride.session.generation()
                    + " steps=" + ride.session.steps() + " x=" + at.x() + " y=" + at.y() + " z=" + at.z());
        }
        return lines;
    }

    /** @return null once the ride is admitted, otherwise a message for the player */
    String request(Player player, Vehicle vehicle) {
        if (!available()) return "riding is unavailable: " + unavailable();
        RideSettings settings = vehicle.settings();
        if (settings == null) return "this NPC cannot be ridden";
        if (!player.hasPermission(settings.permission())) return "you do not have permission";
        if (!vehicle.current() || !vehicle.snapshot().active()) return "this NPC is not available";
        if (vehicle.mounted()) return "this NPC is itself riding";
        if (player.isDead() || player.isSleeping() || player.isInsideVehicle() || player.getGameMode() == GameMode.SPECTATOR) return "you cannot mount right now";
        Vec3 feet = vehicle.snapshot().position().point();
        World world = player.getWorld();
        if (!world.getUID().toString().equals(vehicle.snapshot().position().world())) return "this NPC is in another world";
        MotionValidator.Result ground = probe(world, vehicle).check(feet, feet, true);
        if (!ground.clear()) return "the NPC must stand on full-block ground with clear space (" + ground.detail() + ")";
        RideLedger.Admission admission = ledger.admit(player.getUniqueId(), vehicle.uuid(), feet, settings, System.nanoTime());
        if (admission.rejection() != null) return switch (admission.rejection()) {
            case ALREADY_RIDING -> "you are already riding";
            case OCCUPIED -> "someone else is riding this NPC";
            case CAPACITY -> "the rider limit of " + RideLedger.MAX_RIDERS + " is reached";
        };
        RideSession session = admission.session();
        NativePacketAdapter.ControlSubscription input;
        try { input = presentation.subscribe(player); }
        catch (RuntimeException failure) { ledger.remove(session); session.close("input-unavailable"); return "your connection is not ready: " + failure.getMessage(); }
        Ride ride = new Ride(session, player, vehicle, input);
        rides.put(player.getUniqueId(), ride);
        vehicle.claim();
        presentation.attach(player, vehicle, () -> {
            if (session.attached()) place(ride);
        }, reason -> {
            if (rides.get(player.getUniqueId()) == ride) end(ride, "attach-failed", Exit.LEAVE_POSITION);
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
                plugin.getLogger().warning("Rider session failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                end(ride, "failed", Exit.RESTORE_POSITION);
            }
        }
    }
    private String lifecycleStop(Ride ride) {
        Player player = ride.player;
        if (!player.isOnline()) return "offline";
        if (player.isDead()) return "death";
        if (!ride.vehicle.current()) return "definition-changed";
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        if (!npc.active()) return "npc-retired";
        if (ride.vehicle.mounted()) return "npc-mounted";
        if (!player.hasPermission(ride.session.settings().permission())) return "permission-revoked";
        if (player.isInsideVehicle()) return "real-vehicle";
        if (player.isSleeping() || player.getGameMode() == GameMode.SPECTATOR) return "player-state";
        if (!player.getWorld().getUID().toString().equals(npc.position().world())) return "world-changed";
        if (!ride.vehicle.presentedTo(player)) return "npc-not-visible";
        return null;
    }
    private void steer(Ride ride, long now) {
        World world = ride.player.getWorld();
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        Vec3 position = npc.position().point();
        ControlInbox.Snapshot input = ride.input.latest();
        RideSession.Step step = ride.session.tick(now, position, ride.vehicle.yaw(), ride.player.getLocation().getYaw(), input, probe(world, ride.vehicle));
        if (step.action() == RideSession.Action.STOP) { end(ride, step.reason(), Exit.RESTORE_POSITION); return; }
        if (ride.session.state() != RideSession.State.RIDING) return;
        if (!step.position().equals(position) || step.yaw() != ride.vehicle.yaw()) ride.vehicle.commit(step.position(), step.yaw());
        place(ride);
    }
    /** Runs every tick so that a late movement packet from before the client mounted cannot move the rider away. */
    private void place(Ride ride) {
        Vec3 feet = ride.vehicle.snapshot().position().point();
        Location at = ride.player.getLocation();
        if (at.getX() != feet.x() || at.getY() != feet.y() || at.getZ() != feet.z()) presentation.place(ride.player, feet);
    }
    private static RideSteering.Probe probe(World world, Vehicle vehicle) {
        return RideSteering.probe(vehicle.hullWidth(), vehicle.hullHeight(), MOTION_CELLS, LiveMovementProbe.sampler(world));
    }

    /** A vehicle was removed or replaced. Presentation is still live here, so the rider is detached and put back on the ground. */
    void vehicleGone(UUID vehicle, String reason) {
        RideSession session = ledger.ofNpc(vehicle);
        Ride ride = session == null ? null : rides.get(session.rider());
        if (ride != null) end(ride, reason, Exit.RESTORE_POSITION);
    }
    /** Quit, death, foreign teleport or world change: the destination already belongs to someone else. */
    void playerLeaving(Player player, String reason) {
        Ride ride = rides.get(player.getUniqueId());
        if (ride != null) end(ride, reason, Exit.LEAVE_POSITION);
    }
    /** The viewer's presentation is being rebuilt or failed while the player stays online. */
    void viewerLost(Player player) {
        Ride ride = rides.get(player.getUniqueId());
        if (ride != null) end(ride, "viewer-lost", Exit.RESTORE_POSITION);
    }
    boolean stop(String playerName, String reason) {
        for (Ride ride : new ArrayList<>(rides.values()))
            if (ride.player.getName().equalsIgnoreCase(playerName)) { end(ride, reason, Exit.RESTORE_POSITION); return true; }
        return false;
    }

    private void end(Ride ride, String reason, Exit exit) {
        // Every revocation happens before any callback or packet, so nothing can re-enter a live session.
        if (rides.get(ride.player.getUniqueId()) != ride) return;
        rides.remove(ride.player.getUniqueId());
        ledger.remove(ride.session);
        ride.session.close(reason);
        ride.input.close();
        try { presentation.detach(ride.player, ride.vehicle); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Rider detach failed: " + failure.getMessage()); }
        if (exit == Exit.RESTORE_POSITION && ride.player.isOnline() && !ride.player.isDead()) restore(ride);
        if (ride.player.isOnline() && !"shift".equals(reason) && !"shutdown".equals(reason) && !"death".equals(reason) && !"offline".equals(reason))
            ride.player.sendMessage("Dismounted: " + reason);
    }
    /** The client stops following once the detach arrives, then applies this ordinary teleport, so both sides agree again. */
    private void restore(Ride ride) {
        Player player = ride.player;
        Location from = player.getLocation();
        Location target = standingPlace(ride, from);
        restoring = true;
        try {
            if (!player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN))
                plugin.getLogger().warning("Dismount teleport was cancelled for " + player.getName());
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Dismount teleport failed for " + player.getName() + ": " + failure.getMessage());
        } finally { restoring = false; }
    }
    private Location standingPlace(Ride ride, Location from) {
        World world = from.getWorld();
        VirtualNpcState.Snapshot npc = ride.vehicle.snapshot();
        Vec3 feet = npc.position().point();
        Location fallback = new Location(world, feet.x(), feet.y(), feet.z(), from.getYaw(), from.getPitch());
        if (world == null || !world.getUID().toString().equals(npc.position().world())) return from;
        RideSteering.Probe stand = RideSteering.probe(PLAYER_WIDTH, PLAYER_HEIGHT, STAND_CELLS, LiveMovementProbe.sampler(world));
        for (Vec3 candidate : RideSteering.dismountCandidates(feet, ride.vehicle.yaw(), ride.vehicle.hullWidth() / 2)) {
            if (stand.check(candidate, candidate, true).clear())
                return new Location(world, candidate.x(), candidate.y(), candidate.z(), from.getYaw(), from.getPitch());
        }
        return fallback;
    }

    @Override public void close() {
        for (Ride ride : new ArrayList<>(rides.values())) end(ride, "shutdown", Exit.RESTORE_POSITION);
        closed = true;
    }
}
