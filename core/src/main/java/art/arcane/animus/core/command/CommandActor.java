package art.arcane.animus.core.command;

import java.util.Optional;
import java.util.UUID;

/** Whoever issued an Animus command: a player or the console. Read and answer only on the actor's own owner. */
public interface CommandActor {
    boolean permitted(String node);
    void tell(String message);
    /** The player's UUID, or empty for the console. */
    Optional<UUID> player();
    /** Runs the action on the actor's owner later. False when it cannot be accepted. */
    boolean reply(Runnable action);
}
