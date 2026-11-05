package art.arcane.animus.core.runtime;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.navigation.WaypointSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bound the byte read before JSON allocation; invoked only through the runtime's admitted I/O queue. */
public final class RouteDocuments {
    private RouteDocuments() {}
    public static WaypointSettings read(DocumentStore documents, String reference) throws IOException {
        if (reference == null || !reference.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid reusable route identifier");
        Path path = documents.resolve("behaviors/routes/" + reference + ".json");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(64 * 1024 + 1); }
        if (bytes.length > 64 * 1024) throw new IOException("Reusable route exceeds64 KiB");
        return WaypointSettings.document(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }
}
