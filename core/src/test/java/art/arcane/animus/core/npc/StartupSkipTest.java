package art.arcane.animus.core.npc;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** One bad or duplicate NPC file at startup costs only that NPC, never the whole plugin. */
class StartupSkipTest {
    @TempDir Path root;
    private final List<String> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { if (record.getLevel().intValue() >= Level.WARNING.intValue()) synchronized (warnings) { warnings.add(record.getMessage()); } }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach void listen() { Logger.getLogger("Animus").addHandler(capture); }
    @AfterEach void stopListening() { Logger.getLogger("Animus").removeHandler(capture); }

    private AnimusRuntime runtime() { return new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root)); }

    private boolean warned(String... parts) {
        synchronized (warnings) { return warnings.stream().anyMatch((String line) -> List.of(parts).stream().allMatch(line::contains)); }
    }

    @Test void aTypoInOneFileSkipsOnlyThatNpcAndKeepsTheFile() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", NpcRepositoryTest.JSON),
                    new DocumentStore.Change("npcs/town/baker.json", "missing", NpcRepositoryTest.BAKER)));
        }
        Files.writeString(root.resolve("npcs/town/typo.json"), "{\"schemaVersion\":1,");
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            assertTrue(runtime.isRunning());
            assertTrue(runtime.npcs().find("guard").isPresent());
            assertTrue(runtime.npcs().find("baker").isPresent());
            assertEquals(2, runtime.npcs().all().size());
            assertTrue(runtime.npcs().diagnostics().stream().anyMatch((String line) -> line.startsWith("npcs/town/typo.json: ")), runtime.npcs().diagnostics().toString());
        }
        assertEquals("{\"schemaVersion\":1,", Files.readString(root.resolve("npcs/town/typo.json")));
        assertTrue(warned("Skipped NPC file npcs/town/typo.json"), warnings.toString());
    }

    @Test void aCopiedFileKeepsTheRecoveryBackedOriginalAndSkipsTheCopy() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.loadForStartup();
            repository.apply(new NpcRepository.Draft("npcs/town/guard.json", "missing", 0, JsonDocuments.object(NpcRepositoryTest.JSON)));
        }
        String original = Files.readString(root.resolve("npcs/town/guard.json"));
        String copy = original.replace("Guard", "Copied Guard");
        Files.createDirectories(root.resolve("npcs/backup"));
        Files.writeString(root.resolve("npcs/backup/guard.json"), copy);
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            NpcDefinition kept = runtime.npcs().find("guard").orElseThrow();
            assertEquals("npcs/town/guard.json", kept.path());
            assertEquals("Guard", kept.name());
            assertTrue(runtime.npcs().diagnostics().stream().anyMatch((String line) -> line.startsWith("npcs/backup/guard.json: $.id: duplicate guard")),
                    runtime.npcs().diagnostics().toString());
        }
        assertEquals(copy, Files.readString(root.resolve("npcs/backup/guard.json")));
        assertEquals(original, Files.readString(root.resolve("npcs/town/guard.json")));
        assertTrue(warned("npcs/backup/guard.json", "duplicate"), warnings.toString());
    }

    @Test void twoHandWrittenDuplicatesKeepTheFirstByPath() throws Exception {
        Files.createDirectories(root.resolve("npcs/b"));
        Files.createDirectories(root.resolve("npcs/a"));
        Files.writeString(root.resolve("npcs/b/guard.json"), NpcRepositoryTest.JSON.replace("Guard", "Second"));
        Files.writeString(root.resolve("npcs/a/guard.json"), NpcRepositoryTest.JSON.replace("Guard", "First"));
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            assertEquals("First", runtime.npcs().find("guard").orElseThrow().name());
            assertEquals(1, runtime.npcs().all().size());
            assertTrue(runtime.npcs().diagnostics().stream().anyMatch((String line) -> line.startsWith("npcs/b/guard.json: $.id: duplicate")), runtime.npcs().diagnostics().toString());
        }
    }
}
