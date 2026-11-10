package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibsDisguisesImportTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-4000-8000-000000000001
                name: Ghoul
                traits:
                  type: PLAYER
                  location: {world: world, x: 1, y: 64, z: 1}
              '2':
                uuid: 00000000-0000-4000-8000-000000000002
                name: Steed
                traits:
                  type: PLAYER
                  location: {world: world, x: 2, y: 64, z: 2}
              '3':
                uuid: 00000000-0000-4000-8000-000000000003
                name: Plain
                traits:
                  type: VILLAGER
                  location: {world: world, x: 3, y: 64, z: 3}
            """;

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private Path plugins() { return root.resolve("plugins"); }

    private void write(String relative, String text) throws Exception {
        Path file = plugins().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test void savedDisguisesAndScriptCommandsReachTheNpcsTheyNamed() throws Exception {
        write("Citizens/saves.yml", SAVES);
        write("LibsDisguises/SavedDisguises/00000000-0000-4000-8000-000000000001", "zombie setBaby true\nskeleton\n");
        // A Citizens player NPC's entity carries the version 2 form of the NPC's UUID.
        write("Denizen/scripts/stable.dsc", """
                stable_world:
                  type: world
                  events:
                    on server start:
                    - execute as_server "disguiseplayer 00000000-0000-2000-8000-000000000002 horse setColor BLACK"
                """);
        try (DocumentStore store = new DocumentStore(plugins().resolve("Animus"))) {
            NpcRepository npcs = new NpcRepository(store);
            npcs.loadForStartup();
            CitizensDropIn flow = new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(plugins()), () -> false, () -> false, () -> false));
            List<String> staged = flow.preview("reject", Optional.empty(), Map.of(), false);
            assertEquals("Next: /animus import citizens apply", staged.getLast(), String.join("\n", staged));
            flow.applyStaged();
            assertEquals("zombie setBaby true", disguise(npcs, "citizens-1"));
            assertEquals("horse setColor BLACK", disguise(npcs, "citizens-2"));
            assertFalse(npcs.find("citizens-3").orElseThrow().json().has("disguise"));
        }
    }

    private static String disguise(NpcRepository npcs, String id) {
        NpcDefinition definition = npcs.stored().stream().filter((NpcDefinition stored) -> stored.id().equals(id)).findFirst().orElseThrow();
        return definition.json().get("disguise").getAsString();
    }

    @Test void oldFormatFilesAndPlaceholdersAreReportedInsteadOfGuessed() throws Exception {
        write("LibsDisguises/SavedDisguises/00000000-0000-4000-8000-000000000001", "[{\"disguiseType\":\"ZOMBIE\"}]");
        write("Skript/scripts/npcs.sk", "on load:\n\texecute console command \"/duuid 00000000-0000-4000-8000-000000000003 player %player%\"\n");
        write("LibsDisguises/SavedDisguises/not-a-uuid", "zombie");
        try (DocumentStore store = new DocumentStore(plugins().resolve("Animus"))) {
            LibsDisguisesSaves.Result read = LibsDisguisesSaves.read(store, Optional.of(plugins()));
            assertTrue(read.found().isEmpty(), read.found().toString());
            assertEquals(2, read.problems().size(), read.problems().toString());
            assertTrue(read.problems().get(0).startsWith("plugins/LibsDisguises/SavedDisguises/00000000-0000-4000-8000-000000000001: saved in LibsDisguises' old format"));
            assertTrue(read.problems().get(1).startsWith("plugins/Skript/scripts/npcs.sk line 2: the disguise 'player %player%' uses placeholders"));
        }
    }

    @Test void onlyAPlayerBodyLooksUpItsEntityUuid() {
        UUID npc = UUID.fromString("00000000-0000-4000-8000-000000000002");
        LibsDisguisesSaves.Result result = new LibsDisguisesSaves.Result(Map.of(UUID.fromString("00000000-0000-2000-8000-000000000002"),
                new LibsDisguisesSaves.Found("pig", "test")), List.of());
        assertEquals("pig", LibsDisguisesSaves.of(result, npc, "minecraft:player").orElseThrow().disguise());
        assertTrue(LibsDisguisesSaves.of(result, npc, "minecraft:villager").isEmpty());
    }
}
