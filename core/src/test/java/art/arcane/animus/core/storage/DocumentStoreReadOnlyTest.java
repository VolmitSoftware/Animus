package art.arcane.animus.core.storage;

import art.arcane.animus.core.npc.NpcRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentStoreReadOnlyTest {
    @TempDir Path root;
    private static final String DEFINITION = """
        {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc",
         "id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,
         "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
        """;
    private static final String PENDING = """
        {"id":"12345678-1234-1234-1234-123456789abc","entries":[
          {"path":"npcs/town/guard.json","before":null,"after":"{}"}]}
        """;

    @Test void validationOfAnUnopenedExportCreatesNothingAndDoesNotActivate() throws Exception {
        Path source = root.resolve("npcs/town/guard.json");
        Files.createDirectories(source.getParent()); Files.writeString(source, DEFINITION);
        Map<String,String> before = tree();
        try (DocumentStore store = DocumentStore.readOnly(root)) {
            NpcRepository repository = new NpcRepository(store);
            var validation = repository.validateStored();
            assertTrue(validation.errors().isEmpty()); assertEquals(1, validation.definitions());
            assertTrue(repository.snapshot().isEmpty()); assertEquals(DEFINITION, store.read("npcs/town/guard.json"));
        }
        assertEquals(before, tree()); assertFalse(Files.exists(root.resolve("state")));
    }

    @Test void missingRootIsNotCreatedByValidation() {
        Path absent = root.resolve("not-created");
        assertThrows(IOException.class, () -> DocumentStore.readOnly(absent));
        assertFalse(Files.exists(absent));
    }

    @Test void pendingRecoveryIsRejectedWithoutChangingSourcesOrJournal() throws Exception {
        try (DocumentStore ignored = new DocumentStore(root)) { }
        Files.writeString(root.resolve("state/pending.json"), PENDING);
        Map<String,String> before = tree();
        IOException failure = assertThrows(IOException.class, () -> DocumentStore.readOnly(root));
        assertTrue(failure.getMessage().contains("Pending transaction requires recovery"));
        assertEquals(before, tree()); assertFalse(Files.exists(root.resolve("npcs")));
    }

    @Test void mutationEntryPointsRefuseWithoutCreatingState() throws Exception {
        Map<String,String> before = tree();
        try (DocumentStore store = DocumentStore.readOnly(root)) {
            assertThrows(IOException.class, () -> store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", DEFINITION))));
            assertThrows(IOException.class, () -> store.putImmutableJson("npc-definitions", DEFINITION));
            assertThrows(IOException.class, () -> store.rollback("12345678-1234-1234-1234-123456789abc"));
        }
        assertEquals(before, tree());
    }

    @Test void anExistingWriterPreventsAReadOnlyViewWithoutBreakingTheWriter() throws Exception {
        try (DocumentStore writer = new DocumentStore(root)) {
            assertThrows(IOException.class, () -> DocumentStore.readOnly(root));
            writer.commit(List.of(new DocumentStore.Change("skins/probe.json", "missing", "{}")));
            assertEquals("{}", writer.read("skins/probe.json"));
        }
        Map<String,String> before = tree();
        try (DocumentStore reader = DocumentStore.readOnly(root)) {
            assertEquals("{}", reader.read("skins/probe.json"));
            assertThrows(IOException.class, () -> new DocumentStore(root));
        }
        assertEquals(before, tree());
        try (DocumentStore writer = new DocumentStore(root)) { assertEquals("{}", writer.read("skins/probe.json")); }
    }

    @Test void aPendingJournalAppearingDuringAnUnlockedExportScanIsDetected() throws Exception {
        try (DocumentStore reader = DocumentStore.readOnly(root)) {
            Files.createDirectories(root.resolve("state"));
            Files.writeString(root.resolve("state/pending.json"), PENDING);
            Map<String,String> externallyWritten = tree();
            assertThrows(IOException.class, reader::verifyReadable);
            assertThrows(IOException.class, () -> new NpcRepository(reader).validateStored());
            assertEquals(externallyWritten, tree());
        }
        assertFalse(Files.exists(root.resolve("state/store.lock")));
    }

    private Map<String,String> tree() throws IOException {
        Map<String,String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) files.put(root.relativize(path).toString(),
                    Files.isDirectory(path) ? "directory" : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return files;
    }
}
