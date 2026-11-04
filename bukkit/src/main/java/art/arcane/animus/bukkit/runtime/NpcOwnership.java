package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-writer authority for one NPC. Every change is a compare-and-set, so exactly one thread wins each transition
 * and a token from an earlier epoch can never act again. Holds no Bukkit objects.
 */
final class NpcOwnership {
    enum Phase { DORMANT, ACTIVE, TRANSFERRING, SUSPENDING, RETIRED }
    /** The writer's authority. The anchor chunk addresses the owner's scheduler; it is not a region identity. */
    record Token(UUID npc, long epoch, ChunkPosition anchor) {}
    record State(Phase phase, Token token) {}
    private static final State DORMANT = new State(Phase.DORMANT, null), RETIRED = new State(Phase.RETIRED, null);
    private final UUID npc;
    private final AtomicLong epochs = new AtomicLong();
    private final AtomicReference<State> state = new AtomicReference<>(DORMANT);

    NpcOwnership(UUID npc) { this.npc = Objects.requireNonNull(npc); }

    State state() { return state.get(); }
    boolean current(Token token) { State now = state.get(); return now.phase == Phase.ACTIVE && now.token == token; }
    boolean transferring(Token token) { State now = state.get(); return now.phase == Phase.TRANSFERRING && now.token == token; }

    /** Dormant to active at the anchor; null when the NPC is not dormant. */
    Token activate(ChunkPosition anchor) {
        Token next = new Token(npc, epochs.incrementAndGet(), Objects.requireNonNull(anchor));
        return state.compareAndSet(DORMANT, new State(Phase.ACTIVE, next)) ? next : null;
    }
    /** The source freezes: its token is revoked and a new epoch waits for the destination. Null when stale. */
    Token beginTransfer(Token from, ChunkPosition destination) {
        State now = state.get();
        if (now.phase != Phase.ACTIVE || now.token != from) return null;
        Token next = new Token(npc, epochs.incrementAndGet(), Objects.requireNonNull(destination));
        return state.compareAndSet(now, new State(Phase.TRANSFERRING, next)) ? next : null;
    }
    /** The destination takes authority, once. */
    boolean adopt(Token transfer) {
        State now = state.get();
        return now.phase == Phase.TRANSFERRING && now.token == transfer && state.compareAndSet(now, new State(Phase.ACTIVE, transfer));
    }
    /** Active authority ends without a successor; the winner tears down, then {@link #settle}s. */
    boolean suspend(Token token) { return move(Phase.ACTIVE, token, new State(Phase.SUSPENDING, token)); }
    /** A handoff that no destination adopted ends. Never ends an adopted, active token. */
    boolean abandon(Token transfer) { return move(Phase.TRANSFERRING, transfer, new State(Phase.SUSPENDING, transfer)); }
    /** Teardown finished: the NPC is dormant and may be woken. */
    boolean settle(Token token) { return move(Phase.SUSPENDING, token, DORMANT); }
    /** A wake that found the chunks unavailable gives the NPC back untouched. */
    boolean revert(Token token) { return move(Phase.ACTIVE, token, DORMANT); }
    private boolean move(Phase phase, Token token, State next) {
        State now = state.get();
        return now.phase == phase && now.token == token && state.compareAndSet(now, next);
    }
    /** The owner ends the NPC permanently. */
    boolean retire(Token token) {
        State now = state.get();
        return now.phase == Phase.ACTIVE && now.token == token && state.compareAndSet(now, RETIRED);
    }
    /** Ends a dormant or transferring NPC, which has no running owner to ask. */
    boolean retireUnowned() {
        while (true) {
            State now = state.get();
            if (now.phase != Phase.DORMANT && now.phase != Phase.TRANSFERRING) return false;
            if (state.compareAndSet(now, RETIRED)) return true;
        }
    }
    /** Shutdown: every outstanding token becomes stale at once. */
    void retireAll() { state.set(RETIRED); }
}
