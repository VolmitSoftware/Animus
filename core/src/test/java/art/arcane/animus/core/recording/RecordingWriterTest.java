package art.arcane.animus.core.recording;

import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.CatalogReloads;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecordingWriterTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    static RecordingCapture.Take take(String id, int ticks) {
        RecordingCapture capture = new RecordingCapture(id, RECORDER, 2, RecordingCaptureTest.at(100.5, 64, 0.5));
        for (int i = 0; i < ticks; i++) {
            if (!capture.advance()) continue;
            double t = capture.tick();
            capture.sample(RecordingCaptureTest.at(100.5 + Math.sin(t / 7) * 3, 64, 0.5 + t * 0.05), 72_000);
        }
        return capture.finish().orElseThrow();
    }

    @Test void shouldWriteTheSerializedDocumentAsATransactionAndReloadRecordings(@TempDir Path directory) throws IOException {
        List<CatalogChange> reloads = new ArrayList<>();
        CatalogReloads catalogs = (CatalogChange change, Consumer<String> problems) -> reloads.add(change);
        try (DocumentStore store = new DocumentStore(directory)) {
            RecordingWriter.Saved saved = assertInstanceOf(RecordingWriter.Saved.class, RecordingWriter.save(store, catalogs, take("demo", 100), 1_048_576));
            assertEquals(1, saved.revision());
            RecordingDocument stored = RecordingDocuments.read(store, "demo", 1_048_576);
            assertEquals(stored.serialize(), store.read("recordings/demo.json"), "the stored bytes are the serialized form");
            assertEquals(saved.bytes(), stored.serialize().getBytes(StandardCharsets.UTF_8).length);
            assertEquals(Set.of(DocumentKind.RECORDING), reloads.getFirst().kinds());
            RecordingWriter.Saved again = assertInstanceOf(RecordingWriter.Saved.class, RecordingWriter.save(store, catalogs, take("demo", 60), 1_048_576));
            assertEquals(2, again.revision());
            assertEquals(2, RecordingDocuments.read(store, "demo", 1_048_576).revision());
            store.rollback(again.transaction());
            assertEquals(1, RecordingDocuments.read(store, "demo", 1_048_576).revision(), "the save leaves a rollback receipt");
        }
    }

    @Test void shouldKeepTheStoredDetailsWhenARecordingIsTakenAgain(@TempDir Path directory) throws IOException {
        CatalogReloads catalogs = (CatalogChange change, Consumer<String> problems) -> { };
        try (DocumentStore store = new DocumentStore(directory)) {
            RecordingDocument first = take("demo", 100).document(1);
            RecordingDocument.Details details = new RecordingDocument.Details("Walk to the stall", List.of("market"), Optional.of(Playback.ONCE), 0.5);
            store.commit(List.of(new DocumentStore.Change("recordings/demo.json", "missing",
                    new RecordingDocument(1, "demo", first.recordedBy(), first.world(), first.origin(), first.sampleTicks(), first.durationTicks(), first.frames(),
                            first.events(), details).serialize())));
            assertInstanceOf(RecordingWriter.Saved.class, RecordingWriter.save(store, catalogs, take("demo", 60), 1_048_576));
            RecordingDocument again = RecordingDocuments.read(store, "demo", 1_048_576);
            assertEquals(2, again.revision());
            assertEquals(details, again.details());
        }
    }

    @Test void shouldRefuseASaveAboveTheFileLimitWithItsSizeAndWriteNothing(@TempDir Path directory) throws IOException {
        List<CatalogChange> reloads = new ArrayList<>();
        try (DocumentStore store = new DocumentStore(directory)) {
            RecordingWriter.Outcome outcome = RecordingWriter.save(store, (CatalogChange change, Consumer<String> problems) -> reloads.add(change), take("big", 6000), 65_536);
            RecordingWriter.TooLarge refused = assertInstanceOf(RecordingWriter.TooLarge.class, outcome);
            assertTrue(refused.bytes() > 65_536);
            assertEquals(65_536, refused.maximum());
            assertNull(store.read("recordings/big.json"));
            assertTrue(reloads.isEmpty());
        }
    }

    @Test void shouldAdmitOneSessionPerPlayerUpToTheLiveCap() {
        RecordingRoster<String> roster = new RecordingRoster<>("recording");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertTrue(roster.admit(a, "a", false, 4).orElseThrow().contains("disabled"));
        assertEquals(Optional.empty(), roster.admit(a, "a", true, 2));
        assertTrue(roster.admit(a, "a2", true, 2).orElseThrow().contains("already"));
        assertEquals(Optional.empty(), roster.admit(b, "b", true, 2));
        assertTrue(roster.admit(c, "c", true, 2).orElseThrow().contains("2 of 2"));
        assertTrue(roster.admit(c, "c", true, 1).isPresent(), "lowering the cap refuses new sessions");
        assertEquals(2, roster.size(), "and finishes the running ones");
        assertTrue(roster.remove(a, "a"));
        assertFalse(roster.remove(b, "other"), "only the session that holds the slot frees it");
        assertTrue(roster.admit(c, "c", true, 1).isPresent());
        assertTrue(roster.remove(b, "b"));
        assertEquals(Optional.empty(), roster.admit(c, "c", true, 1));
        assertEquals(Optional.of("c"), roster.get(c));
    }
}
