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
 * Atomic shop purchases on the player's owner thread. Money, experience levels and items move together or not at all: a
 * step that fails restores the player's storage and refunds what was taken. Limits and stock are counted only after a purchase
 * completes. Commands of a command entry are the caller's to run after {@link Status#DONE}.
 *
 * @param <S> the platform's item stack
 */
public final class ShopTransactions<S> {
    public enum Status {
        DONE, NOT_TRADABLE, NO_PERMISSION, LIMIT_REACHED, OUT_OF_STOCK, ECONOMY_UNAVAILABLE, INSUFFICIENT_FUNDS, INVENTORY_FULL,
        NOT_ENOUGH_ITEMS, NOTHING_TO_SELL, PAYMENT_FAILED, FAILED, CONTAINER_FULL, CONTAINER_UNAVAILABLE, NOT_ENOUGH_LEVELS
    }
    /** The buyer's experience levels. */
    public interface Levels {
        /** Takes {@code levels} when the player has that many; false, and nothing taken, otherwise. */
        boolean take(int levels);
        void give(int levels);
    }
    public record Need<S>(S stack, int amount) {}
    /**
     * An entry with its platform stacks built once: a one-item stack of the goods, and the trade lists. {@code match} is
     * {@link ShopDocument#rule}.
     */
    public record Prepared<S>(ShopDocument.Entry entry, MatchRule match, S goods, List<Need<S>> needs, List<Need<S>> gives) {
        public Prepared { Objects.requireNonNull(entry); Objects.requireNonNull(match); needs = List.copyOf(needs); gives = List.copyOf(gives); }
    }
    public record Buyer<S>(UUID id, Predicate<String> permitted, TradeInventory<S> inventory, Consumer<S> drop, Levels levels) {}
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
    public Outcome perform(String shop, Prepared<S> prepared, Buyer<S> buyer, boolean fullStack) { return perform(shop, prepared, buyer, fullStack, Optional.empty()); }

    /**
     * With {@code stock}, the opened containers of an owned shop: only trades run, the gives leave the stock and the
     * needs enter it, and both storages change together or not at all.
     */
    public Outcome perform(String shop, Prepared<S> prepared, Buyer<S> buyer, boolean fullStack, Optional<TradeInventory<S>> stock) {
        ShopDocument.Entry entry = prepared.entry();
        if (entry.type() == ShopDocument.Type.STATIC) return Outcome.of(Status.NOT_TRADABLE);
        if (stock.isPresent() && entry.type() != ShopDocument.Type.TRADE) return Outcome.of(Status.NOT_TRADABLE);
        if (entry.permission().isPresent() && !buyer.permitted().test(entry.permission().get())) return Outcome.of(Status.NO_PERMISSION);
        Instant now = clock.get();
        int base = entry.item().count();
        int units = switch (entry.type()) {
            case BUY, SELL -> fullStack && entry.allowStack() ? Math.max(base, items.maxStackSize(prepared.goods())) : base;
            default -> 1;
        };
        InventoryTransaction<S> inventory = InventoryTransaction.begin(buyer.inventory(), items);
        MatchRule rule = prepared.match();
        if (entry.type() == ShopDocument.Type.SELL) {
            int held = inventory.count(prepared.goods(), rule);
            if (held == 0) return Outcome.of(Status.NOTHING_TO_SELL);
            if (held < units) {
                if (!entry.sellPartial()) return Outcome.of(Status.NOT_ENOUGH_ITEMS);
                units = held;
            }
        }
        BigDecimal price = scaled(entry.price(now), units, entry.type() == ShopDocument.Type.BUY || entry.type() == ShopDocument.Type.SELL ? base : 1);
        boolean money = price.signum() > 0;
        int levels = entry.type() == ShopDocument.Type.BUY ? scaledLevels(entry.levels(), units, base) : entry.levels();
        Payment payment = new Payment(buyer, price, money, levels);
        ShopLedger.Rules rules = ShopLedger.Rules.of(entry);
        Optional<ShopLedger.Reservation> reserved = Optional.empty();
        if (rules.any()) {
            // Held before anything moves, so buyers on other region threads cannot both take the last units.
            reserved = ledger.reserve(shop, entry.id(), rules, buyer.id(), units, now);
            if (reserved.isEmpty()) return Outcome.of(ledger.stockShort(shop, entry.id(), rules, units, now) ? Status.OUT_OF_STOCK : Status.LIMIT_REACHED);
        }
        Outcome outcome = Outcome.of(Status.FAILED);
        try {
            outcome = money && economy.unavailable().isPresent() ? new Outcome(Status.ECONOMY_UNAVAILABLE, 0, price, economy.unavailable()) : switch (entry.type()) {
                case BUY -> buy(prepared, buyer, inventory, units, payment);
                case SELL -> sell(prepared, buyer, inventory, units, price, money, rule);
                case TRADE -> stock.isPresent() ? stocked(prepared, buyer, inventory, InventoryTransaction.begin(stock.get(), items), rule)
                        : trade(prepared, buyer, inventory, payment, rule);
                case COMMAND -> command(inventory, payment);
                case STATIC -> Outcome.of(Status.NOT_TRADABLE);
            };
            return outcome;
        } finally {
            if (reserved.isPresent()) {
                if (outcome.done()) ledger.complete(reserved.get(), outcome.units(), now);
                else ledger.release(reserved.get());
            }
        }
    }

    /** The levels for {@code units} when {@code levels} is for {@code per} items, rounded up. */
    static int scaledLevels(int levels, int units, int per) { return (int) Math.min(Integer.MAX_VALUE, ((long) levels * units + per - 1) / per); }

    /** The price of {@code units} when {@code price} is for {@code per} items. */
    static BigDecimal scaled(BigDecimal price, int units, int per) {
        if (units == per) return price;
        BigDecimal result = price.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(per), 4, RoundingMode.HALF_UP);
        return result.stripTrailingZeros().scale() < 0 ? result.setScale(0, RoundingMode.UNNECESSARY) : result.stripTrailingZeros();
    }

    /** What a purchase costs: money through the economy and experience levels from the buyer. */
    private record Payment(Buyer<?> buyer, BigDecimal price, boolean money, int levels) {}

    /** Takes the levels, then the money; empty when both were paid, else the refusal with nothing taken. */
    private Optional<Status> pay(Payment payment) {
        if (payment.levels() > 0 && !payment.buyer().levels().take(payment.levels())) return Optional.of(Status.NOT_ENOUGH_LEVELS);
        if (payment.money() && !withdraw(payment.buyer().id(), payment.price())) {
            if (payment.levels() > 0) payment.buyer().levels().give(payment.levels());
            return Optional.of(Status.INSUFFICIENT_FUNDS);
        }
        return Optional.empty();
    }

    /** Gives back what {@link #pay} took. */
    private void refund(Payment payment) {
        if (payment.money()) deposit(payment.buyer().id(), payment.price());
        if (payment.levels() > 0) payment.buyer().levels().give(payment.levels());
    }

    private Outcome buy(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, int units, Payment payment) {
        ShopDocument.Entry entry = prepared.entry();
        BigDecimal price = payment.price();
        if (!entry.dropWhenFull() && inventory.room(prepared.goods()) < units) return Outcome.of(Status.INVENTORY_FULL);
        Optional<Status> refused = pay(payment);
        if (refused.isPresent()) return new Outcome(refused.get(), 0, price, Optional.empty());
        List<S> overflow;
        try {
            overflow = entry.dropWhenFull() ? inventory.addOrOverflow(prepared.goods(), units) : List.of();
            if (!entry.dropWhenFull() && !inventory.add(prepared.goods(), units)) throw new IllegalStateException("room vanished");
            inventory.commit();
        } catch (RuntimeException failure) {
            undo(inventory, Optional.of(payment));
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
                undo(inventory, Optional.empty());
                return Outcome.of(Status.PAYMENT_FAILED);
            }
            inventory.commit();
            return new Outcome(Status.DONE, units, price, Optional.empty());
        } catch (RuntimeException failure) {
            undo(inventory, Optional.empty());
            return new Outcome(Status.FAILED, 0, price, Optional.of(reason(failure)));
        }
    }

    private Outcome trade(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, Payment payment, MatchRule rule) {
        BigDecimal price = payment.price();
        boolean paid = false;
        List<S> overflow = new java.util.ArrayList<>();
        try {
            for (Need<S> need : prepared.needs()) {
                if (!inventory.remove(need.stack(), need.amount(), rule)) { undo(inventory, Optional.empty()); return Outcome.of(Status.NOT_ENOUGH_ITEMS); }
            }
            for (Need<S> give : prepared.gives()) {
                if (prepared.entry().dropWhenFull()) overflow.addAll(inventory.addOrOverflow(give.stack(), give.amount()));
                else if (!inventory.add(give.stack(), give.amount())) { undo(inventory, Optional.empty()); return Outcome.of(Status.INVENTORY_FULL); }
            }
            Optional<Status> refused = pay(payment);
            if (refused.isPresent()) { undo(inventory, Optional.empty()); return new Outcome(refused.get(), 0, price, Optional.empty()); }
            paid = true;
            inventory.commit();
        } catch (RuntimeException failure) {
            undo(inventory, paid ? Optional.of(payment) : Optional.empty());
            return new Outcome(Status.FAILED, 0, price, Optional.of(reason(failure)));
        }
        drop(buyer, overflow);
        return new Outcome(Status.DONE, 1, price, Optional.empty());
    }

    /** An owned shop's trade: item for item between the buyer and the shop's containers, never money. */
    private Outcome stocked(Prepared<S> prepared, Buyer<S> buyer, InventoryTransaction<S> inventory, InventoryTransaction<S> stock, MatchRule rule) {
        List<S> overflow = new java.util.ArrayList<>();
        try {
            for (Need<S> give : prepared.gives())
                if (!stock.remove(give.stack(), give.amount(), MatchRule.STRICT)) { both(inventory, stock); return Outcome.of(Status.OUT_OF_STOCK); }
            for (Need<S> need : prepared.needs())
                if (!inventory.remove(need.stack(), need.amount(), rule)) { both(inventory, stock); return Outcome.of(Status.NOT_ENOUGH_ITEMS); }
            for (Need<S> need : prepared.needs())
                if (!stock.add(need.stack(), need.amount())) { both(inventory, stock); return Outcome.of(Status.CONTAINER_FULL); }
            for (Need<S> give : prepared.gives()) {
                if (prepared.entry().dropWhenFull()) overflow.addAll(inventory.addOrOverflow(give.stack(), give.amount()));
                else if (!inventory.add(give.stack(), give.amount())) { both(inventory, stock); return Outcome.of(Status.INVENTORY_FULL); }
            }
            stock.commit();
            inventory.commit();
        } catch (RuntimeException failure) {
            both(inventory, stock);
            return new Outcome(Status.FAILED, 0, BigDecimal.ZERO, Optional.of(reason(failure)));
        }
        drop(buyer, overflow);
        return new Outcome(Status.DONE, 1, BigDecimal.ZERO, Optional.empty());
    }

    private static <S> void both(InventoryTransaction<S> inventory, InventoryTransaction<S> stock) {
        try { stock.rollback(); }
        catch (RuntimeException ignored) { /* The player's storage is still restored below. */ }
        finally { inventory.rollback(); }
    }

    private Outcome command(InventoryTransaction<S> inventory, Payment payment) {
        Optional<Status> refused = pay(payment);
        if (refused.isPresent()) return new Outcome(refused.get(), 0, payment.price(), Optional.empty());
        inventory.commit();
        return new Outcome(Status.DONE, 1, payment.price(), Optional.empty());
    }

    private boolean withdraw(UUID player, BigDecimal price) {
        try { return economy.has(player, price) && economy.withdraw(player, price); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean deposit(UUID player, BigDecimal price) {
        try { return economy.deposit(player, price); }
        catch (RuntimeException failure) { return false; }
    }

    /** Restores the storage even if part of it fails, then refunds what was paid. */
    private void undo(InventoryTransaction<S> inventory, Optional<Payment> paid) {
        try { inventory.rollback(); }
        catch (RuntimeException ignored) { /* The refund still runs; the failure was reported by the caller's outcome. */ }
        finally { paid.ifPresent(this::refund); }
    }

    private static String reason(RuntimeException failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
