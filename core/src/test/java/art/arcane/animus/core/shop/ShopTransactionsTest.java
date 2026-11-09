package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.shop.ShopTransactions.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopTransactionsTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private final MockEconomy economy = new MockEconomy();
    private final List<ShopLedger.Change> journal = new ArrayList<>();
    private final ShopLedger ledger = new ShopLedger(journal::add);
    private final List<Stack> dropped = new ArrayList<>();

    private static ShopDocument shop(String grid, String entries) {
        String json = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"title\":\"Baker\",\"pages\":[{\"name\":\"One\",\"rows\":3,\"" + grid + "\":{\"10\":" + entries + "}}]}";
        ShopDocument.Result result = ShopDocument.parse("town:baker", json);
        assertEquals(List.of(), result.problems());
        return result.shop().orElseThrow();
    }

    private static Stack stack(NpcItem item) { return new Stack(item.id(), 1, "", item.components().orElse("")); }

    private static ShopTransactions.Prepared<Stack> prepare(ShopDocument shop) {
        ShopDocument.Entry entry = shop.pages().getFirst().grids().values().iterator().next().get(10);
        List<ShopTransactions.Need<Stack>> needs = entry.needs().stream().map((ShopDocument.Goods goods) -> new ShopTransactions.Need<>(stack(goods.item()), goods.amount())).toList();
        List<ShopTransactions.Need<Stack>> gives = entry.gives().stream().map((ShopDocument.Goods goods) -> new ShopTransactions.Need<>(stack(goods.item()), goods.amount())).toList();
        return new ShopTransactions.Prepared<>(entry, stack(entry.item()), needs, gives);
    }

    private ShopTransactions.Buyer<Stack> buyer(MockInventory inventory, Set<String> permissions) {
        return new ShopTransactions.Buyer<>(ALEX, permissions::contains, inventory, dropped::add);
    }

    private ShopTransactions<Stack> transactions(Economy money) { return new ShopTransactions<>(MockInventory.OPS, money, ledger, () -> NOW); }

    private static final String BREAD = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":12,\"allowStack\":true}";

    @Test void buyingTakesMoneyThenGivesTheItems() {
        economy.give(ALEX, "20");
        MockInventory inventory = new MockInventory();
        ShopTransactions.Outcome outcome = transactions(economy).perform(shop("buy", BREAD).id(), prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false);
        assertEquals(Status.DONE, outcome.status());
        assertEquals(4, inventory.total("minecraft:bread"));
        assertEquals(0, new BigDecimal("8").compareTo(economy.balance(ALEX)));
    }

    @Test void aFullStackCostsThePricePerItemTimesTheStackSize() {
        economy.give(ALEX, "1000");
        MockInventory inventory = new MockInventory();
        ShopTransactions.Outcome outcome = transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), true);
        assertEquals(Status.DONE, outcome.status());
        assertEquals(64, inventory.total("minecraft:bread"));
        assertEquals(0, new BigDecimal("192").compareTo(outcome.price()));
    }

    @Test void buyingWithoutMoneyChangesNothing() {
        economy.give(ALEX, "11.99");
        MockInventory inventory = new MockInventory();
        assertEquals(Status.INSUFFICIENT_FUNDS, transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false).status());
        assertEquals(0, inventory.writes);
        assertEquals(0, new BigDecimal("11.99").compareTo(economy.balance(ALEX)));
        assertTrue(journal.isEmpty());
    }

    @Test void buyingIntoAFullInventoryIsRefusedBeforePayment() {
        economy.give(ALEX, "100");
        MockInventory inventory = new MockInventory().fill(Stack.of("minecraft:stone", 64));
        assertEquals(Status.INVENTORY_FULL, transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false).status());
        assertEquals(0, economy.withdrawals);
    }

    @Test void partialStacksCountAsRoomForAPurchase() {
        economy.give(ALEX, "100");
        MockInventory inventory = new MockInventory().fill(Stack.of("minecraft:stone", 64)).with(7, Stack.of("minecraft:bread", 60));
        assertEquals(Status.DONE, transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false).status());
        assertEquals(64, inventory.get(7).count());
    }

    @Test void dropWhenFullHandsTheRestToTheDropper() {
        economy.give(ALEX, "100");
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":1,\"dropWhenFull\":true}";
        MockInventory inventory = new MockInventory().fill(Stack.of("minecraft:stone", 64));
        assertEquals(Status.DONE, transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(inventory, Set.of()), false).status());
        assertEquals(List.of(Stack.of("minecraft:bread", 4)), dropped);
    }

    @Test void moneyPricesFailClosedWithoutAnEconomy() {
        MockInventory inventory = new MockInventory();
        ShopTransactions.Outcome outcome = transactions(Economy.none("no economy plugin")).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false);
        assertEquals(Status.ECONOMY_UNAVAILABLE, outcome.status());
        assertEquals("no economy plugin", outcome.detail().orElseThrow());
        assertEquals(0, inventory.writes);
    }

    @Test void freeItemsNeedNoEconomy() {
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":0}";
        MockInventory inventory = new MockInventory();
        assertEquals(Status.DONE, transactions(Economy.none("none")).perform("town:baker", prepare(shop("buy", entry)), buyer(inventory, Set.of()), false).status());
        assertEquals(1, inventory.total("minecraft:bread"));
    }

    @Test void entryPermissionsAreChecked() {
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":0,\"permission\":\"town.bread\"}";
        assertEquals(Status.NO_PERMISSION, transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false).status());
        assertEquals(Status.DONE, transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of("town.bread")), false).status());
    }

    @Test void perPlayerLimitsStopFurtherPurchases() {
        economy.give(ALEX, "1000");
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":1,\"limit\":{\"perPlayer\":8}}";
        ShopDocument shop = shop("buy", entry);
        MockInventory inventory = new MockInventory();
        ShopTransactions<Stack> transactions = transactions(economy);
        assertEquals(Status.DONE, transactions.perform("town:baker", prepare(shop), buyer(inventory, Set.of()), false).status());
        assertEquals(Status.DONE, transactions.perform("town:baker", prepare(shop), buyer(inventory, Set.of()), false).status());
        assertEquals(Status.LIMIT_REACHED, transactions.perform("town:baker", prepare(shop), buyer(inventory, Set.of()), false).status());
        assertEquals(8, inventory.total("minecraft:bread"));
    }

    @Test void stockRunsOutForEveryone() {
        economy.give(ALEX, "1000");
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":1,\"stock\":{\"maximum\":4}}";
        ShopDocument shop = shop("buy", entry);
        ShopTransactions<Stack> transactions = transactions(economy);
        assertEquals(Status.DONE, transactions.perform("town:baker", prepare(shop), buyer(new MockInventory(), Set.of()), false).status());
        assertEquals(Status.OUT_OF_STOCK, transactions.perform("town:baker", prepare(shop), buyer(new MockInventory(), Set.of()), false).status());
    }

    /** Withdraws like the mock, but the first withdrawal waits until the test lets it go. */
    private static final class SlowEconomy implements Economy {
        final MockEconomy money = new MockEconomy();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean refuseFirst;
        @Override public Optional<String> unavailable() { return Optional.empty(); }
        @Override public boolean has(UUID player, BigDecimal amount) { return money.has(player, amount); }
        @Override public boolean withdraw(UUID player, BigDecimal amount) {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("never released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (refuseFirst) return false;
            }
            synchronized (money) { return money.withdraw(player, amount); }
        }
        @Override public boolean deposit(UUID player, BigDecimal amount) { synchronized (money) { return money.deposit(player, amount); } }
        @Override public String format(BigDecimal amount) { return amount.toPlainString(); }
    }

    private static final UUID BLAKE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String LAST_LOAF = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":1,\"stock\":{\"maximum\":4}}";

    @Test void twoBuyersOnDifferentThreadsCannotBothTakeTheLastStock() throws Exception {
        SlowEconomy slow = new SlowEconomy();
        slow.money.give(ALEX, "10").give(BLAKE, "10");
        ShopTransactions<Stack> transactions = transactions(slow);
        ShopDocument shop = shop("buy", LAST_LOAF);
        java.util.concurrent.CompletableFuture<Status> first = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                transactions.perform("town:baker", prepare(shop), buyer(new MockInventory(), Set.of()), false).status());
        assertTrue(slow.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.CompletableFuture<Status> second = java.util.concurrent.CompletableFuture.supplyAsync(() -> transactions.perform("town:baker", prepare(shop),
                new ShopTransactions.Buyer<>(BLAKE, (String node) -> false, new MockInventory(), dropped::add), false).status());
        Status late = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        slow.release.countDown();
        assertEquals(Status.DONE, first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Status.OUT_OF_STOCK, late, "the stock the first buyer holds is not sold again");
        assertEquals(4, ledger.stockUsed("town:baker", "bread", NOW));
    }

    @Test void aFailedPaymentGivesTheReservedStockBack() throws Exception {
        SlowEconomy slow = new SlowEconomy();
        slow.refuseFirst = true;
        slow.money.give(ALEX, "10");
        ShopTransactions<Stack> transactions = transactions(slow);
        ShopDocument shop = shop("buy", LAST_LOAF);
        java.util.concurrent.CompletableFuture<Status> first = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                transactions.perform("town:baker", prepare(shop), buyer(new MockInventory(), Set.of()), false).status());
        assertTrue(slow.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        slow.release.countDown();
        assertEquals(Status.INSUFFICIENT_FUNDS, first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Status.DONE, transactions.perform("town:baker", prepare(shop), buyer(new MockInventory(), Set.of()), false).status());
        assertEquals(4, ledger.stockUsed("town:baker", "bread", NOW));
    }

    @Test void aGivingFailureRefundsThePayment() {
        economy.give(ALEX, "20");
        MockInventory inventory = new MockInventory() {
            @Override public void set(int slot, Stack stack) { throw new IllegalStateException("inventory went away"); }
        };
        ShopTransactions.Outcome outcome = transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(inventory, Set.of()), false);
        assertEquals(Status.FAILED, outcome.status());
        assertEquals(0, new BigDecimal("20").compareTo(economy.balance(ALEX)));
    }

    private static final String SELL = "{\"id\":\"wheat\",\"type\":\"sell\",\"item\":{\"id\":\"minecraft:wheat\",\"count\":8},\"price\":2}";

    @Test void sellingTakesItemsFromAnywhereAndPays() {
        MockInventory inventory = new MockInventory().with(3, Stack.of("minecraft:wheat", 5)).with(30, Stack.of("minecraft:wheat", 5));
        ShopTransactions.Outcome outcome = transactions(economy).perform("town:baker", prepare(shop("sell", SELL)), buyer(inventory, Set.of()), false);
        assertEquals(Status.DONE, outcome.status());
        assertEquals(2, inventory.total("minecraft:wheat"));
        assertEquals(0, new BigDecimal("2").compareTo(economy.balance(ALEX)));
    }

    @Test void sellingTooFewIsRefusedUnlessPartialSalesAreOn() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:wheat", 3));
        assertEquals(Status.NOT_ENOUGH_ITEMS, transactions(economy).perform("town:baker", prepare(shop("sell", SELL)), buyer(inventory, Set.of()), false).status());
        String partial = SELL.replace("\"price\":2", "\"price\":2,\"sellPartial\":true");
        ShopTransactions.Outcome outcome = transactions(economy).perform("town:baker", prepare(shop("sell", partial)), buyer(inventory, Set.of()), false);
        assertEquals(Status.DONE, outcome.status());
        assertEquals(3, outcome.units());
        assertEquals(0, new BigDecimal("0.75").compareTo(economy.balance(ALEX)));
    }

    @Test void aSaleThatWouldRemoveNothingIsRefused() {
        String partial = SELL.replace("\"price\":2", "\"price\":2,\"sellPartial\":true");
        assertEquals(Status.NOTHING_TO_SELL, transactions(economy).perform("town:baker", prepare(shop("sell", partial)), buyer(new MockInventory(), Set.of()), false).status());
    }

    @Test void aFailedPayoutPutsTheItemsBack() {
        economy.failDeposits = true;
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:wheat", 8));
        assertEquals(Status.PAYMENT_FAILED, transactions(economy).perform("town:baker", prepare(shop("sell", SELL)), buyer(inventory, Set.of()), false).status());
        assertEquals(8, inventory.total("minecraft:wheat"));
        assertTrue(journal.isEmpty());
    }

    @Test void sellingNeedsTheExactItemUnderTheStrictRule() {
        String strict = "{\"id\":\"gem\",\"type\":\"sell\",\"item\":{\"id\":\"minecraft:emerald\",\"count\":1,\"components\":\"[custom_name='Gem']\"},\"price\":5}";
        MockInventory plain = new MockInventory().with(0, Stack.of("minecraft:emerald", 4));
        assertEquals(Status.NOTHING_TO_SELL, transactions(economy).perform("town:baker", prepare(shop("sell", strict)), buyer(plain, Set.of()), false).status());
    }

    private static final String TRADE = "{\"id\":\"swap\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"},"
            + "\"needs\":[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":10},{\"item\":{\"id\":\"minecraft:gold_ingot\"},\"amount\":2}],"
            + "\"gives\":[{\"item\":{\"id\":\"minecraft:diamond\"},\"amount\":1}]}";

    @Test void tradesTakeEveryNeededItemAndGiveTheRewards() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 6)).with(1, Stack.of("minecraft:emerald", 6)).with(2, Stack.of("minecraft:gold_ingot", 2));
        assertEquals(Status.DONE, transactions(Economy.none("none")).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false).status());
        assertEquals(2, inventory.total("minecraft:emerald"));
        assertEquals(0, inventory.total("minecraft:gold_ingot"));
        assertEquals(1, inventory.total("minecraft:diamond"));
    }

    @Test void aTradeMissingOneNeedChangesNothing() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 12)).with(2, Stack.of("minecraft:gold_ingot", 1));
        Stack[] before = inventory.copy();
        assertEquals(Status.NOT_ENOUGH_ITEMS, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false).status());
        assertArrayEquals(before, inventory.copy());
    }

    @Test void aTradeChecksRoomAfterTakingTheNeeds() {
        MockInventory inventory = new MockInventory(2).with(0, Stack.of("minecraft:emerald", 10)).with(1, Stack.of("minecraft:gold_ingot", 2));
        assertEquals(Status.DONE, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false).status());
        assertEquals(1, inventory.total("minecraft:diamond"));
    }

    @Test void aTradeWithAMoneyPriceRollsBackWhenThePlayerCannotPay() {
        String priced = TRADE.replace("\"item\":{\"id\":\"minecraft:diamond\"},\"needs\"", "\"item\":{\"id\":\"minecraft:diamond\"},\"price\":5,\"needs\"");
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 10)).with(1, Stack.of("minecraft:gold_ingot", 2));
        Stack[] before = inventory.copy();
        assertEquals(Status.INSUFFICIENT_FUNDS, transactions(economy).perform("town:baker", prepare(shop("trade", priced)), buyer(inventory, Set.of()), false).status());
        assertArrayEquals(before, inventory.copy());
    }

    @Test void aTradeIntoAFullInventoryPutsTheNeedsBack() {
        MockInventory inventory = new MockInventory(3).with(0, Stack.of("minecraft:emerald", 12)).with(1, Stack.of("minecraft:gold_ingot", 4)).with(2, Stack.of("minecraft:stone", 64));
        Stack[] before = inventory.copy();
        assertEquals(Status.INVENTORY_FULL, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false).status());
        assertArrayEquals(before, inventory.copy());
    }

    @Test void commandEntriesChargeAndReportTheCommandsToRun() {
        economy.give(ALEX, "10");
        String entry = "{\"id\":\"pet\",\"type\":\"command\",\"item\":{\"id\":\"minecraft:bone\"},\"price\":10,\"commands\":[{\"command\":\"givepet {player} wolf\"}]}";
        ShopTransactions.Outcome outcome = transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false);
        assertEquals(Status.DONE, outcome.status());
        assertEquals(1, outcome.units());
        assertEquals(0, BigDecimal.ZERO.compareTo(economy.balance(ALEX)));
    }

    @Test void discountsApplyOnlyInsideTheirDates() {
        economy.give(ALEX, "100");
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":10,\"discount\":{\"price\":4,\"start\":\"2026-11-01T00:00:00Z\",\"end\":\"2026-11-05T00:00:00Z\"}}";
        assertEquals(0, new BigDecimal("4").compareTo(transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false).price()));
        ShopTransactions<Stack> later = new ShopTransactions<>(MockInventory.OPS, economy, ledger, () -> Instant.parse("2026-11-06T00:00:00Z"));
        assertEquals(0, new BigDecimal("10").compareTo(later.perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false).price()));
    }

    @Test void staticEntriesAreNotForSale() {
        String entry = "{\"id\":\"sign\",\"type\":\"static\",\"item\":{\"id\":\"minecraft:oak_sign\"}}";
        assertEquals(Status.NOT_TRADABLE, transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false).status());
    }

    @Test void completedPurchasesAreJournaled() {
        economy.give(ALEX, "100");
        String entry = "{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":1,\"limit\":{\"perPlayer\":64,\"resetSeconds\":86400}}";
        transactions(economy).perform("town:baker", prepare(shop("buy", entry)), buyer(new MockInventory(), Set.of()), false);
        assertEquals(1, journal.size());
        assertEquals(4, journal.getFirst().used());
        assertEquals(NOW.plusSeconds(86400).toEpochMilli(), journal.getFirst().windowEnd());
    }

    @Test void aContainerTradeTakesTheGivesFromTheContainerAndStoresTheNeeds() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 12)).with(1, Stack.of("minecraft:gold_ingot", 2));
        MockInventory chest = new MockInventory(27).with(5, Stack.of("minecraft:diamond", 3));
        ShopTransactions.Outcome outcome = transactions(Economy.none("none")).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false, Optional.of(chest));
        assertEquals(Status.DONE, outcome.status());
        assertEquals(1, inventory.total("minecraft:diamond"));
        assertEquals(2, inventory.total("minecraft:emerald"));
        assertEquals(2, chest.total("minecraft:diamond"));
        assertEquals(10, chest.total("minecraft:emerald"));
        assertEquals(2, chest.total("minecraft:gold_ingot"));
    }

    @Test void anEmptyContainerIsOutOfStockAndChangesNothing() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 12)).with(1, Stack.of("minecraft:gold_ingot", 2));
        MockInventory chest = new MockInventory(27);
        Stack[] before = inventory.copy();
        assertEquals(Status.OUT_OF_STOCK, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false, Optional.of(chest)).status());
        assertArrayEquals(before, inventory.copy());
        assertArrayEquals(new MockInventory(27).copy(), chest.copy());
    }

    @Test void aFullContainerRefusesAndRestoresBothSides() {
        MockInventory inventory = new MockInventory().with(0, Stack.of("minecraft:emerald", 12)).with(1, Stack.of("minecraft:gold_ingot", 2));
        MockInventory chest = new MockInventory(2).with(0, Stack.of("minecraft:diamond", 1)).with(1, Stack.of("minecraft:stone", 64));
        Stack[] player = inventory.copy(), stock = chest.copy();
        assertEquals(Status.CONTAINER_FULL, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false, Optional.of(chest)).status());
        assertArrayEquals(player, inventory.copy());
        assertArrayEquals(stock, chest.copy());
    }

    @Test void aContainerTradeIntoAFullPlayerInventoryRestoresTheContainer() {
        MockInventory inventory = new MockInventory(3).with(0, Stack.of("minecraft:emerald", 12)).with(1, Stack.of("minecraft:gold_ingot", 4)).with(2, Stack.of("minecraft:stone", 64));
        MockInventory chest = new MockInventory(27).with(0, Stack.of("minecraft:diamond", 1));
        Stack[] player = inventory.copy(), stock = chest.copy();
        assertEquals(Status.INVENTORY_FULL, transactions(economy).perform("town:baker", prepare(shop("trade", TRADE)), buyer(inventory, Set.of()), false, Optional.of(chest)).status());
        assertArrayEquals(player, inventory.copy());
        assertArrayEquals(stock, chest.copy());
    }

    @Test void containerShopsOnlyTradeItemsForItems() {
        economy.give(ALEX, "100");
        assertEquals(Status.NOT_TRADABLE, transactions(economy).perform("town:baker", prepare(shop("buy", BREAD)), buyer(new MockInventory(), Set.of()), false, Optional.of(new MockInventory(27))).status());
    }
}
