package art.arcane.animus.core.runtime;

import art.arcane.animus.core.storage.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SkinDocumentsTest {
    @TempDir Path directory;
    @Test void verifiesExactContentAddressAndPreservesTextureAndSignature() throws Exception {
        try (var documents = new DocumentStore(directory)) {
            String body = "{\"textureRaw\":\"dGV4dHVyZQ==\",\"signature\":\"c2lnbmF0dXJl\"}\n";
            String hash = store(body);
            var skin = SkinDocuments.read(documents, hash);
            assertEquals("dGV4dHVyZQ==", skin.textureValue()); assertEquals("c2lnbmF0dXJl", skin.signature());
            Files.writeString(directory.resolve("skins/" + hash + ".json"), body + " ");
            assertThrows(IOException.class, () -> SkinDocuments.read(documents, hash));
        }
    }
    @Test void missingMalformedAndUnsafeReferencesFailBeforeActivation() throws Exception {
        try (var documents = new DocumentStore(directory)) {
            assertThrows(IOException.class, () -> SkinDocuments.read(documents, "0".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> SkinDocuments.read(documents, "../../private"));
            String wrongType = store("{\"textureRaw\":true}");
            assertThrows(IOException.class, () -> SkinDocuments.read(documents, wrongType));
            String badSignature = store("{\"textureRaw\":\"dGV4dHVyZQ==\",\"signature\":7}");
            assertThrows(IOException.class, () -> SkinDocuments.read(documents, badSignature));
            String badBase64 = store("{\"textureRaw\":\"not base64?\"}");
            assertThrows(IllegalArgumentException.class, () -> SkinDocuments.read(documents, badBase64));
        }
    }
    private String store(String body) throws IOException {
        String hash = JsonDocuments.hash(body); Files.createDirectories(directory.resolve("skins"));
        Files.writeString(directory.resolve("skins/" + hash + ".json"), body); return hash;
    }
}
