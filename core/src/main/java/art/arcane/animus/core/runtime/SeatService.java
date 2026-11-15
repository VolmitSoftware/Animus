package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.config.SeatSettings;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.control.SeatModule;
import art.arcane.animus.core.control.SeatRules;
import art.arcane.animus.core.control.SeatToggles;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Players sitting on NPCs, as GSit seats one player on another: a click on the NPC sits the player on top of it, a
 * second player sits on the first, and sneaking gets off. Nobody steers; the NPC keeps its route and the players move
 * with it. Each seated player's server position is the ground under the NPC, so vanilla never treats them as flying,
 * and every block that ground enters is asked of the region entry rule, as a ride asks it. Owner thread only.
 */
final class SeatService<P, I> implements AutoCloseable {
    /** The presentation side, kept with viewer state in {@link MountRuntime}. */
    interface Presentation<P, I> {
        BodyTransport.ControlSubscription subscribe(P player);
        /** Shows the seat to the seated player's own client first, then runs {@code attached} or {@code failed} on the owner. */
        void attach(P player, PersistedNpcService<P, I>.Value npc, P below, Runnable attached, Consumer<String> failed);
        /** The seat's end goes to every viewer now, so a teleport issued afterwards reaches clients behind it. */
        void detach(P player, PersistedNpcService<P, I>.Value npc);
        void place(P player, Vec3 feet);
        /** The ground under the lowest vehicle of the NPC's mount chain, where seated players stand for the server. */
        WorldPosition ground(PersistedNpcService<P, I>.Value npc);
        boolean presentedTo(P player, PersistedNpcService<P, I>.Value npc);
        /** The transport can stack one player on another. */
        boolean stacking();
        /** The NPC already carries a steering rider or NPC passengers. */
        String occupied(PersistedNpcService<P, I>.Value npc);
    }
    enum State { ATTACHING, SEATED }
    final class Seat {
        final P player; final UUID id; final PersistedNpcService<P, I>.Value npc; final int level;
        final BodyTransport.ControlSubscription input; final RideEntry entry; final long started;
        /** Taken by the player's own click, so it ends when the player loses the seat's permission; a plugin's seat does not. */
        final boolean clicked;
        State state = State.ATTACHING;
        Seat(P player, UUID id, PersistedNpcService<P, I>.Value npc, int level, BodyTransport.ControlSubscription input, RideEntry entry, long started, boolean clicked) {
            this.player = player; this.id = id; this.npc = npc; this.level = level; this.input = input; this.entry = entry; this.started = started; this.clicked = clicked;
        }
    }
    private enum Exit { RESTORE_POSITION, LEAVE_POSITION }
    private static final long ATTACH_TIMEOUT_NANOS = 5_000_000_000L;
    private final PersistedNpcService<P, I> service;
    private final RuntimeHost<P, I> host;
    private final Presentation<P, I> presentation;
    private final SeatToggles toggles;
    private final Map<UUID, Seat> seats = new HashMap<>();
    private final Map<UUID, List<Seat>> stacks = new HashMap<>();
    private boolean restoring, closed;

    SeatService(PersistedNpcService<P, I> service, Presentation<P, I> presentation, SeatToggles toggles) {
        this.service = service; this.host = service.host; this.presentation = presentation; this.toggles = toggles;
    }

    int count() { return seats.size(); }
    boolean restoring() { return restoring; }
    boolean seated(P player) { return seats.containsKey(host.id(player)); }
    /** Players sitting on this NPC, lowest first; empty when nobody does. */
    List<P> stack(UUID npc) {
        List<Seat> stack = stacks.get(npc);
        if (stack == null) return List.of();
        List<P> players = new ArrayList<>(stack.size());
        for (Seat seat : stack) players.add(seat.player);
        return players;
    }
    /** The lowest seated player of this NPC, or null. */
    P bottom(UUID npc) { List<Seat> stack = stacks.get(npc); return stack == null || stack.isEmpty() ? null : stack.getFirst().player; }
    List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Seat seat : seats.values())
            lines.add(host.name(seat.player) + " on " + seat.npc.spec.definition().id() + (seat.level == 0 ? "" : ", seat " + (seat.level + 1)) + ": " + seat.state.name().toLowerCase(java.util.Locale.ROOT));
        return lines;
    }
    String describe(UUID npc) {
        List<P> players = stack(npc);
        return players.isEmpty() ? "" : "; seated=" + players.stream().map(host::name).toList();
    }
    SeatToggles toggles() { return toggles; }

    /** The seat this NPC offers now: its own module, or the configured default. */
    SeatModule offer(PersistedNpcService<P, I>.Value npc) { return npc.seatModule != null ? npc.seatModule : SeatModule.byDefault(settings().defaultEnabled()); }
    private SeatSettings settings() { return service.runtime.isRunning() ? service.runtime.seatSettings() : SeatSettings.DEFAULT; }

    /** Whether this main-hand click asks for the seat at all; other clicks never reach {@link #request}. */
    boolean asked(PersistedNpcService<P, I>.Value npc, boolean sneaking) { return offer(npc).sitsOn(sneaking); }

    /**
     * @param clicked true for the player's own click; false for a plugin's request, which skips the seat's permission,
     *                empty-hand and sitting-toggle checks
     * @return null once the player is being seated, an empty string for a refusal nobody is told of, otherwise why not
     */
    String request(P player, PersistedNpcService<P, I>.Value npc, boolean clicked) {
        if (closed) return "seats are unavailable";
        if (!service.riders.available()) return "seats are unavailable: " + service.riders.unavailable();
        SeatModule seat = offer(npc);
        UUID id = host.id(player), uuid = npc.spec.definition().uuid();
        // As with GSit: no permission, sitting turned off or an item in hand pass silently.
        if (clicked && (!host.permitted(player, seat.permission()) || !toggles.allows(id))) return "";
        if (clicked && seat.emptyHand() && !host.emptyHand(player)) return "";
        if (host.dead(player) || host.sleeping(player) || host.inVehicle(player) || host.spectator(player) || host.posed(player)) return "you cannot sit right now";
        Seat already = seats.get(id);
        // The client can aim at the NPC it sits on; that click is the seat's own and needs no answer.
        if (already != null) return already.npc == npc ? "" : "you are already sitting";
        if (service.riders.riding(player)) return "you are riding an NPC";
        if (!service.valueCurrent(npc) || !npc.state.snapshot().active()) return "this NPC is not available";
        String occupied = presentation.occupied(npc);
        if (occupied != null) return occupied;
        if (SeatRules.steered(npc.spec.type(), npc.spec.appearance().equipment())) return SeatRules.STEERED;
        WorldPosition at = npc.state.snapshot().position();
        if (!host.position(player).world().equals(at.world())) return "this NPC is in another world";
        List<Seat> stack = stacks.getOrDefault(uuid, List.of());
        if (stack.size() >= seat.stack()) return "its seats are full";
        if (!stack.isEmpty() && !presentation.stacking()) return "its seat is taken";
        if (seats.size() >= settings().maximum()) return "the server limit of " + settings().maximum() + " seated players is reached";
        if (settings().followGSit()) {
            String rule = host.seatRefusal(player, at, PersistedNpcService.bodyHeight(npc), stack.size());
            if (rule != null) return rule;
        }
        WorldPosition ground = presentation.ground(npc);
        boolean allowed;
        try { allowed = host.rideEntry().mayEnter(id, ground); }
        catch (RuntimeException | LinkageError failure) { allowed = false; }
        if (!allowed) return "region entry denied";
        BodyTransport.ControlSubscription input;
        try { input = presentation.subscribe(player); }
        catch (RuntimeException failure) { return "your connection is not ready: " + failure.getMessage(); }
        P below = stack.isEmpty() ? null : stack.getLast().player;
        Seat taken = new Seat(player, id, npc, stack.size(), input, new RideEntry(host.rideEntry(), id, ground), System.nanoTime(), clicked);
        seats.put(id, taken);
        stacks.computeIfAbsent(uuid, (UUID ignored) -> new ArrayList<>()).add(taken);
        presentation.attach(player, npc, below, () -> {
            if (seats.get(id) == taken && taken.state == State.ATTACHING) { taken.state = State.SEATED; place(taken); }
        }, (String reason) -> { if (seats.get(id) == taken) end(taken, "attach-failed", Exit.LEAVE_POSITION); });
        return null;
    }

    void tick() {
        if (seats.isEmpty()) return;
        long now = System.nanoTime();
        // Lowest seats first, so a player above one that ends this tick gets off with it.
        List<Seat> order = new ArrayList<>(seats.values());
        order.sort((Seat left, Seat right) -> Integer.compare(left.level, right.level));
        for (Seat seat : order) {
            if (seats.get(seat.id) != seat) continue;
            try {
                String stop = stop(seat, now);
                if (stop != null) { end(seat, stop, SeatRules.leavesInPlace(stop) ? Exit.LEAVE_POSITION : Exit.RESTORE_POSITION); continue; }
                if (seat.state != State.SEATED) continue;
                WorldPosition ground = presentation.ground(seat.npc);
                if (!seat.entry.allows(ground)) { end(seat, RideEntry.DENIED, Exit.RESTORE_POSITION); continue; }
                place(seat);
            } catch (RuntimeException failure) {
                host.warn("Seat failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                end(seat, "failed", Exit.RESTORE_POSITION);
            }
        }
    }
    private String stop(Seat seat, long now) {
        P player = seat.player;
        if (!host.online(player)) return "offline";
        if (host.dead(player)) return "death";
        if (!service.valueCurrent(seat.npc)) return "definition-changed";
        VirtualNpcState.Snapshot npc = seat.npc.state.snapshot();
        if (!npc.active()) return "npc-retired";
        if (seat.clicked && !offer(seat.npc).enabled()) return "seat-removed";
        if (seat.clicked && !host.permitted(player, offer(seat.npc).permission())) return "permission-revoked";
        if (host.inVehicle(player)) return "real-vehicle";
        if (host.sleeping(player) || host.spectator(player) || host.posed(player)) return "player-state";
        if (!host.position(player).world().equals(npc.position().world())) return "world-changed";
        List<Seat> stack = stacks.get(seat.npc.spec.definition().uuid());
        if (stack == null || stack.indexOf(seat) != seat.level) return "stack-ended";
        ControlInbox.Snapshot input = seat.input.latest();
        if (!input.active()) return input.reason();
        if (seat.state == State.ATTACHING) return now - seat.started > ATTACH_TIMEOUT_NANOS ? "attach-timeout" : null;
        if (!presentation.presentedTo(player, seat.npc)) return "npc-not-visible";
        return null;
    }
    /** Runs every tick, so a late movement packet from before the client sat down cannot move the player away. */
    private void place(Seat seat) {
        Vec3 feet = presentation.ground(seat.npc).point();
        Vec3 at = host.position(seat.player).point();
        if (at.x() != feet.x() || at.y() != feet.y() || at.z() != feet.z()) presentation.place(seat.player, feet);
    }

    /** The NPC was removed or replaced while its presentation is still live: everyone on it gets off. */
    void npcGone(UUID npc, String reason) {
        List<Seat> stack = stacks.get(npc);
        if (stack == null) return;
        for (Seat seat : List.copyOf(stack)) end(seat, reason, Exit.RESTORE_POSITION);
    }
    /** Quit, death, foreign teleport or world change: the destination already belongs to someone else. */
    void playerLeaving(P player, String reason) {
        Seat seat = seats.get(host.id(player));
        if (seat != null) end(seat, reason, Exit.LEAVE_POSITION);
    }
    void viewerLost(P player) {
        Seat seat = seats.get(host.id(player));
        if (seat != null) end(seat, "viewer-lost", Exit.RESTORE_POSITION);
    }
    boolean stop(String playerName, String reason) {
        for (Seat seat : List.copyOf(seats.values()))
            if (host.name(seat.player).equalsIgnoreCase(playerName)) { end(seat, reason, Exit.RESTORE_POSITION); return true; }
        return false;
    }

    /** Ends a seat and every seat above it in its stack, top first, so each detach names only bodies still shown. */
    private void end(Seat seat, String reason, Exit exit) {
        if (seats.get(seat.id) != seat) return;
        List<Seat> stack = stacks.get(seat.npc.spec.definition().uuid());
        if (stack != null) for (int index = stack.size() - 1; index > seat.level; index--) {
            Seat above = stack.get(index);
            if (seats.get(above.id) == above) release(above, "stack-ended", Exit.RESTORE_POSITION);
        }
        release(seat, reason, exit);
    }
    private void release(Seat seat, String reason, Exit exit) {
        seats.remove(seat.id);
        List<Seat> stack = stacks.get(seat.npc.spec.definition().uuid());
        if (stack != null) { stack.remove(seat); if (stack.isEmpty()) stacks.remove(seat.npc.spec.definition().uuid()); }
        seat.state = null;
        seat.input.close();
        try { presentation.detach(seat.player, seat.npc); }
        catch (RuntimeException failure) { host.warn("Seat detach failed: " + failure.getMessage()); }
        if (exit == Exit.RESTORE_POSITION && host.online(seat.player) && !host.dead(seat.player)) restore(seat);
        String told = SeatRules.told(seat.npc.spec.name(), reason);
        if (host.online(seat.player) && told != null) host.tell(seat.player, Palette.reply(told));
    }
    /** The client leaves the seat once the detach arrives, then applies this ordinary teleport beside the NPC. */
    private void restore(Seat seat) {
        P player = seat.player;
        WorldPosition from = host.position(player);
        WorldPosition target = standingPlace(seat, from);
        restoring = true;
        try {
            if (!host.teleport(player, target, host.yaw(player), host.pitch(player)))
                AnimusLog.shared().routine("seat teleports cancelled", host.name(player), "Getting off an NPC was cancelled for " + host.name(player), host::warn);
        } catch (RuntimeException failure) {
            host.warn("Getting off an NPC failed for " + host.name(player) + ": " + failure.getMessage());
        } finally { restoring = false; }
    }
    private WorldPosition standingPlace(Seat seat, WorldPosition from) {
        WorldPosition ground = presentation.ground(seat.npc);
        if (!from.world().equals(ground.world())) return from;
        return SeatRules.standingPlace(ground, seat.npc.yaw, PersistedNpcService.moveWidth(seat.npc), host.collision(from.world()));
    }

    @Override public void close() {
        for (Seat seat : List.copyOf(seats.values())) if (seats.get(seat.id) == seat) end(seat, "shutdown", Exit.RESTORE_POSITION);
        closed = true;
    }
}
