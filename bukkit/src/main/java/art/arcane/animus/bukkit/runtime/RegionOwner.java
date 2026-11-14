package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle of one NPC on regionized servers. The NPC is owned by the region that owns its anchor chunk; one repeating
 * task on that region is its only writer. A move into another chunk is an explicit handoff: the source releases what
 * cannot move, a new epoch revokes every older task, and the destination adopts the body once, after rechecking that
 * it owns the whole footprint and the anchor is loaded. A refused, lost or expired handoff leaves the NPC dormant.
 * A mounted group has one owner: a passenger that boards its vehicle gives up its own task and is ticked, handed off,
 * suspended and woken with the group's root, so vehicle and passengers always change owner in one transfer.
 * Bukkit work lives behind {@link Body}.
 */
final class RegionOwner {
    /** Chunk rectangle the owner must hold before it may read or change the body. */
    record Footprint(String world, int minX, int minZ, int maxX, int maxZ) {
        boolean contains(ChunkPosition chunk) {
            return world.equals(chunk.world()) && chunk.x() >= minX && chunk.x() <= maxX && chunk.z() >= minZ && chunk.z() <= maxZ;
        }
    }
    /** The NPC's owner-confined state. Every method runs on the current owner, or on the thread that just ended ownership. */
    interface Body {
        Footprint footprint();
        ChunkPosition anchor();
        /** Owner only: every chunk of the footprint is loaded. No chunk is loaded or generated to answer. */
        boolean loaded(Footprint footprint);
        /** Any thread: the handoff destination is a chunk known to be loaded, so scheduling there loads nothing. */
        boolean addressable(ChunkPosition destination);
        /** One owner turn after queued work. Returns the chunk the body occupies afterwards. */
        ChunkPosition tick();
        /** This thread now owns the body, by waking it ({@code woken}) or adopting a handoff. */
        void owned(boolean woken);
        /** Before a handoff: cancel the work that cannot continue on another owner. */
        void released();
        /** The handoff began: the source token is revoked and adoption, or its expiry, follows. */
        void handedOff();
        /** Authority is ending without a successor. No other thread can own the body until {@link #rested()}. */
        void suspended(String reason);
        /** The body is dormant where it stands and may be woken again. */
        void rested();
        /** Authority ended permanently. */
        void retired();
    }
    static final long TRANSFER_TIMEOUT_NANOS = 5_000_000_000L;
    private record Slot(NpcOwnership.Token token, RegionScheduler.Task task) {}
    private final RegionScheduler regions;
    private final RegionBudget budget;
    private final NpcOwnership ownership;
    private final OwnerMailbox mailbox;
    private final int workPerTick;
    private final Body body;
    private final AtomicReference<Slot> task = new AtomicReference<>();
    private volatile Footprint footprint;
    private volatile long transferDeadline;
    /** The group root that carries this passenger, while {@link NpcOwnership.Phase#CARRIED}. */
    private volatile RegionOwner carrier;
    /** Passengers this root carries, vehicles before their own passengers. Written by the group's owner only. */
    private final List<RegionOwner> riders = new CopyOnWriteArrayList<>();

    RegionOwner(UUID npc, RegionScheduler regions, RegionBudget budget, int mailboxCapacity, int workPerTick, Body body) {
        this.regions = Objects.requireNonNull(regions); this.budget = Objects.requireNonNull(budget);
        ownership = new NpcOwnership(npc); mailbox = new OwnerMailbox(mailboxCapacity);
        this.workPerTick = workPerTick; this.body = Objects.requireNonNull(body);
        mailbox.pause("NPC is not active");
    }

    NpcOwnership ownership() { return ownership; }
    Body body() { return body; }
    int queued() { return mailbox.size(); }

    /** Call on the owner of the body's anchor, such as a chunk load or a nearby viewer. True when it became active here. */
    boolean wake() {
        ChunkPosition anchor = body.anchor();
        if (!regions.owns(anchor.world(), anchor.x(), anchor.z(), anchor.x(), anchor.z())) return false;
        NpcOwnership.Token token = ownership.activate(anchor);
        if (token == null) return false;
        // The activation compare-and-set orders this read after the previous owner's last write.
        if (!holds(body.footprint()) || !body.loaded(body.footprint())) { ownership.revert(token); return false; }
        footprint = body.footprint();
        mailbox.resume();
        try { body.owned(true); }
        catch (RuntimeException failure) { suspend(token, "waking failed: " + PersistedNpcService.concise(failure)); return false; }
        return schedule(token, "the anchor region refused the NPC task");
    }

    /** True when the calling thread is the NPC's owner right now; a carried passenger is owned with its group. */
    boolean owned() {
        NpcOwnership.State state = ownership.state();
        if (state.phase() == NpcOwnership.Phase.CARRIED) {
            RegionOwner group = carrier;
            return group != null && group != this && group.owned() && ownership.carried(state.token());
        }
        Footprint published = footprint;
        return state.phase() == NpcOwnership.Phase.ACTIVE && published != null && holds(published);
    }

    /** Runs at once on the owner; otherwise queues for the owner. False when the NPC is dormant, retired or full. */
    boolean submit(OwnerMailbox.Work work) {
        if (owned()) { work.run(); return true; }
        NpcOwnership.Phase phase = ownership.state().phase();
        return (phase == NpcOwnership.Phase.ACTIVE || phase == NpcOwnership.Phase.TRANSFERRING || phase == NpcOwnership.Phase.CARRIED) && mailbox.offer(work);
    }

    boolean carried() { return ownership.state().phase() == NpcOwnership.Phase.CARRIED; }
    /** The owner of this NPC's group: its carrier while carried, otherwise itself. */
    RegionOwner root() { RegionOwner group = carrier; return group != null && carried() ? group : this; }
    /** Group owner only: the passengers this root carries. */
    List<RegionOwner> riders() { return List.copyOf(riders); }

    /**
     * Owner only, both of this NPC and of the vehicle: joins the vehicle's group. The passenger's own task stops and the
     * group's root ticks it from now on; a passenger that carries others brings them along. False when the vehicle
     * belongs to another owner or either side is stale.
     */
    boolean board(RegionOwner vehicle) {
        if (vehicle == this || carried() || !owned()) return false;
        RegionOwner group = vehicle.root();
        if (group == this || group.carried() || group.ownership.state().phase() != NpcOwnership.Phase.ACTIVE || !group.owned()) return false;
        NpcOwnership.State state = ownership.state();
        if (ownership.board(state.token(), body.anchor()) == null) return false;
        cancel(state.token());
        footprint = null;
        carrier = group;
        group.riders.add(this);
        for (RegionOwner passenger : riders) { passenger.carrier = group; group.riders.add(passenger); }
        riders.clear();
        group.footprint = group.groupFootprint();
        return true;
    }
    /** Group owner only: the passenger leaves its group and runs its own task again, under a new epoch. */
    boolean alight() {
        NpcOwnership.State state = ownership.state();
        RegionOwner group = carrier;
        if (state.phase() != NpcOwnership.Phase.CARRIED || group == null || !group.owned()) return false;
        NpcOwnership.Token next = ownership.alight(state.token(), body.anchor());
        if (next == null) return false;
        group.riders.remove(this);
        carrier = null;
        footprint = body.footprint();
        return schedule(next, "the passenger's region refused its task");
    }

    /** Chunk-unload callback on the owner: an NPC whose footprint includes the chunk becomes dormant first. */
    boolean unloading(ChunkPosition chunk) {
        NpcOwnership.State state = ownership.state();
        if (state.phase() == NpcOwnership.Phase.CARRIED) { RegionOwner group = carrier; return group != null && group != this && group.unloading(chunk); }
        Footprint published = footprint;
        if (state.phase() != NpcOwnership.Phase.ACTIVE || published == null || !published.contains(chunk) || !holds(published)) return false;
        return suspend(state.token(), "its chunk unloaded");
    }

    /** Owner only, from the owner's own turn: ends the NPC permanently. */
    boolean retire() {
        NpcOwnership.State state = ownership.state();
        boolean passenger = state.phase() == NpcOwnership.Phase.CARRIED;
        if ((state.phase() != NpcOwnership.Phase.ACTIVE && !passenger) || !owned()) return false;
        // Passengers outlive their removed vehicle: they leave the group while this owner still holds it.
        if (!passenger) for (RegionOwner rider : List.copyOf(riders)) rider.alight();
        RegionOwner group = carrier;
        if (!ownership.retire(state.token())) return false;
        if (passenger && group != null) group.riders.remove(this);
        carrier = null;
        cancelTask(); mailbox.close("NPC was removed"); body.retired();
        return true;
    }
    /** Ends a dormant or transferring NPC from any thread; an active one must be retired by its owner. */
    boolean retireUnowned() {
        if (!ownership.retireUnowned()) return false;
        // A root retired in transfer has no owner to hand its passengers to: they go dormant and wake on their own.
        dropRiders("their vehicle was removed");
        cancelTask(); mailbox.close("NPC was removed"); body.retired();
        return true;
    }
    /** A handoff whose destination never adopted it ends dormant. Any thread. */
    boolean expireTransfer(long nowNanos) {
        NpcOwnership.State state = ownership.state();
        if (state.phase() != NpcOwnership.Phase.TRANSFERRING || nowNanos - transferDeadline < 0) return false;
        return abandon(state.token(), "the destination region did not adopt the NPC in time");
    }
    boolean transferring() { return ownership.state().phase() == NpcOwnership.Phase.TRANSFERRING; }
    /** Plugin shutdown: revoke every token and task without touching the body or the world. */
    void shutdown() { ownership.retireAll(); cancelTask(); mailbox.close("NPC runtime stopped"); }

    private void turn(NpcOwnership.Token token) {
        if (!ownership.current(token)) { cancel(token); return; }
        // A fault in one NPC's turn ends that NPC's authority; it never escapes into the region tick.
        try {
            Footprint now = groupFootprint();
            if (!holds(now) || !body.loaded(now)) { suspend(token, "its owner no longer holds the NPC's loaded chunks"); return; }
            footprint = now;
            drain();
            if (!ownership.current(token)) return;
            ChunkPosition at = body.tick();
            if (!ownership.current(token)) return;
            // Passengers follow their vehicles in the same turn, in boarding order: a vehicle before its passengers.
            for (RegionOwner rider : List.copyOf(riders)) {
                if (rider.carrier != this) continue;
                rider.drain();
                if (!ownership.current(token)) return;
                if (rider.carrier != this || !rider.carried()) continue;
                rider.body.tick();
                if (!ownership.current(token)) return;
            }
            footprint = groupFootprint();
            if (!at.equals(token.anchor())) handoff(token, at);
        } catch (RuntimeException failure) {
            if (ownership.current(token)) suspend(token, "its owner turn failed: " + PersistedNpcService.concise(failure));
        }
    }
    private void drain() {
        int granted = budget.take(RegionBudget.Kind.OWNER_WORK, Math.min(workPerTick, mailbox.size()));
        budget.refund(RegionBudget.Kind.OWNER_WORK, granted - mailbox.drain(granted));
    }
    /** The rectangle the group covers: this body and every passenger it carries. */
    private Footprint groupFootprint() {
        Footprint union = body.footprint();
        for (RegionOwner rider : riders) {
            Footprint part = rider.body.footprint();
            if (!part.world().equals(union.world())) continue;
            union = new Footprint(union.world(), Math.min(union.minX(), part.minX()), Math.min(union.minZ(), part.minZ()),
                    Math.max(union.maxX(), part.maxX()), Math.max(union.maxZ(), part.maxZ()));
        }
        return union;
    }
    private void handoff(NpcOwnership.Token token, ChunkPosition destination) {
        body.released();
        for (RegionOwner rider : riders) rider.body.released();
        transferDeadline = System.nanoTime() + TRANSFER_TIMEOUT_NANOS;
        NpcOwnership.Token next = ownership.beginTransfer(token, destination);
        if (next == null) return;
        footprint = null;
        // Only the source's own task: the destination may already have adopted and scheduled its own.
        cancel(token);
        body.handedOff();
        if (!body.addressable(destination)) abandon(next, "the destination chunk is not known to be loaded");
        else if (!regions.execute(destination, () -> adopt(next))) abandon(next, "the destination region refused the handoff");
    }
    private void adopt(NpcOwnership.Token transfer) {
        if (!ownership.transferring(transfer)) return;
        // Reads after the transferring state are ordered after the source's last write.
        Footprint now = groupFootprint();
        if (!holds(now) || !body.loaded(now)) { abandon(transfer, "the destination does not hold the NPC's loaded chunks"); return; }
        if (!ownership.adopt(transfer)) return;
        footprint = now;
        try { body.owned(false); for (RegionOwner rider : riders) rider.body.owned(false); }
        catch (RuntimeException failure) { suspend(transfer, "adoption failed: " + PersistedNpcService.concise(failure)); return; }
        schedule(transfer, "the destination region refused the NPC task");
    }
    private boolean schedule(NpcOwnership.Token token, String refused) {
        RegionScheduler.Task scheduled = regions.repeat(token.anchor(), () -> turn(token));
        if (scheduled == null) { suspend(token, refused); return false; }
        Slot previous = task.getAndSet(new Slot(token, scheduled));
        if (previous != null) previous.task().cancel();
        if (!ownership.current(token)) cancel(token);
        return true;
    }
    private boolean suspend(NpcOwnership.Token token, String reason) {
        return ownership.suspend(token) && settle(token, reason);
    }
    private boolean abandon(NpcOwnership.Token transfer, String reason) {
        return ownership.abandon(transfer) && settle(transfer, reason);
    }
    /** Teardown runs while the NPC is suspending, so no wake can take it until it is dormant. */
    private boolean settle(NpcOwnership.Token token, String reason) {
        footprint = null;
        try { cancel(token); mailbox.pause("NPC not active: " + reason); body.suspended(reason); dropRiders("their vehicle is not active: " + reason); }
        finally { if (ownership.settle(token)) body.rested(); }
        return true;
    }
    /** The group ends without an owner: every carried passenger goes dormant where it stands. */
    private void dropRiders(String reason) {
        for (RegionOwner rider : List.copyOf(riders)) {
            NpcOwnership.State state = rider.ownership.state();
            rider.carrier = null;
            if (state.phase() != NpcOwnership.Phase.CARRIED || !rider.ownership.suspendCarried(state.token())) continue;
            rider.footprint = null;
            try { rider.mailbox.pause("NPC not active: " + reason); rider.body.suspended(reason); }
            finally { if (rider.ownership.settle(state.token())) rider.body.rested(); }
        }
        riders.clear();
    }
    private void cancel(NpcOwnership.Token token) {
        Slot slot = task.get();
        if (slot != null && slot.token() == token && task.compareAndSet(slot, null)) slot.task().cancel();
    }
    private void cancelTask() { Slot slot = task.getAndSet(null); if (slot != null) slot.task().cancel(); }
    private boolean holds(Footprint footprint) { return regions.owns(footprint.world(), footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ()); }
}
