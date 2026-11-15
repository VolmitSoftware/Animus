package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.List;
import java.util.Optional;

/**
 * The decoy of one NPC on Folia that lets real mobs target it (see {@link art.arcane.animus.bukkit.combat.NpcDecoys}).
 * It is drawn, spawned and moved on the NPC's owner, only in chunks that owner holds, and only mobs that owner holds are
 * pointed at it. When the NPC's new owner does not hold the decoy, the decoy is removed on its own scheduler and the new
 * owner draws again. Owner only.
 *
 * @param <M> a real mob
 * @param <D> the decoy entity
 */
final class ActorDecoy<M, D> {
    /** Mobs near the NPC: those already on its decoy and those that would fight it and have nothing else to fight, nearest first. */
    record Scan<M>(int drawn, List<M> candidates) {}
    interface Host<M, D> {
        /** The NPC while it is active, lets mobs target it and its combat runs; empty otherwise. */
        Optional<VirtualNpcState.Snapshot> targetable();
        /** Null when this owner does not hold the whole area around the NPC. */
        Scan<M> scan(VirtualNpcState.Snapshot self, D decoy);
        /** A new decoy on the NPC's feet, in a chunk this owner holds; null when none could be made. */
        D spawn(VirtualNpcState.Snapshot self);
        boolean valid(D decoy);
        /** This owner holds the decoy now. */
        boolean owns(D decoy);
        Vec3 at(D decoy);
        /** Owner of the decoy: moves it without a teleport, so mobs keep it as their target. */
        void move(D decoy, Vec3 feet);
        /** Owner of the decoy: mobs on it let go and it is removed. */
        void remove(D decoy);
        /** Another owner holds the decoy: it is removed on its own scheduler. */
        void removeThere(D decoy);
        /** The decoy is gone already: only its bookkeeping goes. */
        void forget(D decoy);
        /** Owner of the mob: true when it targets the decoy now. */
        boolean target(M mob, D decoy);
    }
    static final int DRAW_TURNS = 20, PER_NPC = 6;
    private static final double FOLLOW = .05;
    private final Host<M, D> host;
    private final int phase;
    private D decoy;
    private long turns;

    ActorDecoy(Host<M, D> host, int phase) { this.host = host; this.phase = Math.floorMod(phase, DRAW_TURNS); }

    /** One combat turn: the decoy follows the NPC every turn, and a draw runs once every {@link #DRAW_TURNS}. */
    void tick() {
        turns++;
        Optional<VirtualNpcState.Snapshot> self = host.targetable();
        if (self.isEmpty()) { close(); return; }
        // A decoy another region holds is never read here; one that died or unloaded is simply forgotten.
        if (decoy != null && !host.owns(decoy)) close();
        else if (decoy != null && !host.valid(decoy)) { host.forget(decoy); decoy = null; }
        if (decoy != null) follow(self.get());
        if ((turns + phase) % DRAW_TURNS == 0) draw(self.get());
    }
    private void follow(VirtualNpcState.Snapshot self) {
        Vec3 feet = self.position().point(), at = host.at(decoy);
        if (Math.abs(at.x() - feet.x()) + Math.abs(at.y() - feet.y()) + Math.abs(at.z() - feet.z()) > FOLLOW) host.move(decoy, feet);
    }
    private void draw(VirtualNpcState.Snapshot self) {
        Scan<M> scan = host.scan(self, decoy);
        if (scan == null) return;
        // A decoy no mob targets any more is taken away until a mob would fight the NPC again.
        if (scan.candidates().isEmpty() && scan.drawn() == 0) { close(); return; }
        if (scan.candidates().isEmpty() || scan.drawn() >= PER_NPC) return;
        if (decoy == null) decoy = host.spawn(self);
        if (decoy == null) return;
        int drawn = scan.drawn();
        for (M mob : scan.candidates()) {
            if (drawn >= PER_NPC) break;
            if (host.target(mob, decoy)) drawn++;
        }
    }
    /** Removes the decoy on whichever owner holds it; mobs that targeted it choose again with their own AI. */
    void close() {
        D current = decoy;
        decoy = null;
        if (current == null) return;
        if (!host.owns(current)) host.removeThere(current);
        else if (host.valid(current)) host.remove(current);
        else host.forget(current);
    }
}
