package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.TradeInventory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The opened containers of an owned shop, or why they cannot be used now: the block is gone or not loaded, it is not
 * on this thread, or protection refuses the owner. The platform opens them on the buyer's owner thread for one trade.
 *
 * @param <S> the platform's item stack
 */
public record ShopStock<S>(Optional<TradeInventory<S>> inventory, Optional<String> refusal) {
    public ShopStock {
        Objects.requireNonNull(inventory);
        Objects.requireNonNull(refusal);
        if (inventory.isPresent() == refusal.isPresent()) throw new IllegalArgumentException("either an inventory or a refusal");
    }

    public static <S> ShopStock<S> of(TradeInventory<S> inventory) { return new ShopStock<>(Optional.of(inventory), Optional.empty()); }
    public static <S> ShopStock<S> refused(String reason) { return new ShopStock<>(Optional.empty(), Optional.of(reason)); }

    /** Several containers read as one storage: the slots of the first, then the next. */
    public static <S> TradeInventory<S> combined(List<TradeInventory<S>> parts) {
        List<TradeInventory<S>> copy = List.copyOf(parts);
        if (copy.size() == 1) return copy.getFirst();
        return new TradeInventory<>() {
            @Override public int size() { return copy.stream().mapToInt(TradeInventory::size).sum(); }
            @Override public S get(int slot) { return locate(slot, (TradeInventory<S> part, Integer index) -> part.get(index)); }
            @Override public void set(int slot, S stack) { locate(slot, (TradeInventory<S> part, Integer index) -> { part.set(index, stack); return null; }); }
            private S locate(int slot, java.util.function.BiFunction<TradeInventory<S>, Integer, S> action) {
                int offset = slot;
                for (TradeInventory<S> part : copy) {
                    if (offset < part.size()) return action.apply(part, offset);
                    offset -= part.size();
                }
                throw new IndexOutOfBoundsException("slot " + slot);
            }
        };
    }
}
