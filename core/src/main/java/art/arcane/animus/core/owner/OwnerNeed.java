package art.arcane.animus.core.owner;

import java.util.Objects;

/**
 * One thing an NPC owned by a player may do only while its owner holds {@code node}: {@code what} it would do, such as
 * {@code run commands}, and {@code where}, such as {@code behavior town:reward}. A lent permission needs that very node.
 */
public record OwnerNeed(String node, String what, String where) {
    public OwnerNeed { Objects.requireNonNull(node); Objects.requireNonNull(what); Objects.requireNonNull(where); }

    /** The plain refusal for the NPC {@code id}: what it would do and the node its owner lacks. */
    public String refusal(String id) {
        return id + " is owned by a player, so it cannot " + what + " (" + where + ") unless its owner holds " + node;
    }
}
