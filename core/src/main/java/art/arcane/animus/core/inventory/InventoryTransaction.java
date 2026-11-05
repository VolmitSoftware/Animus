package art.arcane.animus.core.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * All-or-nothing changes to one player's storage, on the player's owner thread. Each slot is remembered before its
 * first write, so {@link #rollback()} restores exactly what the player had. Removal and addition refuse instead of
 * changing anything when the whole amount cannot be taken or placed.
 *
 * @param <S> the platform's item stack
 */
public final class InventoryTransaction<S> {
    private final TradeInventory<S> inventory;
    private final ItemOps<S> items;
    private final Map<Integer, S> before = new HashMap<>();
    private boolean finished;

    private InventoryTransaction(TradeInventory<S> inventory, ItemOps<S> items) {
        this.inventory = Objects.requireNonNull(inventory);
        this.items = Objects.requireNonNull(items);
    }

    public static <S> InventoryTransaction<S> begin(TradeInventory<S> inventory, ItemOps<S> items) { return new InventoryTransaction<>(inventory, items); }

    public int count(S wanted, MatchRule rule) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            S held = inventory.get(slot);
            if (!items.empty(held) && items.matches(held, wanted, rule)) total += items.count(held);
        }
        return total;
    }

    /** Removes the amount from as many matching stacks as needed, or changes nothing and returns false. */
    public boolean remove(S wanted, int amount, MatchRule rule) {
        open();
        positive(amount);
        if (count(wanted, rule) < amount) return false;
        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            S held = inventory.get(slot);
            if (items.empty(held) || !items.matches(held, wanted, rule)) continue;
            int taken = Math.min(remaining, items.count(held));
            write(slot, items.count(held) == taken ? null : items.withCount(held, items.count(held) - taken));
            remaining -= taken;
        }
        return true;
    }

    /** How many of the stack would fit: free space in matching partial stacks plus empty slots. */
    public int room(S stack) {
        int maximum = Math.max(1, items.maxStackSize(stack));
        long room = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            S held = inventory.get(slot);
            if (items.empty(held)) room += maximum;
            else if (items.matches(held, stack, MatchRule.STRICT)) room += Math.max(0, maximum - items.count(held));
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /** Adds the whole amount, partial stacks first, or changes nothing and returns false. */
    public boolean add(S stack, int amount) {
        open();
        positive(amount);
        if (room(stack) < amount) return false;
        int left = place(stack, amount);
        if (left != 0) throw new IllegalStateException("inventory changed while adding");
        return true;
    }

    /** Adds what fits and returns the rest as maximum-sized stacks, for a caller that drops them at the player. */
    public List<S> addOrOverflow(S stack, int amount) {
        open();
        positive(amount);
        int fits = Math.min(amount, room(stack));
        int left = (fits > 0 ? place(stack, fits) : 0) + (amount - fits);
        List<S> overflow = new ArrayList<>();
        int maximum = Math.max(1, items.maxStackSize(stack));
        while (left > 0) {
            int size = Math.min(maximum, left);
            overflow.add(items.withCount(stack, size));
            left -= size;
        }
        return List.copyOf(overflow);
    }

    public void commit() { open(); finished = true; before.clear(); }

    public void rollback() {
        open();
        for (Map.Entry<Integer, S> entry : before.entrySet()) inventory.set(entry.getKey(), entry.getValue());
        before.clear();
        finished = true;
    }

    private int place(S stack, int amount) {
        int maximum = Math.max(1, items.maxStackSize(stack));
        int left = amount;
        for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
            S held = inventory.get(slot);
            if (items.empty(held) || !items.matches(held, stack, MatchRule.STRICT) || items.count(held) >= maximum) continue;
            int added = Math.min(left, maximum - items.count(held));
            write(slot, items.withCount(held, items.count(held) + added));
            left -= added;
        }
        for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
            if (!items.empty(inventory.get(slot))) continue;
            int added = Math.min(left, maximum);
            write(slot, items.withCount(stack, added));
            left -= added;
        }
        return left;
    }

    private void write(int slot, S stack) {
        before.putIfAbsent(slot, inventory.get(slot));
        inventory.set(slot, stack);
    }

    private void open() { if (finished) throw new IllegalStateException("transaction already finished"); }
    private static void positive(int amount) { if (amount <= 0) throw new IllegalArgumentException("amount must be positive: " + amount); }
}
