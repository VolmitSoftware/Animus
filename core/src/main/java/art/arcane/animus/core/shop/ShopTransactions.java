package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.inventory.TradeInventory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Atomic shop purchases on the player's owner thread. Money and items move together or not at all: a step that
 * fails restores the player's storage and refunds what was taken. Limits and stock are counted only after a purchase
 * completes. Commands of a command entry are the caller's to run after {@link Status#DONE}.
 *
 * @param <S> the platform's item stack
 */
public final class ShopTransactions<S> {
    public enum Status {
        DONE, NOT_TRADABLE, NO_PERMISSION, LIMIT_REACHED, OUT_OF_STOCK, ECONOMY_UNAVAILABLE, INSUFFICIENT_FUNDS, INVENTORY_FULL,
        NOT_ENOUGH_ITEMS, NOTHING_TO_SELL, PAYMENT_FAILED, FAILED
    }
    public record Need<S>(S stack, int amount) {}
    /** An entry with its platform stacks built once: a one-item stack of the goods, and the trade lists. */
    public record Prepared<S>(ShopDocument.Entry entry, S goods, List<Need<S>> needs, List<Need<S>> gives) {
        public Prepared { Objects.requireNonNull(entry); needs = List.copyOf(needs); gives = List.copyOf(gives); }
    }
    public record Buyer<S>(UUID id, Predicate<String> permitted, TradeInventory<S> inventory, Consumer<S> drop) {}
    /** What happened. {@code units} are items for buy and sell, one for a trade or command purchase. */
    public record Outcome(Status status, int units, BigDecimal price, Optional<String> detail) {
        static Outcome of(Status status) { return new Outcome(status, 0, BigDecimal.ZERO, Optional.empty()); }
        public boolean done() { return status == Status.DONE; }
    }

    private final ItemOps<S> items;
    private final Economy economy;
    private final ShopLedger ledger;
    private final Supplier<Instant> clock;

    public ShopTransactions(ItemOps<S> items, Economy economy, ShopLedger ledger, Supplier<Instant> clock) {
        this.items = Objects.requireNonNull(items);
        this.economy = Objects.requireNonNull(economy);
        this.ledger = Objects.requireNonNull(ledger);
        this.clock = Objects.requireNonNull(clock);
    }

    public Economy economy() { return economy; }
    public ShopLedger ledger() { return ledger; }

    /** {@code fullStack} is a shift click: a maximum-size stack when the entry allows it. */
    public Outcome perform(String shop, Prepared<S> prepared, Buyer<S> buyer, boolean fullStack) {
        ShopDocument.Entry entry = prepared.entry();
        if (entry.type() == ShopDocument.Type.STATIC) return Outcome.of(Status.NOT_TRADABLE);
        if (entry.permission().isPresent() && !buyer.permitted().test(entry.permission().get())) return Outcome.of(Status.NO_PERMISSION);
        Instant now = clock.get();
        int base = entry.item().count();
        int units = switch (entry.type()) {
            case BUY, SELL -> fullStack && entry.allowStack() ? Math.max(base, items.maxStackSize(prepared.goods())) : base;
            default -> 1;
        };
        InventoryTransaction<S> inventory = InventoryTransaction.begin(buyer.inventory(), items);
        MatchRule rule = entry.match().orElse(MatchRule.STRICT);
        if (entry.type() == ShopDocument.Type.SELL) {
            int held = inventory.count(prepared.goods(), rule);
            if (held == 0) return Outcome.of(Status.NOTHING_TO_SELL);
            if (held < units) {
                if (!entry.sellPartial()) return Outcome.of(Status.NOT_ENOUGH_ITEMS);
                units = held;
            }
        }
        ShopLedger.Rules rules = ShopLedger.Rules.of(entry);
        if (rules.any()) {
            long allowed = ledger.available(shop, entry.id(), rules, buyer.id(), now);
            if (allowed < units) {
                boolean stockBinds = entry.stock().isPresent()
                        && entry.stock().get().maximum() - ledger.stockUsed(shop, entry.id(), now) < units;
                return Outcome.of(stockBinds ? Status.OUT_OF_STOCK : Status.LIMIT_REACHED);
            }
        }
        BigDecimal price = scaled(entry.price(now), units, entry.type() == ShopDocument.Type.BUY || entry.type() == ShopDocument.Type.SELL ? base : 1);
        boolean money = price.signum() > 0;
        if (money && economy.unavailable().isPresent()) return new Outcome(Status.ECONOMY_UNAVAILABLE, 0, price, economy.unavailable());
        Outcome outcome = switch (entry.type()) {
            case BUY -> buy(prepared, buyer, inventory, units, price, money);
            case SELL -> sell(prepared, buyer, inventory, units, price, money, rule);
            case TRADE -> trade(prepared, buyer, inventory, price, money, rule);
            case COMMAND -> command(buyer, inventory, price, money);
            case STATIC -> Outcome.of(Status.NOT_TRADABLE);
        };
        if (outcome.done() && rules.any()) ledger.record(shop, entry.id(), rules, buyer.id(), outcome.units(), now);
        return outcome;
    }

    /** The price of {@code units} when {@code price} is for {@code per} items. */
    static BigDecimal scaled(BigDecimal price, int units, int per) {
        if (units == per) return price;
        BigDecimal result = price.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(per), 4, RoundingMode.HALF_UP);
        return result.stripTrailingZeros().scale() < 0 ? result.setScale(0, RoundingMode.UNNECESSARY) : result.stripTrailingZeros();
    }

    private Outcome buy(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, int units, BigDecimal price, boolean money) {
        ShopDocument.Entry entry = prepared.entry();
        if (!entry.dropWhenFull() && inventory.room(prepared.goods()) < units) return Outcome.of(Status.INVENTORY_FULL);
        if (money && !withdraw(buyer.id(), price)) return new Outcome(Status.INSUFFICIENT_FUNDS, 0, price, Optional.empty());
        List<S> overflow;
        try {
            overflow = entry.dropWhenFull() ? inventory.addOrOverflow(prepared.goods(), units) : List.of();
            if (!entry.dropWhenFull() && !inventory.add(prepared.goods(), units)) throw new IllegalStateException("room vanished");
            inventory.commit();
        } catch (RuntimeException failure) {
            undo(inventory, buyer.id(), money ? price : BigDecimal.ZERO);
            return new Outcome(Status.FAILED, 0, price, Optional.of(reason(failure)));
        }
        drop(buyer, overflow);
        return new Outcome(Status.DONE, units, price, Optional.empty());
    }

    /** Overflow leaves only after the commit, so a rolled back purchase never leaves items in the world. */
    private void drop(Buyer<S> buyer, List<S> overflow) {
        for (S extra : overflow) {
            try { buyer.drop().accept(extra); }
            catch (RuntimeException ignored) { /* The purchase is complete; a failed drop cannot be undone safely. */ }
        }
    }

    private Outcome sell(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, int units, BigDecimal price, boolean money, MatchRule rule) {
        try {
            if (!inventory.remove(prepared.goods(), units, rule)) return Outcome.of(Status.NOT_ENOUGH_ITEMS);
            if (money && !deposit(buyer.id(), price)) {
                undo(inventory, buyer.id(), BigDecimal.ZERO);
                return Outcome.of(Status.PAYMENT_FAILED);
            }
            inventory.commit();
            return new Outcome(Status.DONE, units, price, Optional.empty());
        } catch (RuntimeException failure) {
            undo(inventory, buyer.id(), BigDecimal.ZERO);
            return new Outcome(Status.FAILED, 0, price, Optional.of(reason(failure)));
        }
    }

    private Outcome trade(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, BigDecimal price, boolean money, MatchRule rule) {
        boolean paid = false;
        List<S> overflow = new java.util.ArrayList<>();
        try {
            for (Need<S> need : prepared.needs()) {
                if (!inventory.remove(need.stack(), need.amount(), rule)) { undo(inventory, buyer.id(), BigDecimal.ZERO); return Outcome.of(Status.NOT_ENOUGH_ITEMS); }
            }
            for (Need<S> give : prepared.gives()) {
                if (prepared.entry().dropWhenFull()) overflow.addAll(inventory.addOrOverflow(give.stack(), give.amount()));
                else if (!inventory.add(give.stack(), give.amount())) { undo(inventory, buyer.id(), BigDecimal.ZERO); return Outcome.of(Status.INVENTORY_FULL); }
            }
            if (money) {
                if (!withdraw(buyer.id(), price)) { undo(inventory, buyer.id(), BigDecimal.ZERO); return new Outcome(Status.INSUFFICIENT_FUNDS, 0, price, Optional.empty()); }
                paid = true;
            }
            inventory.commit();
        } catch (RuntimeException failure) {
            undo(inventory, buyer.id(), paid ? price : BigDecimal.ZERO);
            return new Outcome(Status.FAILED, 0, price, Optional.of(reason(failure)));
        }
        drop(buyer, overflow);
        return new Outcome(Status.DONE, 1, price, Optional.empty());
    }

    private Outcome command(Buyer<S> buyer, InventoryTransaction<S> inventory, BigDecimal price, boolean money) {
        if (money && !withdraw(buyer.id(), price)) return new Outcome(Status.INSUFFICIENT_FUNDS, 0, price, Optional.empty());
        inventory.commit();
        return new Outcome(Status.DONE, 1, price, Optional.empty());
    }

    private boolean withdraw(UUID player, BigDecimal price) {
        try { return economy.has(player, price) && economy.withdraw(player, price); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean deposit(UUID player, BigDecimal price) {
        try { return economy.deposit(player, price); }
        catch (RuntimeException failure) { return false; }
    }

    /** Restores the storage even if part of it fails, then refunds. */
    private void undo(InventoryTransaction<S> inventory, UUID player, BigDecimal refund) {
        try { inventory.rollback(); }
        catch (RuntimeException ignored) { /* The refund still runs; the failure was reported by the caller's outcome. */ }
        finally { if (refund.signum() > 0) deposit(player, refund); }
    }

    private static String reason(RuntimeException failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
