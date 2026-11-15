package art.arcane.animus.core.owner;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The player whose menu click or command the current file work does, so {@link OwnerWrites} can hold every write it
 * makes to the NPCs that player may change. Set on the file lane around one piece of work; work started without it
 * (the console, imports, the editor, startup) is not a player's.
 */
public final class ActingPlayer {
    /**
     * {@code admin}: the player held {@code animus.admin} when they acted; {@code others}: they held it or
     * {@code animus.npc.others}. Both read on their own thread.
     */
    public record Actor(UUID player, boolean admin, boolean others) {
        public Actor { Objects.requireNonNull(player); }
    }

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActingPlayer() {}

    public static Optional<Actor> current() { return Optional.ofNullable(CURRENT.get()); }

    @FunctionalInterface public interface Work<T> { T run() throws java.io.IOException; }

    /** Runs {@code work} as {@code actor} on the calling thread, then restores whoever acted before; empty runs it as no one. */
    public static <T> T call(Optional<Actor> actor, Work<T> work) throws java.io.IOException {
        Actor previous = CURRENT.get();
        if (actor.isPresent()) CURRENT.set(actor.get()); else CURRENT.remove();
        try { return work.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
