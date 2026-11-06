package art.arcane.animus.core.recording;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RecordingDocumentsTest {
    @TempDir Path directory;

    @Test void readsARecordingByItsId() throws IOException {
        try (DocumentStore store = new DocumentStore(directory)) {
            Path recordings = store.resolve("recordings");
            Files.createDirectories(recordings);
            Files.writeString(recordings.resolve("market-loop.json"), SharedFixtures.text(SharedFixtures.fixture("recordings/market-loop.json")));
            RecordingDocument document = RecordingDocuments.read(store, "market-loop", 1_048_576);
            assertEquals("market-loop", document.id());
            assertEquals(5, document.frames().size());
            Files.copy(recordings.resolve("market-loop.json"), recordings.resolve("other.json"));
            IOException mismatch = assertThrows(IOException.class, () -> RecordingDocuments.read(store, "other", 1_048_576));
            assertTrue(mismatch.getMessage().contains("$.id: the recording id is market-loop but the file is other"), mismatch.getMessage());
        }
    }

    @Test void missingOversizedMalformedAndLinkedFilesAreRefused() throws IOException {
        try (DocumentStore store = new DocumentStore(directory)) {
            Path recordings = store.resolve("recordings");
            Files.createDirectories(recordings);
            assertThrows(IOException.class, () -> RecordingDocuments.read(store, "missing", 65_536));
            Files.writeString(recordings.resolve("large.json"), " ".repeat(65_537));
            IOException large = assertThrows(IOException.class, () -> RecordingDocuments.read(store, "large", 65_536));
            assertTrue(large.getMessage().contains("exceeds 65536 bytes"), large.getMessage());
            Files.write(recordings.resolve("bad.json"), new byte[] {(byte) 0xc3, (byte) 0x28});
            assertThrows(IOException.class, () -> RecordingDocuments.read(store, "bad", 65_536));
            Path outside = directory.resolve("outside.json");
            Files.writeString(outside, SharedFixtures.text(SharedFixtures.fixture("recordings/market-loop.json")));
            Files.createSymbolicLink(recordings.resolve("linked.json"), outside);
            assertThrows(IOException.class, () -> RecordingDocuments.read(store, "linked", 65_536));
            assertThrows(IllegalArgumentException.class, () -> RecordingDocuments.read(store, "../outside", 65_536));
            assertThrows(IllegalArgumentException.class, () -> RecordingDocuments.read(store, "Upper", 65_536));
        }
    }
}
