package art.arcane.animus.core.api;

import art.arcane.animus.api.NpcService;
import java.util.function.BooleanSupplier;

/** Hands out one {@link NpcService} per owner and ends what an owner made when it stops. Thread-safe. */
public interface NpcScopes {
    /** Used where no NPC service runs: opening a service fails. */
    NpcScopes NONE = new NpcScopes() {
        @Override public NpcService open(String owner, BooleanSupplier available) { throw new IllegalStateException("The NPC service is not running"); }
        @Override public void release(String owner) {}
    };

    /** The owner's service; while {@code available} is false every call answers UNAVAILABLE. */
    NpcService open(String owner, BooleanSupplier available);

    /** The owner stopped: its temporary NPCs, modules, hiding and follows end. */
    void release(String owner);
}
