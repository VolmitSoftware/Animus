package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.control.RideLedger;
import art.arcane.animus.core.control.RideSession;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.control.RideSteering;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.ChunkOwnership;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * A real player riding one NPC on Folia. The ride lives on the NPC's owner and moves with the NPC on every handoff. The
 * rider stands at the NPC's feet, so the region that owns the NPC also owns the rider; the rider and its steering input
 * are read only while that is so. Player-side events reach the ride through the NPC's mailbox with the ride's epoch,
 * and the end of a ride hands the rider's own presentation and dismount to the rider's scheduler. Owner only.
 */
final class ActorRide {
    enum Exit { RESTORE_POSITION, LEAVE_POSITION }
    /** Ticks a ride waits for its rider's region to hold the NPC again before it ends. */
    private static final int RIDER_AWAY_TICKS = 40, RIDE_CELLS = 1024, STAND_CELLS = 256;
    /** A rider farther than this from the NPC's feet was moved by something else, such as Folia's same-region teleport. */
    private static final double RIDER_DRIFT = 3;
    private static final class Ride {
        final RegionRides.Lease<RegionActor> lease; final RideSession session; final Player player; final BodyTransport.ControlSubscription input; final RideEntry entry;
        int away;
        Ride(RegionRides.Lease<RegionActor> lease, RideSession session, Player player, BodyTransport.ControlSubscription input, RideEntry entry) {
            this.lease = lease; this.session = session; this.player = player; this.input = input; this.entry = entry;
        }
    }
    private final RegionActor actor;
    private final RegionNpcRuntime host;
    private Ride riding;

    ActorRide(RegionActor actor, RegionNpcRuntime host) { this.actor = actor; this.host = host; }

    boolean active() { return riding != null; }
    /** The rider to present on this NPC, from admission until the ride ends. */
    Player rider() { Ride current = riding; return current == null ? null : current.player; }

    /** On the thread that owns both the NPC and the player. Null once admitted, otherwise a message for the player. */
    String request(Player player, RideSettings settings) {
        String unavailable = host.packets.riderUnavailable();
        if (unavailable != null) return "riding is unavailable: " + unavailable;
        if (settings == null) return "this NPC cannot be ridden";
        if (!host.regions.owns(player)) return "you are in another region";
        if (!player.hasPermission(settings.permission())) return "you do not have permission";
        if (!actor.navigable()) return "this NPC is not available";
        if (actor.seated || host.mounts.vehicleOf().containsKey(actor.id)) return "this NPC is itself riding";
        if (riding != null) return "someone else is riding this NPC";
        if (player.isDead() || player.isSleeping() || player.isInsideVehicle() || player.getGameMode() == GameMode.SPECTATOR) return "you cannot mount right now";
        WorldPosition at = actor.state.snapshot().position();
        World world = host.world(at.world());
        if (world == null || !player.getWorld().getUID().toString().equals(at.world())) return "this NPC is in another world";
        RideSteering.Result ground = probe(world).check(at.point(), at.point());
        if (ground.outcome() != RideSteering.Outcome.MOVED) return "no room to mount here (" + ground.detail() + ")";
        RegionRides.Admission<RegionActor> admission = host.rides.admit(player.getUniqueId(), actor.uuid, actor);
        if (admission.rejection() != null) return switch (admission.rejection()) {
            case ALREADY_RIDING -> "you are already riding";
            case OCCUPIED -> "someone else is riding this NPC";
            case CAPACITY -> "the rider limit of " + RideLedger.MAX_RIDERS + " is reached";
        };
        RideSession session = new RideSession(player.getUniqueId(), actor.uuid, at.point(), settings, body().supports(MovementProfile.Mode.FLY), System.nanoTime());
        BodyTransport.ControlSubscription input;
        try { input = host.packets.subscribeControl(player); }
        catch (RuntimeException failure) { host.rides.release(admission.lease()); session.close("input-unavailable"); return "your connection is not ready: " + failure.getMessage(); }
        riding = new Ride(admission.lease(), session, player, input, new RideEntry(host.rideEntry, player.getUniqueId(), at));
        actor.claimForRide();
        return null;
    }
    /** The rider's own client settled the attach packet; the ride starts when this is still the same ride. */
    void attached(UUID generation, boolean succeeded, String failure) {
        Ride current = riding;
        if (current == null || !current.lease.generation().equals(generation)) return;
        if (!succeeded) { end(current, "attach-failed: " + failure, Exit.LEAVE_POSITION); return; }
        if (current.session.attached()) place(current);
    }
    /** A player-side event or command ends the ride with this epoch; an earlier ride's request does nothing. */
    void end(UUID generation, String reason, Exit exit) {
        Ride current = riding;
        if (current != null && current.lease.generation().equals(generation)) end(current, reason, exit);
    }
    /** The NPC itself ends whatever ride it carries. */
    void end(String reason, Exit exit) { Ride current = riding; if (current != null) end(current, reason, exit); }
    String summary() {
        Ride current = riding;
        if (current == null) return "free";
        return current.session.state().name().toLowerCase(Locale.ROOT) + " by " + current.player.getName() + "; steps=" + current.session.steps();
    }
    /** For ride status, from any thread: a racy but harmless read. */
    String line() {
        Ride current = riding;
        if (current == null) return null;
        Vec3 at = actor.publication.snapshot().position().point();
        return current.session.line(current.player.getName(), actor.id, at);
    }

    void tick() {
        Ride current = riding;
        if (current == null) return;
        Player player = current.player;
        // Never read a rider another region holds: wait for the region that holds the NPC to hold the rider again.
        if (!host.regions.owns(player)) { if (++current.away > RIDER_AWAY_TICKS) end(current, "rider-unreachable", Exit.LEAVE_POSITION); return; }
        current.away = 0;
        try {
            String stop = stop(current);
            if (stop != null) { end(current, stop, Exit.RESTORE_POSITION); return; }
            WorldPosition at = actor.state.snapshot().position();
            Location look = player.getLocation();
            RideSession.Step step = current.session.tick(System.nanoTime(), at.point(), actor.yaw, look.getYaw(), look.getPitch(), current.input.latest(), probe(host.world(at.world())));
            if (step.action() == RideSession.Action.STOP) { end(current, step.reason(), RideSession.leavesRiderInPlace(step.reason()) ? Exit.LEAVE_POSITION : Exit.RESTORE_POSITION); return; }
            if (current.session.state() != RideSession.State.RIDING) return;
            if (!step.position().equals(at.point()) && !current.entry.allows(new WorldPosition(at.world(), step.position()))) { end(current, RideEntry.DENIED, Exit.RESTORE_POSITION); return; }
            if (!step.position().equals(at.point()) || step.yaw() != actor.yaw) actor.rideStep(step.position(), step.yaw());
            place(current);
        } catch (RuntimeException failure) {
            host.noteFailure("ride", "npc=" + actor.id, PersistedNpcService.concise(failure));
            end(current, "failed", Exit.RESTORE_POSITION);
        }
    }
    private String stop(Ride current) {
        Player player = current.player;
        if (!player.isOnline()) return "offline";
        if (player.isDead()) return "death";
        if (!actor.state.snapshot().active() || !actor.navigable()) return "npc-retired";
        if (actor.seated) return "npc-mounted";
        if (!player.hasPermission(current.session.settings().permission())) return "permission-revoked";
        if (player.isInsideVehicle()) return "real-vehicle";
        if (player.isSleeping() || player.getGameMode() == GameMode.SPECTATOR) return "player-state";
        if (!player.getWorld().getUID().toString().equals(actor.state.snapshot().position().world())) return "world-changed";
        if (!host.presents(player, actor.uuid)) return "npc-not-visible";
        if (current.session.state() == RideSession.State.RIDING
                && RegionViewer.position(player.getLocation()).point().distance(actor.state.snapshot().position().point()) > RIDER_DRIFT) return "teleported";
        return null;
    }
    /** The rider's server position stays at the NPC's feet, in a chunk this thread holds. */
    private void place(Ride current) {
        if (current.session.state() != RideSession.State.RIDING) return;
        Vec3 feet = actor.state.snapshot().position().point();
        Location at = current.player.getLocation();
        if (at.getX() == feet.x() && at.getY() == feet.y() && at.getZ() == feet.z()) return;
        int chunkX = Math.floorDiv((int) Math.floor(feet.x()), 16), chunkZ = Math.floorDiv((int) Math.floor(feet.z()), 16);
        if (!host.regions.owns(current.player.getWorld(), chunkX, chunkZ, chunkX, chunkZ)) return;
        host.packets.placeRider(current.player, feet.x(), feet.y(), feet.z());
    }
    /**
     * Ends the ride on the NPC's owner. The lease ends first, so nothing re-enters it; then the rider's own scheduler
     * writes the detach to the rider's client before any dismount teleport, and other viewers follow the publication.
     */
    private void end(Ride current, String reason, Exit exit) {
        if (riding != current) return;
        riding = null;
        host.rides.release(current.lease);
        current.session.close(reason);
        current.input.close();
        boolean held = host.regions.owns(current.player);
        WorldPosition target = exit == Exit.RESTORE_POSITION && held && current.player.isOnline() && !current.player.isDead() ? standingPlace() : null;
        actor.rideReleased();
        boolean quiet = "shift".equals(reason) || "shutdown".equals(reason) || "death".equals(reason) || "offline".equals(reason);
        host.dismounted(current.player, actor, target, quiet ? null : RichText.colorize(Palette.reply("Dismounted: " + reason.replace('-', ' '))));
    }
    private WorldPosition standingPlace() {
        WorldPosition at = actor.state.snapshot().position();
        World world = host.world(at.world());
        if (world == null) return at;
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        RegionNpcRuntime.Geometry player = host.geometry(BodyRules.PLAYER);
        RideSteering.Probe stand = RideSteering.probe(BodyMobility.forBody(BodyRules.PLAYER, player.width(), player.height()), STAND_CELLS, LiveMovementProbe.owned(world, owned));
        for (Vec3 candidate : RideSteering.dismountCandidates(at.point(), actor.yaw, width() / 2)) {
            RideSteering.Result placed = stand.check(candidate, candidate);
            if (placed.outcome() == RideSteering.Outcome.MOVED && placed.grounded()) return new WorldPosition(at.world(), placed.position());
        }
        return at;
    }
    private RideSteering.Probe probe(World world) {
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        return RideSteering.probe(body(), RIDE_CELLS, LiveMovementProbe.owned(world, owned));
    }
    private double width() { return Math.max(actor.hullWidth(), host.geometry(BodyRules.PLAYER).width()); }
    private MovementProfile body() {
        RegionNpcRuntime.Geometry player = host.geometry(BodyRules.PLAYER);
        double height = host.geometry(actor.spec.type()).seat().riderClearance(actor.hullHeight(), player.seat().vehicleAttachmentY(), player.height());
        return BodyMobility.forBody(actor.spec.type(), width(), height);
    }
}
