package art.arcane.animus.core.config;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinActionSettingsTest {
    @TempDir Path root;

    private BuiltinActionSettings load(String toml) throws Exception {
        Files.writeString(root.resolve("config.toml"), toml);
        try (DocumentStore store = new DocumentStore(root)) { return PluginSettings.load(store).behaviors().actions(); }
    }

    @Test void defaultsDenyCommandsAndGenerateARoundTrippingFile() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            PluginSettings first = PluginSettings.load(store);
            assertEquals(BuiltinActionSettings.DEFAULT, first.behaviors().actions());
            assertTrue(first.behaviors().actions().commandBehaviors().isEmpty());
            assertTrue(first.behaviors().actions().commandNamespaces().isEmpty());
            assertFalse(first.behaviors().actions().commandAllowed("anything:at-all"));
            assertTrue(first.behaviors().actions().rawTextBehaviors().isEmpty());
            assertTrue(Files.readString(root.resolve("config.toml")).contains("[behaviors.actions]"));
            assertEquals(first, PluginSettings.load(store));
        }
    }

    @Test void olderFilesWithoutTheTableUseDefaults() throws Exception {
        assertEquals(BuiltinActionSettings.DEFAULT, load("[behaviors]\nenabled = true\n"));
    }

    @Test void allowListsAndBudgetsParse() throws Exception {
        BuiltinActionSettings settings = load("""
                [behaviors.actions]
                actionsPerTick = 8
                actionsPerNpcPerTick = 2
                queuedActions = 16
                pendingChoices = 32
                choiceLifetimeSeconds = 120
                commandBehaviors = ["town:gate/open"]
                commandNamespaces = ["quests"]
                navigationWaits = 6
                rawTextBehaviors = ["town:shout"]
                """);
        assertEquals(new BuiltinActionSettings(8, 2, 16, 32, 120, List.of("town:gate/open"), List.of("quests"), 6, List.of("town:shout")), settings);
        assertTrue(settings.rawTextAllowed("town:shout"));
        assertFalse(settings.rawTextAllowed("town:gate/open"), "raw text is granted per behavior, never by the command grant");
        assertTrue(settings.commandAllowed("town:gate/open"));
        assertFalse(settings.commandAllowed("town:gate/close"));
        assertTrue(settings.commandAllowed("quests:anything/nested"));
        assertFalse(settings.commandAllowed("questsx:anything"));
        assertFalse(settings.commandAllowed("malformed"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "actionsPerTick = 0", "actionsPerTick = 257", "actionsPerNpcPerTick = 0", "actionsPerNpcPerTick = 65",
            "queuedActions = 0", "queuedActions = 1025", "pendingChoices = 0", "pendingChoices = 4097",
            "choiceLifetimeSeconds = 0", "choiceLifetimeSeconds = 601", "navigationWaits = 0", "navigationWaits = 65",
            "commandBehaviors = [\"*\"]", "commandBehaviors = [\"NotAnId\"]", "commandBehaviors = [1]", "commandBehaviors = \"town:gate\"",
            "commandNamespaces = [\"*\"]", "commandNamespaces = [\"Town\"]", "commandNamespaces = [\"town:gate\"]",
            "rawTextBehaviors = [\"town\"]", "rawTextBehaviors = [\"*\"]"})
    void invalidSettingsFailLoadWithoutChangingTheFile(String line) throws Exception {
        String source = "[behaviors.actions]\n" + line + "\n";
        Files.writeString(root.resolve("config.toml"), source);
        try (DocumentStore store = new DocumentStore(root)) { assertThrows(IOException.class, () -> PluginSettings.load(store)); }
        assertEquals(source, Files.readString(root.resolve("config.toml")));
    }

    @Test void perNpcBudgetCannotExceedTheGlobalBudget() {
        assertThrows(IllegalArgumentException.class, () -> new BuiltinActionSettings(2, 3, 8, 8, 60, List.of(), List.of(), 8, List.of()));
    }

    @Test void allowListSizeIsBounded() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 257).mapToObj(index -> "town:gate" + index).toList();
        assertThrows(IllegalArgumentException.class, () -> new BuiltinActionSettings(32, 4, 256, 256, 60, tooMany, List.of(), 8, List.of()));
    }
}
