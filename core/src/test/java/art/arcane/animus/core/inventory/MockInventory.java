package art.arcane.animus.core.inventory;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** A 36-slot test inventory whose stacks are plain values: item id, count, visible text and hidden data. */
public class MockInventory implements TradeInventory<MockInventory.Stack> {
    public record Stack(String id, int count, String name, String hidden) {
        public Stack { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(hidden); }
        public static Stack of(String id, int count) { return new Stack(id, count, "", ""); }
    }

    public static final ItemOps<Stack> OPS = new ItemOps<>() {
        private final Map<String, Integer> maximums = Map.of("minecraft:ender_pearl", 16, "minecraft:diamond_sword", 1);
        @Override public boolean empty(Stack stack) { return stack == null || stack.count() <= 0; }
        @Override public int count(Stack stack) { return stack == null ? 0 : stack.count(); }
        @Override public Stack withCount(Stack stack, int count) { return new Stack(stack.id(), count, stack.name(), stack.hidden()); }
        @Override public int maxStackSize(Stack stack) { return maximums.getOrDefault(stack.id(), 64); }
        @Override public boolean matches(Stack held, Stack wanted, MatchRule rule) {
            if (held == null || wanted == null || !held.id().equals(wanted.id())) return false;
            return switch (rule) {
                case MATERIAL -> true;
                case LEGACY -> held.name().equals(wanted.name());
                case STRICT -> held.name().equals(wanted.name()) && held.hidden().equals(wanted.hidden());
            };
        }
        @Override public String describe(Stack stack, int count) { return count + "x " + stack.id(); }
    };

    private final Stack[] slots;
    public int writes;

    public MockInventory() { this(36); }
    public MockInventory(int size) { slots = new Stack[size]; }

    @Override public int size() { return slots.length; }
    @Override public Stack get(int slot) { return slots[slot]; }
    @Override public void set(int slot, Stack stack) { writes++; slots[slot] = stack == null || stack.count() <= 0 ? null : stack; }

    public MockInventory with(int slot, Stack stack) { slots[slot] = stack; return this; }
    public MockInventory fill(Stack stack) { Arrays.fill(slots, stack); return this; }

    public int total(String id) {
        int total = 0;
        for (Stack stack : slots) if (stack != null && stack.id().equals(id)) total += stack.count();
        return total;
    }

    public Stack[] copy() { return slots.clone(); }
}
