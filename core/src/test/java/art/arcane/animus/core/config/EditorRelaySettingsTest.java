package art.arcane.animus.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorRelaySettingsTest {
    @TempDir Path root;

    @Test
    void shouldWriteAndReadTheRelayDefaults() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(EditorRelaySettings.DEFAULT, PluginSettings.load(store).relay());
            String toml = Files.readString(root.resolve("config.toml"));
            assertTrue(toml.contains("[editor]"), toml);
            assertTrue(toml.contains("relayUrl = \"wss://animus-relay-823521865655.us-central1.run.app\""), toml);
            assertTrue(toml.contains("relayEnabled = true"), toml);
        }
    }

    @Test
    void shouldReadRelayKeysAndIgnoreTheOtherEditorKeys() throws IOException {
        Files.writeString(root.resolve("config.toml"), """
                [editor]
                listenerEnabled = false
                relayEnabled = false
                relayUrl = "ws://127.0.0.1:8080/"
                relayReconnectMinSeconds = 2
                relayReconnectMaxSeconds = 30
                relayFrameBytes = 65536
                relayConcurrentRequests = 8
                """);
        try (DocumentStore store = new DocumentStore(root)) {
            EditorRelaySettings relay = PluginSettings.load(store).relay();
            assertFalse(relay.enabled());
            assertEquals(new EditorRelaySettings(false, "ws://127.0.0.1:8080/", 2, 30, 65536, 8), relay);
            assertEquals(URI.create("ws://127.0.0.1:8080/agent"), relay.agentUri());
        }
    }

    @Test
    void shouldNameTheEditorTableWhenAValueIsOutOfRange() throws IOException {
        Files.writeString(root.resolve("config.toml"), "[editor]\nrelayReconnectMinSeconds = 10\nrelayReconnectMaxSeconds = 5\n");
        try (DocumentStore store = new DocumentStore(root)) {
            IOException failure = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(failure.getMessage().contains("editor: relayReconnectMaxSeconds"), failure.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ws://relay.example.com", "https://relay.example.com", "wss://user@relay.example.com", "wss://relay.example.com/?a=1", "wss://", "relay"})
    void shouldRefuseRelayUrlsThatAreNotWss(String url) {
        assertThrows(IllegalArgumentException.class, () -> new EditorRelaySettings(true, url, 1, 60, 1024 * 1024, 64));
    }

    @Test
    void shouldClampTheFrameLimitToTheRelays() throws IOException {
        assertEquals(EditorRelaySettings.RELAY_FRAME_BYTES, new EditorRelaySettings(true, "wss://relay.example.com", 1, 60, 4 * 1024 * 1024, 64).frameBytes());
        Files.writeString(root.resolve("config.toml"), "[editor]\nrelayFrameBytes = 16777216\n");
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(1024 * 1024, PluginSettings.load(store).relay().frameBytes(), "a larger frame would make the relay drop the whole connection");
        }
    }

    @Test
    void shouldAppendTheAgentPath() {
        assertEquals(URI.create("wss://relay.example.com/base/agent"), new EditorRelaySettings(true, "wss://relay.example.com/base", 1, 60, 65536, 1).agentUri());
    }
}
