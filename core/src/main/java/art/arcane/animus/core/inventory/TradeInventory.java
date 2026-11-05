package art.arcane.animus.core.inventory;

/**
 * A player's main storage: the hotbar and the three rows above it, slots {@code 0} to {@code size() - 1}. Armour,
 * offhand and the cursor are never read or written, so a trade cannot take a worn item.
 *
 * @param <S> the platform's item stack
 */
public interface TradeInventory<S> {
    int size();
    /** The stack in the slot, or null when it is empty. */
    S get(int slot);
    /** Replaces the slot; null empties it. */
    void set(int slot, S stack);
}
