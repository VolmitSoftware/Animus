package art.arcane.animus.core.recording;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Reads {@code recordings/<id>.json} with the byte count bounded before any JSON is built. Call from the runtime's admitted I/O queue. */
public final class RecordingDocuments {
    private RecordingDocuments() {}

    /**
     * @param reference the recording id, as a navigation module or action names it
     * @param maximumBytes {@code [recordings] maximumFileBytes}; a larger file is refused without reading past the bound
     * @throws IllegalArgumentException when {@code reference} is not a recording id
     * @throws IOException when the file is missing, a link, too large, not UTF-8, invalid, or names another id
     */
    public static RecordingDocument read(DocumentStore documents, String reference, int maximumBytes) throws IOException {
        if (reference == null || !reference.matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        Path path = documents.resolve("recordings/" + reference + ".json");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maximumBytes + 1); }
        if (bytes.length > maximumBytes) throw new IOException("Recording " + reference + " exceeds " + maximumBytes + " bytes");
        RecordingDocument document = RecordingDocument.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), maximumBytes);
        if (!document.id().equals(reference)) throw new IOException("$.id: the recording id is " + document.id() + " but the file is " + reference);
        return document;
    }
}
