package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorIdentityTest {
    @TempDir Path root;

    @Test
    void shouldReproduceTheSharedRelayHandshakeGolden() throws IOException {
        JsonObject golden = SharedFixtures.object(SharedFixtures.fixture("editor/relay-handshake.json"));
        byte[] seed = new byte[32];
        int index = 0;
        for (JsonElement value : golden.getAsJsonArray("seedBytes")) seed[index++] = value.getAsByte();
        EditorIdentity identity = EditorIdentity.fromSeed(seed);
        assertEquals(golden.get("serverKey").getAsString(), identity.serverKey());
        assertEquals(golden.get("fingerprint").getAsString(), identity.fingerprint());
        assertEquals(golden.get("signature").getAsString(), identity.sign(golden.get("signedText").getAsString()));
        assertEquals(golden.get("signedText").getAsString(), "animus-relay/1 " + golden.get("nonce").getAsString());
    }

    @Test
    void shouldCreateTheIdentityOnceAndReloadIt() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            EditorIdentity created = EditorIdentity.loadOrCreate(store);
            Path file = root.resolve(EditorIdentity.RELATIVE_PATH);
            assertTrue(Files.isRegularFile(file));
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
            EditorIdentity loaded = EditorIdentity.loadOrCreate(store);
            assertEquals(created.serverKey(), loaded.serverKey());
            assertEquals(created.fingerprint(), loaded.fingerprint());
            assertEquals(PairingCode.fingerprint(java.util.Base64.getUrlDecoder().decode(created.serverKey())), created.fingerprint());
        }
    }

    @Test
    void shouldRefuseAFileWhoseKeyDoesNotMatchItsSeed() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            EditorIdentity.loadOrCreate(store);
            Path file = root.resolve(EditorIdentity.RELATIVE_PATH);
            String other = EditorIdentity.generate().serverKey();
            String text = Files.readString(file);
            String tampered = text.replaceAll("\"serverKey\": \"[^\"]+\"", "\"serverKey\": \"" + other + "\"");
            assertNotEquals(text, tampered);
            Files.writeString(file, tampered);
            assertTrue(assertThrows(IOException.class, () -> EditorIdentity.loadOrCreate(store)).getMessage().contains("does not match"));
        }
    }

    @Test
    void shouldGenerateDistinctKeys() {
        assertNotEquals(EditorIdentity.generate().serverKey(), EditorIdentity.generate().serverKey());
    }
}
