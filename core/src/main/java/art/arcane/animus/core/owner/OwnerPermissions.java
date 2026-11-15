package art.arcane.animus.core.owner;

import java.util.UUID;

/**
 * The permission nodes of an NPC's owning player, online or not. Every platform supplies one; without one every answer
 * is no, so an NPC owned by a player gains nothing.
 */
public interface OwnerPermissions {
    /** {@code UNKNOWN}: offline and no permission plugin could answer. Only {@code YES} grants. */
    enum Answer { YES, NO, UNKNOWN }

    /** Off the server thread: may load an offline player's permissions, which can take a moment. */
    Answer has(UUID player, String node);

    /**
     * Never blocks: an online player's answer, else the last answer seen for that node, else {@code UNKNOWN}, which may
     * start a lookup so a later call knows.
     */
    Answer cached(UUID player, String node);

    OwnerPermissions NONE = new OwnerPermissions() {
        @Override public Answer has(UUID player, String node) { return Answer.NO; }
        @Override public Answer cached(UUID player, String node) { return Answer.NO; }
    };
}
