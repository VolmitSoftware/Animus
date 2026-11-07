package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The words replies use: spawned and despawned are the saved intent, active means loaded in a world now, dormant is a
 * saved NPC that is not spawned. Problem replies name the next step, and long replies say what they left out.
 */
class CommandVocabularyTest {
    @TempDir Path root;

    /** Sentences a player can read that use a word the vocabulary replaced, or an old command name. */
    private static final Pattern BANNED = Pattern.compile("activat|inactive|it starts when|apply-plan|foliaproof|—", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final List<String> SOURCES = List.of("core/src/main/java/art/arcane/animus/core/admin/Administration.java",
            "core/src/main/java/art/arcane/animus/core/runtime/PersistedNpcService.java", "core/src/main/java/art/arcane/animus/core/runtime/ActivationWarnings.java",
            "core/src/main/java/art/arcane/animus/core/editor/EditorApi.java", "core/src/main/java/art/arcane/animus/core/editor/RuntimeEditorHost.java",
            "bukkit/src/main/java/art/arcane/animus/bukkit/runtime/RegionNpcRuntime.java");

    @Test void replyStringsKeepTheVocabulary() throws IOException {
        Path repository = repository();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> commands = Files.list(repository.resolve("core/src/main/java/art/arcane/animus/core/command"))) { commands.forEach(files::add); }
        for (String source : SOURCES) files.add(repository.resolve(source));
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).strip();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/**")) continue;
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    String text = literal.group(1);
                    if (text.contains(" ") && BANNED.matcher(text).find()) found.add(file.getFileName() + ":" + (index + 1) + " " + text);
                }
            }
        }
        assertTrue(found.isEmpty(), String.join("\n", found));
    }

    @Test void anNpcThatCannotTakeLiveWorkSaysWhyAndWhatToDo() throws IOException {
        NpcDefinition despawned = definition(false), spawned = definition(true);
        assertEquals("Unknown NPC: ghost; /animus list shows ids", CommandGroup.inactive("ghost", Optional.empty()));
        assertEquals("guard is despawned; /animus spawn guard first", CommandGroup.inactive("guard", Optional.of(despawned)));
        assertEquals("guard is spawned but not active; it becomes active when its chunk loads or a player comes near", CommandGroup.inactive("guard", Optional.of(spawned)));
    }

    @Test void validateAndReloadCountSavedAndSpawnedNpcs() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root))) {
            runtime.start();
            Administration admin = new Administration(runtime);
            String created = admin.create("guard", "minecraft:player", "world", 0, 64, 0, "", "Guard");
            assertTrue(created.startsWith("Created dormant guard; tx "), created);
            admin.create("smith", "minecraft:player", "world", 2, 64, 0, "town", "Smith");
            admin.spawned("smith", true);
            assertEquals(List.of("All definitions valid; 2 saved, 1 spawned"), admin.validate());
            assertEquals(List.of("Reloaded; 2 saved, 1 spawned"), admin.reload());
            assertTrue(admin.spawned("guard", false).endsWith("; /animus spawn guard brings it back"));
        }
    }

    @Test void failuresWithoutAMessageNeverReadNull() {
        assertEquals("IllegalStateException", CommandGroup.reason(new IllegalStateException()));
        assertEquals("IOException", CommandGroup.reason(new IOException(" ")));
        assertEquals("world missing", CommandGroup.reason(new IllegalArgumentException("world missing")));
    }

    @Test void aLongReplyShowsFiveLinesOnAPlayersScreenAndSaysHowManyThereWere() {
        RecordingActor player = new RecordingActor(true);
        List<String> report = new ArrayList<>();
        for (int index = 1; index <= 9; index++) report.add("line " + index);
        CommandGroup.lines(player, report);
        assertEquals(List.of("line 1", "line 2", "line 3", "line 4", "line 5"), player.told);
        assertEquals(1, player.rich.size());
        assertTrue(player.rich.getFirst().visible().endsWith("Showing 5 of 9 lines [Details]"), player.rich.getFirst().visible());
        RecordingActor console = new RecordingActor(false);
        CommandGroup.lines(console, report);
        assertEquals(report, console.told, "the console reads every line");
    }

    @Test void attributeChangesCarryTheUndoChip() {
        ChatLine line = ChatLine.parse("guard: scale = 1.5; tx " + UUID.randomUUID());
        assertTrue(line.visible().contains("[Undo]"), line.visible());
    }

    @Test void createDefaultsToWhereThePlayerStands() {
        Optional<WorldPosition> standing = Optional.of(new WorldPosition("town", new Vec3(10.257, 64, -3.5)));
        AnimusCommandTree.Placement here = AnimusCommandTree.placement(standing, "", "", "", "");
        assertEquals(new AnimusCommandTree.Placement("town", 10.26, 64, -3.5), here);
        assertEquals(new AnimusCommandTree.Placement("nether", 1, 64, -3.5), AnimusCommandTree.placement(standing, "nether", "1", "", ""));
        IllegalArgumentException console = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AnimusCommandTree.placement(Optional.empty(), "world", "0", "", "0"));
        assertTrue(console.getMessage().startsWith("The console gives world=, x=, y= and z="), console.getMessage());
        assertEquals(new AnimusCommandTree.Placement("world", 0, 64, 0), AnimusCommandTree.placement(Optional.empty(), "world", "0", "64", "0"));
        IllegalArgumentException word = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AnimusCommandTree.placement(standing, "", "east", "", ""));
        assertEquals("x must be a number, such as x=12.5", word.getMessage());
    }

    @Test void createTakesOnlyTheIdAndOffersKeyedDefaults() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN);
        List<String> keys = director.complete(admin, new String[]{"create", "guard", ""});
        assertEquals(List.of("type=", "name=", "folder=", "world=", "x=", "y=", "z="), keys);
        assertFalse(director.complete(admin, new String[]{""}).contains("navigate"), "navigate moved into navigation");
    }

    private static NpcDefinition definition(boolean spawned) throws IOException {
        return NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"guard\",\"type\":\"minecraft:player\",\"group\":\"\",\"name\":\"Guard\","
                + "\"spawned\":" + spawned + ",\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}");
    }

    private static Path repository() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("Repository root not found");
        return current;
    }

    /** Keeps plain lines and chip lines apart. */
    private static final class RecordingActor implements CommandActor {
        final List<String> told = new ArrayList<>();
        final List<ChatLine> rich = new ArrayList<>();
        final Optional<UUID> player;
        RecordingActor(boolean player) { this.player = player ? Optional.of(UUID.randomUUID()) : Optional.empty(); }
        @Override public boolean permitted(String node) { return true; }
        @Override public void tell(String message) { told.add(message); }
        @Override public Optional<UUID> player() { return player; }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
        @Override public void rich(ChatLine line) { rich.add(line); }
    }
}
