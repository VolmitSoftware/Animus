package art.arcane.animus.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One flag in behavior state, as {@code animus:set-flag} and {@code animus:flag-state} address it. Flags belong to a
 * behavior namespace: the behaviors {@code town:greet} and {@code town:farewell} share the flags of namespace
 * {@code town}, so a plugin reads and writes what those behaviors see by naming {@code town}. A flag is kept per NPC, per
 * player, per NPC and player pair (relationship) or once for the server (global).
 * <p>
 * The record accepts any text; the service answers {@link Outcome.Status#INVALID} for a malformed namespace or name, or a
 * scope without the NPC or player it needs.
 *
 * @param namespace behavior namespace, such as {@code town}
 * @param name flag name, such as {@code greeted}
 * @param scope who the flag belongs to
 * @param npc the NPC, for npc and relationship flags
 * @param player the player, for player and relationship flags
 * @since 1.3
 */
public record Flag(String namespace, String name, Scope scope, Optional<NpcHandle> npc, Optional<UUID> player) {
    /** Who a flag belongs to. */
    public enum Scope { NPC, PLAYER, RELATIONSHIP, GLOBAL }

    public Flag {
        Objects.requireNonNull(namespace, "namespace"); Objects.requireNonNull(name, "name"); Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(npc, "npc"); Objects.requireNonNull(player, "player");
    }

    /**
     * A flag of one NPC.
     *
     * @param namespace behavior namespace
     * @param name flag name
     * @param npc the NPC
     * @return the flag
     */
    public static Flag npc(String namespace, String name, NpcHandle npc) { return new Flag(namespace, name, Scope.NPC, Optional.of(npc), Optional.empty()); }

    /**
     * A flag of one player.
     *
     * @param namespace behavior namespace
     * @param name flag name
     * @param player the player
     * @return the flag
     */
    public static Flag player(String namespace, String name, UUID player) { return new Flag(namespace, name, Scope.PLAYER, Optional.empty(), Optional.of(player)); }

    /**
     * A flag of one NPC and player pair.
     *
     * @param namespace behavior namespace
     * @param name flag name
     * @param npc the NPC
     * @param player the player
     * @return the flag
     */
    public static Flag relationship(String namespace, String name, NpcHandle npc, UUID player) {
        return new Flag(namespace, name, Scope.RELATIONSHIP, Optional.of(npc), Optional.of(player));
    }

    /**
     * A flag of the whole server.
     *
     * @param namespace behavior namespace
     * @param name flag name
     * @return the flag
     */
    public static Flag global(String namespace, String name) { return new Flag(namespace, name, Scope.GLOBAL, Optional.empty(), Optional.empty()); }
}
