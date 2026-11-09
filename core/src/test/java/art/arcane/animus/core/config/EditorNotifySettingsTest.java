package art.arcane.animus.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorNotifySettingsTest {
    @TempDir Path root;

    @Test
    void shouldWriteAndReadTheNotifyDefaults() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(EditorNotifySettings.DEFAULT, PluginSettings.load(store).editor().notices());
            String toml = Files.readString(root.resolve("config.toml"));
            assertTrue(toml.contains("[editor.notify]"), toml);
            assertTrue(toml.contains("block.note_block.pling"), toml);
            assertEquals(EditorNotifySettings.DEFAULT, PluginSettings.load(store).editor().notices(), "the written defaults read back unchanged");
        }
    }

    @Test
    void shouldReadSwitchesAndPartialSoundsOverDefaults() throws IOException {
        Files.writeString(root.resolve("config.toml"), """
                [editor.notify]
                chat = false
                actionBar = true
                console = false
                [editor.notify.sounds.apply]
                sound = "minecraft:entity.experience_orb.pickup"
                pitch = 1.2
                [editor.notify.sounds.refused]
                sound = ""
                """);
        try (DocumentStore store = new DocumentStore(root)) {
            EditorNotifySettings notify = PluginSettings.load(store).editor().notices();
            assertTrue(notify.enabled());
            assertFalse(notify.chat());
            assertTrue(notify.actionBar());
            assertFalse(notify.console());
            EditorNotifySettings.Sound apply = notify.sound(EditorNotifySettings.Kind.APPLY);
            assertEquals("minecraft:entity.experience_orb.pickup", apply.id());
            assertEquals(1.2, apply.pitch(), 1e-9);
            assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.APPLY).volume(), apply.volume(), 1e-9);
            assertTrue(notify.sound(EditorNotifySettings.Kind.REFUSED).silent());
            assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.PAIR), notify.sound(EditorNotifySettings.Kind.PAIR));
        }
    }

    @Test
    void shouldRefuseUnknownKeysAndBadValuesNamingTheField() throws IOException {
        assertRefused("[editor.notify.sounds.apply]\nvolume = 9\n", "editor: notify: sounds.apply.volume must be within 0..2");
        assertRefused("[editor.notify.sounds.apply]\npitch = 0.1\n", "editor: notify: sounds.apply.pitch must be within 0.5..2");
        assertRefused("[editor.notify.sounds.apply]\nsound = \"Not A Sound\"\n", "editor: notify: sounds.apply.sound must be a sound id");
    }

    private void assertRefused(String table, String message) throws IOException {
        Files.writeString(root.resolve("config.toml"), table);
        try (DocumentStore store = new DocumentStore(root)) {
            IOException failure = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(failure.getMessage().contains(message), failure.getMessage());
        }
    }
}
