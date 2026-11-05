package art.arcane.animus.core.inventory;

import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTransactionTest {
    private static final Stack BREAD = Stack.of("minecraft:bread", 1);
    private static final Stack EMERALD = Stack.of("minecraft:emerald", 1);

    private static InventoryTransaction<Stack> begin(MockInventory inventory) { return InventoryTransaction.begin(inventory, MockInventory.OPS); }

    @Test void countsMatchingItemsAcrossStacks() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 10)).with(20, Stack.of("minecraft:emerald", 5)).with(3, Stack.of("minecraft:bread", 2));
        assertEquals(15, begin(inventory).count(EMERALD, MatchRule.STRICT));
    }

    @Test void strictMatchingRejectsHiddenDataThatTheLegacyRuleIgnores() {
        MockInventory inventory = new MockInventory().with(0, new Stack("minecraft:emerald", 4, "", "custom"));
        assertEquals(0, begin(inventory).count(EMERALD, MatchRule.STRICT));
        assertEquals(4, begin(inventory).count(EMERALD, MatchRule.LEGACY));
    }

    @Test void removeTakesFromSeveralStacksAndLeavesTheRest() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 3)).with(5, Stack.of("minecraft:emerald", 4));
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertTrue(transaction.remove(EMERALD, 5, MatchRule.STRICT));
        transaction.commit();
        assertEquals(2, inventory.total("minecraft:emerald"));
        assertNull(inventory.get(0));
    }

    @Test void removeOfMoreThanHeldChangesNothing() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 3));
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertFalse(transaction.remove(EMERALD, 4, MatchRule.STRICT));
        assertEquals(0, inventory.writes);
        assertEquals(3, inventory.total("minecraft:emerald"));
    }

    @Test void roomCountsPartialStacksAndEmptySlots() {
        MockInventory inventory = new MockInventory(3).with(0, Stack.of("minecraft:bread", 60)).with(1, Stack.of("minecraft:stone", 64));
        assertEquals(4 + 64, begin(inventory).room(BREAD));
    }

    @Test void roomRespectsSmallMaximumStackSizes() {
        MockInventory inventory = new MockInventory(2).with(0, Stack.of("minecraft:ender_pearl", 10));
        assertEquals(6 + 16, begin(inventory).room(Stack.of("minecraft:ender_pearl", 1)));
    }

    @Test void addFillsPartialStacksBeforeEmptySlots() {
        MockInventory inventory = new MockInventory(3).with(2, Stack.of("minecraft:bread", 62));
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertTrue(transaction.add(BREAD, 4));
        transaction.commit();
        assertEquals(64, inventory.get(2).count());
        assertEquals(2, inventory.get(0).count());
    }

    @Test void addIntoAFullInventoryFailsWithoutChanges() {
        MockInventory inventory = new MockInventory(2).fill(Stack.of("minecraft:stone", 64));
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertFalse(transaction.add(BREAD, 1));
        assertEquals(0, inventory.writes);
    }

    @Test void addStacksIntoPartialStacksEvenWithNoEmptySlot() {
        MockInventory inventory = new MockInventory(2).with(0, Stack.of("minecraft:stone", 64)).with(1, Stack.of("minecraft:bread", 10));
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertTrue(transaction.add(BREAD, 50));
        assertEquals(60, inventory.get(1).count());
    }

    @Test void overflowReturnsWhatDidNotFitInMaximumSizedStacks() {
        MockInventory inventory = new MockInventory(1).with(0, Stack.of("minecraft:bread", 60));
        InventoryTransaction<Stack> transaction = begin(inventory);
        List<Stack> overflow = transaction.addOrOverflow(BREAD, 4 + 64 + 10);
        assertEquals(64, inventory.get(0).count());
        assertEquals(List.of(Stack.of("minecraft:bread", 64), Stack.of("minecraft:bread", 10)), overflow);
    }

    @Test void rollbackRestoresEveryTouchedSlot() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 3)).with(1, Stack.of("minecraft:bread", 63));
        Stack[] before = inventory.copy();
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertTrue(transaction.remove(EMERALD, 3, MatchRule.STRICT));
        assertTrue(transaction.add(BREAD, 10));
        transaction.rollback();
        assertArrayEquals(before, inventory.copy());
    }

    @Test void rollbackAfterCommitIsRefused() {
        InventoryTransaction<Stack> transaction = begin(new MockInventory());
        transaction.commit();
        assertThrows(IllegalStateException.class, transaction::rollback);
        assertThrows(IllegalStateException.class, () -> transaction.add(BREAD, 1));
    }

    @Test void nonPositiveAmountsAreRefused() {
        InventoryTransaction<Stack> transaction = begin(new MockInventory());
        assertThrows(IllegalArgumentException.class, () -> transaction.add(BREAD, 0));
        assertThrows(IllegalArgumentException.class, () -> transaction.remove(BREAD, -1, MatchRule.STRICT));
    }

    @Test void addedStacksNeverExceedTheirMaximum() {
        MockInventory inventory = new MockInventory(4);
        InventoryTransaction<Stack> transaction = begin(inventory);
        assertTrue(transaction.add(Stack.of("minecraft:diamond_sword", 1), 3));
        for (int slot = 0; slot < 3; slot++) assertEquals(1, inventory.get(slot).count());
    }
}
