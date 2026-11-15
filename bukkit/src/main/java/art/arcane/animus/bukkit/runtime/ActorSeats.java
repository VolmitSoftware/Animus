package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.config.SeatSettings;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.control.SeatModule;
import art.arcane.animus.core.control.SeatRules;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Players sitting on one NPC on Folia, as GSit seats one player on another: a click sits the player on top, a second
 * player sits on the first, sneaking gets off. The seats live on the NPC's owner and move with the NPC on every handoff.
 * A seated player stands on the ground under the NPC, so the region that owns the NPC also owns them; a player is read
 * only while that is so. Player-side events reach a seat through the NPC's mailbox with the seat's generation. Owner only.
 *
 * @param <P> the player
 * @param <A> the NPC's actor, named by each lease
 */
final class ActorSeats<P, A> {
    enum Exit { RESTORE_POSITION, LEAVE_POSITION }
    enum State { ATTACHING, SEATED }
    /** What the seats read and do; every call runs on the NPC's owner. */
    interface Host<P> {
        /** Why seats cannot work here at all, or null. */
        String unavailable();
        SeatSettings settings();
        boolean toggledOn(UUID player);
        RideEntry.Rule entry();
        /** The player rides an NPC through riding. */
        boolean riding(UUID player);
        /** One player can sit on another here. */
        boolean stacking();
        SeatModule offer();
        /** The NPC is active and its definition applied on this owner. */
        boolean available();
        /** Why the NPC takes no seat now: a rider steers it or NPCs ride it; null when it is free. */
        String occupied();
        boolean steered();
        String npcName();
        String npcId();
        WorldPosition feet();
        double height();
        double width();
        float yaw();
        /** The ground under the lowest vehicle of the NPC's mount chain. */
        WorldPosition ground();
        /** Clear ground beside the NPC to get off on, in chunks this owner holds. */
        WorldPosition standingPlace(WorldPosition ground);
        /** This owner holds the chunk under {@code at}. */
        boolean holds(WorldPosition at);

        UUID id(P player);
        String name(P player);
        /** This owner holds the player now; nothing else about a player is read when it does not. */
        boolean owns(P player);
        boolean online(P player);
        boolean dead(P player);
        /** Sleeping, a spectator, or held in a pose by another plugin. */
        boolean busy(P player);
        boolean inVehicle(P player);
        boolean permitted(P player, String node);
        boolean emptyHand(P player);
        WorldPosition position(P player);
        /** GSit's player-sit rules for this seat: null allows, an empty reason refuses silently. */
        String gsit(P player, WorldPosition npc, double height, int stacked);
        BodyTransport.ControlSubscription subscribe(P player);
        /** The player's viewer shows this NPC now. */
        boolean presents(P player);
        /** Keeps the player's server position on the seat's ground. */
        void place(P player, Vec3 feet);
        /** On the player's own scheduler: the seat's end reaches their client, then the teleport and the message. */
        void released(P player, WorldPosition target, String message);
        /** Something viewers show changed. */
        void changed();
    }
    /** Turns a seat waits for the NPC's region to hold its player again before it ends. */
    static final int AWAY_TURNS = 40;
    /** A seated player farther than this from the seat's ground was moved by something else. */
    static final double DRIFT = 3;
    private static final long ATTACH_TIMEOUT_NANOS = 5_000_000_000L;
    final class Seat {
        final P player; final RegionSeats.Lease<A> lease; final int level; final BodyTransport.ControlSubscription input; final RideEntry entry; final long started;
        State state = State.ATTACHING;
        int away;
        Seat(P player, RegionSeats.Lease<A> lease, int level, BodyTransport.ControlSubscription input, RideEntry entry, long started) {
            this.player = player; this.lease = lease; this.level = level; this.input = input; this.entry = entry; this.started = started;
        }
    }
    private final Host<P> host;
    private final RegionSeats<A> ledger;
    private final A self;
    private final UUID npc;
    private final List<Seat> stack = new ArrayList<>();

    ActorSeats(Host<P> host, RegionSeats<A> ledger, A self, UUID npc) { this.host = host; this.ledger = ledger; this.self = self; this.npc = npc; }

    boolean empty() { return stack.isEmpty(); }
    /** The players on this NPC, lowest first. */
    List<P> players() {
        List<P> players = new ArrayList<>(stack.size());
        for (Seat seat : stack) players.add(seat.player);
        return List.copyOf(players);
    }
    /** The lowest seated player, shown as the NPC's own passenger; null when nobody sits. */
    P bottom() { return stack.isEmpty() ? null : stack.getFirst().player; }
    /** The player this one sits on, or null on the NPC itself or when they do not sit here. */
    P below(P player) {
        for (int i = 1; i < stack.size(); i++) if (stack.get(i).player == player) return stack.get(i - 1).player;
        return null;
    }
    /** The generation of this player's seat here, or null. */
    UUID generation(P player) {
        for (Seat seat : stack) if (seat.player == player) return seat.lease.generation();
        return null;
    }
    /** One seat as viewers and status show it, lowest first. */
    record Seated<P>(P player, boolean attached) {}
    List<Seated<P>> seated() {
        List<Seated<P>> shown = new ArrayList<>(stack.size());
        for (Seat seat : stack) shown.add(new Seated<>(seat.player, seat.state == State.SEATED));
        return List.copyOf(shown);
    }

    /** A main-hand click asks for this seat at all; other clicks never reach {@link #request}. */
    boolean asked(boolean sneaking) { return host.offer().sitsOn(sneaking); }

    /**
     * On the thread that owns both the NPC and the player.
     * @return null once the player is being seated, an empty string for a refusal nobody is told of, otherwise why not
     */
    String request(P player) {
        String unavailable = host.unavailable();
        if (unavailable != null) return "seats are unavailable: " + unavailable;
        if (!host.owns(player)) return "you are in another region";
        SeatModule seat = host.offer();
        UUID id = host.id(player);
        // As with GSit: no permission, sitting turned off or an item in hand pass silently.
        if (!host.permitted(player, seat.permission()) || !host.toggledOn(id)) return "";
        if (seat.emptyHand() && !host.emptyHand(player)) return "";
        if (host.dead(player) || host.busy(player) || host.inVehicle(player)) return "you cannot sit right now";
        RegionSeats.Lease<A> already = ledger.of(id);
        // The client can aim at the NPC it sits on; that click is the seat's own and needs no answer.
        if (already != null) return already.npc().equals(npc) ? "" : "you are already sitting";
        if (host.riding(id)) return "you are riding an NPC";
        if (!host.available()) return "this NPC is not available";
        String occupied = host.occupied();
        if (occupied != null) return occupied;
        if (host.steered()) return SeatRules.STEERED;
        WorldPosition at = host.feet();
        if (!host.position(player).world().equals(at.world())) return "this NPC is in another world";
        if (stack.size() >= seat.stack()) return "its seats are full";
        if (!stack.isEmpty() && !host.stacking()) return "its seat is taken";
        int maximum = host.settings().maximum();
        if (ledger.size() >= maximum) return "the server limit of " + maximum + " seated players is reached";
        if (host.settings().followGSit()) {
            String rule = host.gsit(player, at, host.height(), stack.size());
            if (rule != null) return rule;
        }
        WorldPosition ground = host.ground();
        boolean allowed;
        try { allowed = host.entry().mayEnter(id, ground); }
        catch (RuntimeException | LinkageError failure) { allowed = false; }
        if (!allowed) return "region entry denied";
        BodyTransport.ControlSubscription input;
        try { input = host.subscribe(player); }
        catch (RuntimeException failure) { return "your connection is not ready: " + failure.getMessage(); }
        RegionSeats.Admission<A> admission = ledger.admit(id, npc, self, maximum);
        if (admission.rejection() != null) {
            input.close();
            return admission.rejection() == RegionSeats.Rejection.CAPACITY ? "the server limit of " + maximum + " seated players is reached" : "you are already sitting";
        }
        stack.add(new Seat(player, admission.lease(), stack.size(), input, new RideEntry(host.entry(), id, ground), System.nanoTime()));
        host.changed();
        return null;
    }

    /** The seated player's own client settled the attach; the seat starts when it is still the same seat. */
    void attached(UUID generation, boolean succeeded) {
        Seat seat = find(generation);
        if (seat == null || seat.state != State.ATTACHING) return;
        if (!succeeded) { end(seat, "attach-failed", Exit.LEAVE_POSITION); return; }
        seat.state = State.SEATED;
        place(seat);
    }
    /** A player-side event or command ends the seat with this generation; an earlier seat's request does nothing. */
    void end(UUID generation, String reason, Exit exit) {
        Seat seat = find(generation);
        if (seat != null) end(seat, reason, exit);
    }
    /** The NPC itself ends every seat it carries. */
    void endAll(String reason, Exit exit) { if (!stack.isEmpty()) end(stack.getFirst(), reason, exit); }

    /** After the NPC moved this turn, lowest seats first, so a player above one that ends gets off with it. */
    void tick() {
        if (stack.isEmpty()) return;
        long now = System.nanoTime();
        for (Seat seat : List.copyOf(stack)) {
            if (!stack.contains(seat)) continue;
            // Never read a player another region holds: wait for this region to hold them again.
            if (!host.owns(seat.player)) { if (++seat.away > AWAY_TURNS) end(seat, "seat-unreachable", Exit.LEAVE_POSITION); continue; }
            seat.away = 0;
            try {
                String stop = stop(seat, now);
                if (stop != null) { end(seat, stop, SeatRules.leavesInPlace(stop) ? Exit.LEAVE_POSITION : Exit.RESTORE_POSITION); continue; }
                if (seat.state != State.SEATED) continue;
                if (!seat.entry.allows(host.ground())) { end(seat, RideEntry.DENIED, Exit.RESTORE_POSITION); continue; }
                place(seat);
            } catch (RuntimeException failure) {
                end(seat, "failed", Exit.RESTORE_POSITION);
            }
        }
    }
    private String stop(Seat seat, long now) {
        P player = seat.player;
        if (!host.online(player)) return "offline";
        if (host.dead(player)) return "death";
        if (!ledger.current(seat.lease)) return "stack-ended";
        if (!host.available()) return "npc-retired";
        SeatModule offer = host.offer();
        if (!offer.enabled()) return "seat-removed";
        if (!host.permitted(player, offer.permission())) return "permission-revoked";
        if (host.inVehicle(player)) return "real-vehicle";
        if (host.busy(player)) return "player-state";
        WorldPosition at = host.position(player), ground = host.ground();
        if (!at.world().equals(ground.world())) return "world-changed";
        if (stack.indexOf(seat) != seat.level) return "stack-ended";
        ControlInbox.Snapshot input = seat.input.latest();
        if (!input.active()) return input.reason();
        if (seat.state == State.ATTACHING) return now - seat.started > ATTACH_TIMEOUT_NANOS ? "attach-timeout" : null;
        if (!host.presents(player)) return "npc-not-visible";
        if (at.point().distance(ground.point()) > DRIFT) return "teleported";
        return null;
    }
    /** The player's server position stays on the ground under the NPC, in a chunk this owner holds. */
    private void place(Seat seat) {
        if (seat.state != State.SEATED) return;
        WorldPosition ground = host.ground();
        Vec3 at = host.position(seat.player).point(), feet = ground.point();
        if (at.x() == feet.x() && at.y() == feet.y() && at.z() == feet.z()) return;
        if (host.holds(ground)) host.place(seat.player, feet);
    }

    /** Ends a seat and every seat above it, top first, so each detach names only players still seated. */
    private void end(Seat seat, String reason, Exit exit) {
        if (!stack.contains(seat)) return;
        for (int index = stack.size() - 1; index > seat.level; index--) release(stack.get(index), "stack-ended", Exit.RESTORE_POSITION);
        release(seat, reason, exit);
    }
    /**
     * The lease ends first, so nothing re-enters it; the publication changes before the player's scheduler sends the seat's
     * end, so the detach names what viewers should show now; the teleport beside the NPC follows the detach.
     */
    private void release(Seat seat, String reason, Exit exit) {
        stack.remove(seat);
        ledger.release(seat.lease);
        seat.input.close();
        boolean held = host.owns(seat.player);
        WorldPosition target = exit == Exit.RESTORE_POSITION && held && host.online(seat.player) && !host.dead(seat.player) ? host.standingPlace(host.ground()) : null;
        host.changed();
        host.released(seat.player, target, held && host.online(seat.player) ? SeatRules.told(host.npcName(), reason) : null);
    }
    private Seat find(UUID generation) {
        for (Seat seat : stack) if (seat.lease.generation().equals(generation)) return seat;
        return null;
    }
}
