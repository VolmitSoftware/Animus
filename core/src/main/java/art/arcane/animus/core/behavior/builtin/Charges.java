package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Cost;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CostItem;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.TradeInventory;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The {@code animus:charge} cost on any platform, on the player's owner thread. Items come from the player's storage,
 * levels are whole experience levels and money goes through the economy. Money is taken last, so a refused withdrawal
 * puts the items back and leaves the levels alone; a step that throws puts the items back too.
 */
public final class Charges {
    private Charges() {}

    /**
     * One player's side of a charge. {@code stack} builds a one-item platform stack for a cost item and throws a
     * {@link art.arcane.animus.core.extension.CapabilityRefusal} for an item the server does not have.
     */
    public record Payer<S>(UUID player, TradeInventory<S> storage, ItemOps<S> items, Function<CostItem, S> stack, IntSupplier levels,
                           IntConsumer takeLevels, Economy economy) {
        public Payer {
            Objects.requireNonNull(player); Objects.requireNonNull(storage); Objects.requireNonNull(items); Objects.requireNonNull(stack);
            Objects.requireNonNull(levels); Objects.requireNonNull(takeLevels); Objects.requireNonNull(economy);
        }
    }

    public static <S> boolean affordable(Payer<S> payer, Cost cost) {
        if (payer.levels().getAsInt() < cost.levels()) return false;
        InventoryTransaction<S> items = InventoryTransaction.begin(payer.storage(), payer.items());
        try { if (!take(payer, items, cost)) return false; }
        finally { items.rollback(); }
        return cost.money().signum() == 0 || payer.economy().unavailable().isEmpty() && payer.economy().has(payer.player(), cost.money());
    }

    public static <S> boolean charge(Payer<S> payer, Cost cost) {
        if (payer.levels().getAsInt() < cost.levels()) return false;
        boolean money = cost.money().signum() > 0;
        if (money && payer.economy().unavailable().isPresent()) return false;
        InventoryTransaction<S> items = InventoryTransaction.begin(payer.storage(), payer.items());
        try {
            if (!take(payer, items, cost) || money && !(payer.economy().has(payer.player(), cost.money()) && payer.economy().withdraw(payer.player(), cost.money()))) {
                items.rollback();
                return false;
            }
        } catch (RuntimeException failed) {
            items.rollback();
            throw failed;
        }
        items.commit();
        if (cost.levels() > 0) payer.takeLevels().accept(cost.levels());
        return true;
    }

    private static <S> boolean take(Payer<S> payer, InventoryTransaction<S> items, Cost cost) {
        for (CostItem wanted : cost.items())
            if (!items.remove(payer.stack().apply(wanted), wanted.amount(), wanted.match())) return false;
        return true;
    }
}
