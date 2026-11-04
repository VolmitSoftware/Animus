package art.arcane.animus.core.config;

import art.arcane.animus.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginSettingsTest {
    @TempDir Path root;
    @Test void createsTomlOnlyAndPreservesCommentsOnReload() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals("Hello from Animus", PluginSettings.load(store).statusMessage());
            assertTrue(Files.exists(root.resolve("config.toml")));
            assertFalse(Files.exists(root.resolve("config.json")));
            String source = "# Admin comment\nstatusMessage = 'Custom message'\n[future]\nkeep = true\n";
            Files.writeString(root.resolve("config.toml"), source);
            assertEquals("Custom message", PluginSettings.load(store).statusMessage());
            assertEquals(source, Files.readString(root.resolve("config.toml")));
        }
    }
    @Test void migratesLegacyJsonLosslesslyWithoutRemovingTheOriginal() throws Exception {
        String original = "{\"statusMessage\":\"He said \\\"hello\\\"\\nAgain\",\"unknown\":{\"preserved\":true}}";
        Files.writeString(root.resolve("config.json"), original);
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals("He said \"hello\"\nAgain", PluginSettings.load(store).statusMessage());
            assertEquals(original, store.read("config.json"));
            assertEquals(original, store.read("backups/config-" + JsonDocuments.hash(original) + ".json"));
            assertTrue(store.read("config.toml").contains("preserved"));
            Files.writeString(root.resolve("config.json"), "invalid legacy now");
            assertEquals("He said \"hello\"\nAgain", PluginSettings.load(store).statusMessage());
        }
    }
    @Test void refusesLossyLegacyNullsAndRetainsTheSource() throws Exception {
        String original = "{\"statusMessage\":\"Valid\",\"future\":null}";
        Files.writeString(root.resolve("config.json"), original);
        try (DocumentStore store = new DocumentStore(root)) {
            assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertFalse(Files.exists(root.resolve("config.toml")));
            assertEquals(original, store.read("config.json"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "statusMessage = 123", "statusMessage = true", "statusMessage = []", "statusMessage = ''", "statusMessage = '  '", "statusMessage = 'a'\nstatusMessage = 'b'", "statusMessage = \"broken", "{\"statusMessage\":\"json is not toml\"}"})
    void invalidTomlDoesNotFallBackOrOverwrite(String original) throws Exception {
        Files.writeString(root.resolve("config.toml"), original);
        Files.writeString(root.resolve("config.json"), "{\"statusMessage\":\"Valid legacy\"}");
        try (DocumentStore store = new DocumentStore(root)) {
            assertTrue(assertThrows(IOException.class, () -> PluginSettings.load(store)).getMessage().contains("config.toml"));
            assertEquals(original, store.read("config.toml"));
        }
    }
}
