package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.TradeInventory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The opened containers of an owned shop, or why they cannot be used now. {@code lost} marks a reason that will not
 * pass on its own: a block that is no longer a container, or protection that no longer lets the owner open it. A
 * chunk that is not loaded or not on this thread is not lost. The platform opens them on the buyer's owner thread for
 * one trade.
 *
 * @param <S> the platform's item stack
 */
public record ShopStock<S>(Optional<TradeInventory<S>> inventory, Optional<String> refusal, boolean lost) {
    public ShopStock {
        Objects.requireNonNull(inventory);
        Objects.requireNonNull(refusal);
        if (inventory.isPresent() == refusal.isPresent()) throw new IllegalArgumentException("either an inventory or a refusal");
        if (lost && refusal.isEmpty()) throw new IllegalArgumentException("only a refusal is lost");
    }

    public static <S> ShopStock<S> of(TradeInventory<S> inventory) { return new ShopStock<>(Optional.of(inventory), Optional.empty(), false); }
    /** A refusal that may pass: the chunk is not loaded or belongs to another thread. */
    public static <S> ShopStock<S> refused(String reason) { return new ShopStock<>(Optional.empty(), Optional.of(reason), false); }
    /** A refusal that closes the shop until its owner fixes it. */
    public static <S> ShopStock<S> lost(String reason) { return new ShopStock<>(Optional.empty(), Optional.of(reason), true); }

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
