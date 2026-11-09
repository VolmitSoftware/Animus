package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Import application must use the same bounded tree admission as ordinary repository loading. */
class ImportTreeBoundaryTest {
    @TempDir Path root;

    private CitizensImporter.Preview preview(DocumentStore store) throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), """
                npc:
                  '1':
                    name: Example
                    traits:
                      type: PLAYER
                      location: {world: world, x: 0, y: 64, z: 0}
                """);
        return new CitizensImporter().preview(source, store, Map.of());
    }

    private void assertUnapplied(DocumentStore store, CitizensImporter.Preview preview) throws Exception {
        assertNull(store.read("imports/" + preview.id() + "/receipt.json"));
        assertNull(store.read("npcs/world/citizens-1.json"));
        assertFalse(Files.exists(store.resolve("state/npc-definitions/last-valid/npcs/world/citizens-1.json")));
    }

    @Test void rejectsExcessiveDepthEvenWhenItContainsNoNpcFiles() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("data"))) {
            var preview = preview(store);
            Files.createDirectories(store.resolve("npcs/a/b/c/d/e/f/g/h/i/j"));
            IOException error = assertThrows(IOException.class, () -> new CitizensImporter().apply(store, preview.id()));
            assertTrue(error.getMessage().contains("nested deeper than"), error.getMessage());
            assertUnapplied(store, preview);
            Files.delete(store.resolve("npcs/a/b/c/d/e/f/g/h/i/j"));
            assertNotEquals("already-applied", new CitizensImporter().apply(store, preview.id()));
        }
    }

    @Test void rejectsNonJsonDirectoryAndDanglingLinksBeforeInstallingAnyDocument() throws Exception {
        Path external = Files.createDirectory(root.resolve("external"));
        Files.writeString(external.resolve("untouched.txt"), "original");
        try (DocumentStore store = new DocumentStore(root.resolve("data"))) {
            var preview = preview(store);
            Path npcs = Files.createDirectory(store.resolve("npcs"));
            Path link = npcs.resolve("not-a-json-file");
            for (Path target : new Path[]{external, root.resolve("missing")}) {
                Files.createSymbolicLink(link, target);
                IOException error = assertThrows(IOException.class, () -> new CitizensImporter().apply(store, preview.id()));
                assertTrue(error.getMessage().contains("symbolic links"), error.getMessage());
                assertUnapplied(store, preview);
                Files.delete(link);
            }
            assertEquals("original", Files.readString(external.resolve("untouched.txt")));
            new CitizensImporter().apply(store, preview.id());
        }
    }

    @Test void directoryNamedJsonIsNotParsedAsADefinition() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("data"))) {
            var preview = preview(store);
            Files.createDirectories(store.resolve("npcs/notes.json"));
            new CitizensImporter().apply(store, preview.id());
            assertNotNull(store.read("npcs/world/citizens-1.json"));
            assertTrue(Files.isDirectory(store.resolve("npcs/notes.json")));
        }
    }
}
