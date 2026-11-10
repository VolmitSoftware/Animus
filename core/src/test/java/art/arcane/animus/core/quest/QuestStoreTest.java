package art.arcane.animus.core.quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuestStoreTest {
    @TempDir Path folder;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void recordsSurviveARestart() throws Exception {
        try (QuestStore store = new QuestStore(folder, 10_000, (String warning) -> fail(warning))) {
            store.record(PLAYER).put("town:herbs", new QuestRecord.Active(0, new int[]{1}, 5, false, 1));
            store.changed(PLAYER);
        }
        try (QuestStore store = new QuestStore(folder, 0, (String warning) -> fail(warning))) {
            assertEquals(Optional.of(new QuestRecord.Active(0, new int[]{1}, 5, false, 1)), store.record(PLAYER).active("town:herbs"));
            store.record(PLAYER).forgetAll();
            store.changed(PLAYER);
            store.flush();
            assertFalse(Files.exists(folder.resolve(PLAYER + ".json")), "an empty record leaves no file");
        }
    }

    @Test void aDamagedFileIsSetAsideAndTheRestLoads() throws Exception {
        Files.writeString(folder.resolve(PLAYER + ".json"), "{not json");
        List<String> warnings = new ArrayList<>();
        try (QuestStore store = new QuestStore(folder, 0, warnings::add)) {
            assertTrue(store.record(PLAYER).empty());
            assertEquals(1, store.problems().size());
        }
        assertEquals(1, warnings.size());
        try (var files = Files.list(folder)) { assertTrue(files.anyMatch((Path file) -> file.getFileName().toString().startsWith(PLAYER + ".json.bad-"))); }
    }

    @Test void aRecordWithAnUnreadableSchemaVersionIsSetAsideInsteadOfThrowing() throws Exception {
        List<String> damaged = List.of("{\"schemaVersion\": null}", "{\"schemaVersion\": {}}", "{\"schemaVersion\": [1]}", "[]", "null");
        for (String text : damaged) {
            Path folder = Files.createTempDirectory(this.folder, "damaged");
            Files.writeString(folder.resolve(PLAYER + ".json"), text);
            List<String> warnings = new ArrayList<>();
            try (QuestStore store = new QuestStore(folder, 0, warnings::add)) {
                assertTrue(store.record(PLAYER).empty(), text);
                assertEquals(1, store.problems().size(), text);
            }
            try (var files = Files.list(folder)) { assertTrue(files.anyMatch((Path file) -> file.getFileName().toString().startsWith(PLAYER + ".json.bad-")), text); }
        }
    }

    @Test void recordsLoadWhenUsedAndLeaveMemoryOnceReleasedAndWritten() throws Exception {
        try (QuestStore store = new QuestStore(folder, 10_000, (String warning) -> fail(warning))) {
            store.record(PLAYER).put("town:herbs", new QuestRecord.Active(0, new int[]{1}, 5, false, 1));
            store.changed(PLAYER);
        }
        try (QuestStore store = new QuestStore(folder, 10_000, (String warning) -> fail(warning))) {
            assertEquals(0, store.loaded(), "nothing is read at start");
            assertTrue(store.peek(PLAYER).orElseThrow().active("town:herbs").isPresent(), "an offline record can be read");
            assertTrue(store.peek(UUID.randomUUID()).isEmpty());
            assertEquals(0, store.loaded(), "reading does not keep or create records");
            QuestRecord record = store.record(PLAYER);
            assertEquals(1, store.loaded());
            record.drop("town:herbs");
            store.changed(PLAYER);
            store.release(PLAYER);
            assertEquals(1, store.loaded(), "a changed record stays until it is written");
            store.flush();
            assertEquals(0, store.loaded());
            assertFalse(Files.exists(folder.resolve(PLAYER + ".json")));
            store.record(PLAYER);
            store.release(PLAYER);
            store.record(PLAYER);
            store.flush();
            assertEquals(1, store.loaded(), "a player who came back keeps their record");
        }
    }
}
