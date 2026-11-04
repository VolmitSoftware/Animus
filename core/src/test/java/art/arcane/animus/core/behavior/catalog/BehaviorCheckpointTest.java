package art.arcane.animus.core.behavior.catalog;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BehaviorCheckpointTest {
    private static final String ID = "example:quests/greet";
    private static final String POINTER = "state/behavior-catalog/last-valid/behaviors/example/quests/greet.json";

    @TempDir Path root;

    private static String source(long revision, int ticks) {
        return "{\"schemaVersion\":1,\"revision\":" + revision + ",\"id\":\"" + ID + "\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":" + ticks + "}}}";
    }

    private Path file() throws Exception {
        Path file = root.resolve(BehaviorPath.forId(ID));
        Files.createDirectories(file.getParent());
        return file;
    }

    private BehaviorCatalog catalog(DocumentStore documents) throws Exception {
        return new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), requirement -> BehaviorCatalog.Capability.present(), new BehaviorCheckpoints(documents));
    }

    private static BehaviorCatalog.Reload reload(BehaviorCatalog catalog) {
        return catalog.reload(catalog.snapshot().generation(), Set.of());
    }

    private static BehaviorCatalog.Entry entry(BehaviorCatalog catalog) {
        return catalog.snapshot().entries().get(BehaviorPath.forId(ID));
    }

    private void accept(long revision) throws Exception {
        Files.writeString(file(), source(revision, 5));
        try (DocumentStore documents = new DocumentStore(root)) {
            assertTrue(reload(catalog(documents)).published());
        }
    }

    @Test
    void acceptedProgramIsCheckpointedWithoutReceiptsOrSourceChanges() throws Exception {
        accept(3);
        assertTrue(Files.exists(root.resolve(POINTER)));
        assertEquals(source(3, 5), Files.readString(file()));
        assertFalse(Files.exists(root.resolve("backups")));
        try (Stream<Path> objects = Files.list(root.resolve("state/objects/behavior-catalog"))) {
            assertEquals(1, objects.count());
        }
    }

    @Test
    void malformedSourceAfterRestartKeepsTheLastAcceptedProgramAndReportsExactErrors() throws Exception {
        accept(3);
        Files.writeString(file(), "{\"schemaVersion\":1,");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            assertTrue(reload(catalog).published());
            BehaviorCatalog.Entry entry = entry(catalog);
            assertEquals(BehaviorCatalog.SourceState.INVALID, entry.state());
            assertEquals(3, entry.accepted().orElseThrow().revision());
            assertEquals("behavior-json-invalid", entry.diagnostics().getFirst().code());
            assertEquals("$", entry.diagnostics().getFirst().field());
            assertTrue(catalog.snapshot().find(ID).orElseThrow().available(), "Last accepted program remains usable");
        }
        assertEquals("{\"schemaVersion\":1,", Files.readString(file()), "Invalid bytes stay in place");
    }

    @Test
    void semanticallyInvalidSourceAfterRestartReportsTheExactField() throws Exception {
        accept(3);
        Files.writeString(file(), source(4, 0));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertEquals(3, entry(catalog).accepted().orElseThrow().revision());
            Diagnostic diagnostic = entry(catalog).diagnostics().getFirst();
            assertEquals("$.routines.main.ticks", diagnostic.field());
        }
    }

    @Test
    void revisionRegressionIsRejectedAcrossRestartAndRepairAdvancesTheCheckpoint() throws Exception {
        accept(3);
        Files.writeString(file(), source(2, 9));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertEquals(BehaviorCatalog.SourceState.CONFLICT, entry(catalog).state());
            assertEquals(3, entry(catalog).accepted().orElseThrow().revision());
            Files.writeString(file(), source(4, 9));
            reload(catalog);
            assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
        }
        Files.writeString(file(), "broken");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertEquals(4, entry(catalog).accepted().orElseThrow().revision());
        }
    }

    @Test
    void unchangedSourceAfterRestartDoesNotRewriteTheCheckpoint() throws Exception {
        accept(3);
        FileTime stamp = FileTime.fromMillis(Files.getLastModifiedTime(root.resolve(POINTER)).toMillis() - 60000);
        Files.setLastModifiedTime(root.resolve(POINTER), stamp);
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
        }
        assertEquals(stamp, Files.getLastModifiedTime(root.resolve(POINTER)));
    }

    @Test
    void sourceDeletedWhileStoppedDoesNotResurrect() throws Exception {
        accept(3);
        Files.delete(file());
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            assertTrue(reload(catalog).published());
            assertNull(entry(catalog));
            assertTrue(catalog.snapshot().find(ID).isEmpty());
        }
        assertFalse(Files.exists(root.resolve(POINTER)), "Deletion also clears the recovery pointer");
        Files.writeString(file(), "broken");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertTrue(entry(catalog).accepted().isEmpty(), "A later malformed file is new, not a recovery");
            assertEquals(BehaviorCatalog.SourceState.INVALID, entry(catalog).state());
        }
    }

    @Test
    void sourceDeletedWhileRunningIsUnavailableAndDoesNotResurrectAfterRestart() throws Exception {
        Files.writeString(file(), source(3, 5));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            Files.delete(file());
            reload(catalog);
            assertEquals(BehaviorCatalog.SourceState.MISSING, entry(catalog).state());
            assertFalse(catalog.snapshot().find(ID).orElseThrow().available());
            assertFalse(Files.exists(root.resolve(POINTER)));
        }
        Files.writeString(file(), "broken");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertTrue(entry(catalog).accepted().isEmpty());
        }
    }

    @Test
    void corruptCheckpointIsReportedAndAValidSourceReplacesIt() throws Exception {
        accept(3);
        Files.writeString(root.resolve(POINTER), "{\"schemaVersion\":1,");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            assertTrue(reload(catalog).published());
            assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
            assertTrue(entry(catalog).diagnostics().stream().anyMatch(item -> item.code().equals("behavior-checkpoint-invalid") && item.severity() == Diagnostic.Severity.WARNING));
            assertTrue(catalog.snapshot().find(ID).orElseThrow().available());
        }
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertTrue(entry(catalog).diagnostics().isEmpty(), "The rewritten checkpoint is valid again");
        }
    }

    @Test
    void corruptCheckpointWithMalformedSourceReportsBothAndAcceptsNothing() throws Exception {
        accept(3);
        Files.writeString(root.resolve(POINTER), "{\"schemaVersion\":1,");
        Files.writeString(file(), "broken");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            assertTrue(reload(catalog).published());
            assertTrue(entry(catalog).accepted().isEmpty());
            List<String> codes = entry(catalog).diagnostics().stream().map(Diagnostic::code).toList();
            assertTrue(codes.contains("behavior-json-invalid"), codes.toString());
            assertTrue(codes.contains("behavior-checkpoint-invalid"), codes.toString());
        }
    }

    @Test
    void tamperedCheckpointObjectIsNotTrusted() throws Exception {
        accept(3);
        try (Stream<Path> objects = Files.list(root.resolve("state/objects/behavior-catalog"))) {
            Files.writeString(objects.findFirst().orElseThrow(), source(9, 9));
        }
        Files.writeString(file(), "broken");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            reload(catalog);
            assertTrue(entry(catalog).accepted().isEmpty());
        }
    }

    @Test
    void validationNeverWritesCheckpoints() throws Exception {
        Files.writeString(file(), source(3, 5));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            assertTrue(catalog.validate(catalog.snapshot().generation()).valid());
            assertFalse(Files.exists(root.resolve(POINTER)));
        }
    }

    @Test
    void checkpointWriteFailureRejectsThePublicationAndKeepsTheSnapshot() throws Exception {
        Files.writeString(file(), source(3, 5));
        Path blocker = root.resolve("state/behavior-catalog");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorCatalog catalog = catalog(documents);
            Files.createDirectories(blocker.getParent());
            Files.writeString(blocker, "not a directory");
            BehaviorCatalog.Reload result = reload(catalog);
            assertFalse(result.published());
            assertEquals("catalog-checkpoint-failed", result.diagnostics().getFirst().code());
            assertEquals(0, catalog.snapshot().generation());
        }
    }
}
