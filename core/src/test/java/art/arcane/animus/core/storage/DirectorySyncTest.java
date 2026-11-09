package art.arcane.animus.core.storage;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows refuses to open a directory for reading, so a directory fsync fails there after the rename has already
 * happened. A folder without read permission fails the same way on POSIX, which stands in for it here.
 */
class DirectorySyncTest {
    @TempDir Path root;

    @Test void commitsSucceedAndRecoveryStaysCleanWhenDirectorySyncIsRefused() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        new DocumentStore(root).close();
        Path state = root.resolve("state");
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("-wx------"));
        try {
            try (DocumentStore store = new DocumentStore(root)) {
                String first = store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", "{\"n\":1}")));
                assertFalse(Files.exists(state.resolve("pending.json")), "a refused directory sync must not leave the journal behind");
                store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", JsonDocuments.hash("{\"n\":1}"), "{\"n\":2}")));
                assertEquals("{\"n\":2}", store.read("npcs/test/a.json"));
                assertNotNull(store.read("backups/" + first + ".json"));
            }
            try (DocumentStore reopened = new DocumentStore(root)) {
                assertFalse(Files.exists(state.resolve("pending.json")));
                assertEquals("{\"n\":2}", reopened.read("npcs/test/a.json"));
            }
        } finally { Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------")); }
    }
}
