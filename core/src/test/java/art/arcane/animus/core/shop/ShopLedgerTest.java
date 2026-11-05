package art.arcane.animus.core.shop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopLedgerTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NOON = Instant.parse("2026-11-04T12:00:00Z");

    private static ShopLedger.Rules rules(long perPlayer, long global, long reset, long stock, long restock) {
        return new ShopLedger.Rules(new ShopDocument.Limit(perPlayer, global, reset),
                stock < 0 ? java.util.Optional.empty() : java.util.Optional.of(new ShopDocument.Stock(stock, restock)));
    }

    @Test void unlimitedEntriesAreAlwaysAvailable() {
        ShopLedger ledger = new ShopLedger(change -> {});
        assertEquals(Long.MAX_VALUE, ledger.available("town:baker", "bread", rules(-1, -1, -1, -1, -1), ALEX, NOON));
    }

    @Test void perPlayerLimitsCountEachPlayerApart() {
        ShopLedger ledger = new ShopLedger(change -> {});
        ShopLedger.Rules limit = rules(10, -1, -1, -1, -1);
        ledger.record("town:baker", "bread", limit, ALEX, 7, NOON);
        assertEquals(3, ledger.available("town:baker", "bread", limit, ALEX, NOON));
        assertEquals(10, ledger.available("town:baker", "bread", limit, SAM, NOON));
    }

    @Test void globalLimitsAndStockAreShared() {
        ShopLedger ledger = new ShopLedger(change -> {});
        ShopLedger.Rules shared = rules(-1, 20, -1, 15, -1);
        ledger.record("town:baker", "bread", shared, ALEX, 6, NOON);
        ledger.record("town:baker", "bread", shared, SAM, 4, NOON);
        assertEquals(5, ledger.available("town:baker", "bread", shared, SAM, NOON));
    }

    @Test void limitsResetWhenTheirWindowEnds() {
        ShopLedger ledger = new ShopLedger(change -> {});
        ShopLedger.Rules daily = rules(4, -1, 3600, -1, -1);
        ledger.record("town:baker", "bread", daily, ALEX, 4, NOON);
        assertEquals(0, ledger.available("town:baker", "bread", daily, ALEX, NOON.plusSeconds(3599)));
        assertEquals(4, ledger.available("town:baker", "bread", daily, ALEX, NOON.plusSeconds(3600)));
    }

    @Test void stockRestocksOnItsOwnClock() {
        ShopLedger ledger = new ShopLedger(change -> {});
        ShopLedger.Rules stocked = rules(-1, -1, -1, 8, 600);
        ledger.record("town:baker", "bread", stocked, ALEX, 8, NOON);
        assertEquals(0, ledger.available("town:baker", "bread", stocked, SAM, NOON.plusSeconds(10)));
        assertEquals(8, ledger.available("town:baker", "bread", stocked, SAM, NOON.plusSeconds(600)));
    }

    @Test void everyChangeIsJournaledAndReplaysToTheSameState() {
        List<ShopLedger.Change> journal = new ArrayList<>();
        ShopLedger ledger = new ShopLedger(journal::add);
        ShopLedger.Rules all = rules(10, 30, 3600, 25, 600);
        ledger.record("town:baker", "bread", all, ALEX, 3, NOON);
        ledger.record("town:baker", "bread", all, SAM, 2, NOON);
        ShopLedger replayed = new ShopLedger(change -> {});
        journal.forEach(replayed::apply);
        assertEquals(ledger.snapshot(), replayed.snapshot());
        assertEquals(7, replayed.available("town:baker", "bread", all, ALEX, NOON));
        assertEquals(ledger.snapshot().size(), ShopLedger.Change.parseAll(ShopLedger.Change.writeAll(ledger.snapshot())).size());
    }

    @Test void changesSurviveTheirTextForm() {
        ShopLedger.Change change = new ShopLedger.Change(ShopLedger.Kind.PLAYER, "town:baker#bread", java.util.Optional.of(ALEX), 5, 1234);
        assertEquals(change, ShopLedger.Change.parse(change.write()));
    }

    @Test void negativeOrZeroRecordsAreRefused() {
        ShopLedger ledger = new ShopLedger(change -> {});
        assertThrows(IllegalArgumentException.class, () -> ledger.record("town:baker", "bread", rules(1, -1, -1, -1, -1), ALEX, 0, NOON));
    }

    @Test void clearingAPlayerForgetsOnlyTheirCounts() {
        ShopLedger ledger = new ShopLedger(change -> {});
        ShopLedger.Rules limit = rules(5, 50, -1, -1, -1);
        ledger.record("town:baker", "bread", limit, ALEX, 5, NOON);
        ledger.record("town:baker", "bread", limit, SAM, 1, NOON);
        ledger.reset("town:baker", java.util.Optional.of(ALEX));
        assertEquals(5, ledger.available("town:baker", "bread", limit, ALEX, NOON));
        assertEquals(4, ledger.available("town:baker", "bread", limit, SAM, NOON));
    }
}
