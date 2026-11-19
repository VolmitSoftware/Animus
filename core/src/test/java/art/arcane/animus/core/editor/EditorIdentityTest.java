package art.arcane.animus.core.editor;

import art.arcane.volmlib.util.editor.EditorIdentity;
import art.arcane.volmlib.util.editor.PairingCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            EditorIdentity created = EditorLink.identity(store);
            Path file = root.resolve(EditorLink.IDENTITY_PATH);
            assertTrue(Files.isRegularFile(file));
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
            EditorIdentity loaded = EditorLink.identity(store);
            assertEquals(created.serverKey(), loaded.serverKey());
            assertEquals(created.fingerprint(), loaded.fingerprint());
            assertEquals(PairingCode.fingerprint(java.util.Base64.getUrlDecoder().decode(created.serverKey())), created.fingerprint());
        }
    }

    @Test
    void shouldRefuseAFileWhoseKeyDoesNotMatchItsSeed() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            EditorLink.identity(store);
            Path file = root.resolve(EditorLink.IDENTITY_PATH);
            String other = EditorIdentity.generate().serverKey();
            String text = Files.readString(file);
            String tampered = text.replaceAll("\"serverKey\": \"[^\"]+\"", "\"serverKey\": \"" + other + "\"");
            assertNotEquals(text, tampered);
            Files.writeString(file, tampered);
            assertTrue(assertThrows(IOException.class, () -> EditorLink.identity(store)).getMessage().contains("does not match"));
        }
    }

    @Test
    void aDamagedIdentityFileIsOneLineThatNamesTheFileAndTheFix() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            EditorLink.identity(store);
            Files.writeString(root.resolve(EditorLink.IDENTITY_PATH), "{\"v\": 1, \"seed\": ");
            String message = assertThrows(IOException.class, () -> EditorLink.identity(store)).getMessage();
            assertTrue(message.startsWith(EditorLink.IDENTITY_PATH + " is damaged ("), message);
            assertTrue(message.endsWith("delete it and restart to create a new editor identity, then pair the editor again"), message);
            assertFalse(message.contains("\n"), message);
        }
    }

    @Test
    void shouldGenerateDistinctKeys() {
        assertNotEquals(EditorIdentity.generate().serverKey(), EditorIdentity.generate().serverKey());
    }
}
