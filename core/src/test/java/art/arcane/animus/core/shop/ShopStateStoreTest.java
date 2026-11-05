package art.arcane.animus.core.shop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShopStateStoreTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final ShopLedger.Rules LIMIT = new ShopLedger.Rules(new ShopDocument.Limit(10, -1, -1), Optional.empty());
    @TempDir Path folder;

    private ShopStateStore open(List<String> warnings) throws Exception {
        return new ShopStateStore(folder.resolve("state"), folder.resolve("logs"), 5, warnings::add);
    }

    @Test void countersSurviveARestart() throws Exception {
        try (ShopStateStore store = open(new ArrayList<>())) {
            store.ledger().record("town:baker", "bread", LIMIT, ALEX, 6, Instant.now());
        }
        try (ShopStateStore store = open(new ArrayList<>())) {
            assertEquals(4, store.ledger().available("town:baker", "bread", LIMIT, ALEX, Instant.now()));
        }
    }

    @Test void aHotloadedFlushDelayAppliesToTheNextWrite() throws Exception {
        try (ShopStateStore store = new ShopStateStore(folder.resolve("state"), folder.resolve("logs"), 600_000, new ArrayList<String>()::add)) {
            store.flushMillis(5);
            store.ledger().record("town:baker", "bread", LIMIT, ALEX, 1, Instant.now());
            Path journal = folder.resolve("state/ledger.journal");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(journal) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(journal), "the change waited for the old ten minute delay");
        }
    }

    @Test void journaledChangesSurviveWithoutACleanShutdown() throws Exception {
        ShopStateStore crashed = open(new ArrayList<>());
        crashed.ledger().record("town:baker", "bread", LIMIT, ALEX, 3, Instant.now());
        crashed.flush();
        assertTrue(Files.exists(folder.resolve("state/ledger.journal")));
        try (ShopStateStore store = open(new ArrayList<>())) {
            assertEquals(7, store.ledger().available("town:baker", "bread", LIMIT, ALEX, Instant.now()));
        }
    }

    @Test void aTornLastJournalLineIsIgnoredWithAWarning() throws Exception {
        ShopStateStore crashed = open(new ArrayList<>());
        crashed.ledger().record("town:baker", "bread", LIMIT, ALEX, 2, Instant.now());
        crashed.flush();
        Files.writeString(folder.resolve("state/ledger.journal"), "{\"kind\":\"pla", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        List<String> warnings = new ArrayList<>();
        try (ShopStateStore store = open(warnings)) {
            assertEquals(8, store.ledger().available("town:baker", "bread", LIMIT, ALEX, Instant.now()));
        }
        assertTrue(warnings.stream().anyMatch((String warning) -> warning.contains("incomplete last line")), warnings.toString());
    }

    @Test void importedCountsMergeOnce() throws Exception {
        Files.createDirectories(folder.resolve("state/imports"));
        Files.writeString(folder.resolve("state/imports/first.json"),
                "{\"counts\":[{\"shop\":\"town:baker\",\"entry\":\"bread\",\"player\":\"" + ALEX + "\",\"used\":5}]}");
        try (ShopStateStore store = open(new ArrayList<>())) {
            assertEquals(5, store.ledger().available("town:baker", "bread", LIMIT, ALEX, Instant.now()));
        }
        try (ShopStateStore store = open(new ArrayList<>())) {
            assertEquals(5, store.ledger().available("town:baker", "bread", LIMIT, ALEX, Instant.now()));
        }
    }

    @Test void transactionLogsGoToADailyFilePerShop() throws Exception {
        try (ShopStateStore store = open(new ArrayList<>())) {
            store.log("town:baker", "Alex bought 4x Bread for 12");
        }
        try (var files = Files.walk(folder.resolve("logs/town/baker"))) {
            List<Path> logs = files.filter(Files::isRegularFile).toList();
            assertEquals(1, logs.size());
            assertTrue(Files.readString(logs.getFirst()).contains("Alex bought 4x Bread for 12"));
        }
    }
}
