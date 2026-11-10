package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Cost;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CostItem;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChargesTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final int[] levels = {5};

    private static Stack stack(CostItem item) {
        if (!item.item().startsWith("minecraft:")) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Not an item on this server: " + item.item());
        return Stack.of(item.item(), 1);
    }

    private Charges.Payer<Stack> payer(MockInventory inventory, Economy economy) {
        return new Charges.Payer<>(ALEX, inventory, MockInventory.OPS, ChargesTest::stack, () -> levels[0], (int taken) -> levels[0] -= taken, economy);
    }

    private static CostItem item(String id, int amount) { return new CostItem(id, Optional.empty(), amount, MatchRule.STRICT); }

    private static Economy money(boolean throwing) {
        return new Economy() {
            @Override public Optional<String> unavailable() { return Optional.empty(); }
            @Override public boolean has(UUID player, BigDecimal amount) { return true; }
            @Override public boolean withdraw(UUID player, BigDecimal amount) { if (throwing) throw new IllegalStateException("provider failed"); return true; }
            @Override public boolean deposit(UUID player, BigDecimal amount) { return true; }
            @Override public String format(BigDecimal amount) { return amount.toPlainString(); }
        };
    }

    @Test void anUnknownSecondItemPutsTheFirstBack() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:diamond", 3));
        Cost cost = new Cost(BigDecimal.ZERO, 0, List.of(item("minecraft:diamond", 1), item("somemod:coin", 1)));
        assertThrows(CapabilityRefusal.class, () -> Charges.charge(payer(inventory, Economy.none("none")), cost));
        assertEquals(3, inventory.total("minecraft:diamond"));
    }

    @Test void aThrowingMoneyProviderPutsTheItemsBack() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:diamond", 3));
        Cost cost = new Cost(BigDecimal.TEN, 0, List.of(item("minecraft:diamond", 2)));
        assertThrows(IllegalStateException.class, () -> Charges.charge(payer(inventory, money(true)), cost));
        assertEquals(3, inventory.total("minecraft:diamond"));
        assertEquals(5, levels[0]);
    }

    @Test void itemsAndLevelsChargeWithoutAnEconomy() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:diamond", 3));
        Cost cost = new Cost(BigDecimal.ZERO, 2, List.of(item("minecraft:diamond", 2)));
        Charges.Payer<Stack> payer = payer(inventory, Economy.none("mod servers have no standard economy API"));
        assertTrue(Charges.affordable(payer, cost));
        assertTrue(Charges.charge(payer, cost));
        assertEquals(1, inventory.total("minecraft:diamond"));
        assertEquals(3, levels[0]);
    }

    @Test void moneyWithoutAnEconomyIsNeverAffordableAndTakesNothing() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:diamond", 3));
        Cost cost = new Cost(BigDecimal.ONE, 1, List.of(item("minecraft:diamond", 1)));
        Charges.Payer<Stack> payer = payer(inventory, Economy.none("none"));
        assertFalse(Charges.affordable(payer, cost));
        assertFalse(Charges.charge(payer, cost));
        assertEquals(3, inventory.total("minecraft:diamond"));
        assertEquals(5, levels[0]);
    }

    @Test void tooFewLevelsOrItemsTakeNothing() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:diamond", 3));
        assertFalse(Charges.charge(payer(inventory, Economy.none("none")), new Cost(BigDecimal.ZERO, 6, List.of(item("minecraft:diamond", 1)))));
        assertFalse(Charges.charge(payer(inventory, Economy.none("none")), new Cost(BigDecimal.ZERO, 1, List.of(item("minecraft:diamond", 1), item("minecraft:emerald", 1)))));
        assertEquals(3, inventory.total("minecraft:diamond"));
        assertEquals(5, levels[0]);
    }
}
