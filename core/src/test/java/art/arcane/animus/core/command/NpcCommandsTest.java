package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcCommandsTest {
    @TempDir Path root;

    private static final String GUARD = "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"12345678-1234-1234-1234-123456789abc\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"N\","
            + "\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"attributes\":{\"minecraft:gravity\":0.04},\"groups\":[\"giants\"]}";

    @Test void attributeSetsShowsAndResetsOneAttributeInTheFile() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/giants.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"giants\",\"values\":{\"attributes\":{\"scale\":3}}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());

            String set = NpcCommands.attribute(repository, "guard", "minecraft:scale", "1.5");
            assertTrue(set.startsWith("guard: scale = 1.5; tx "), set);
            assertEquals(1.5, attributes(repository).get("scale").getAsDouble());

            String gravity = NpcCommands.attribute(repository, "guard", "gravity", "0.02");
            assertTrue(gravity.startsWith("guard: gravity = 0.02"), gravity);
            assertFalse(attributes(repository).has("minecraft:gravity"), "the namespaced spelling is replaced by the bare id");

            List<String> shown = NpcCommands.show(repository, "guard", "scale");
            assertEquals("guard: scale = 1.5 (npc); vanilla default 1, range 0.0625..16", shown.getFirst());

            String reset = NpcCommands.attribute(repository, "guard", "scale", "reset");
            assertTrue(reset.startsWith("guard: scale reset; tx "), reset);
            assertFalse(attributes(repository).has("scale"));
            assertEquals("guard: scale = 3 (giants); vanilla default 1, range 0.0625..16", NpcCommands.show(repository, "guard", "scale").getFirst(),
                    "a group's value shows through once the NPC's own is gone");
            NpcCommands.attribute(repository, "guard", "gravity", "reset");
            assertFalse(repository.draft("guard").document().has("attributes"), "an empty attributes object is removed");
        }
    }

    @Test void unknownAttributesAndValuesOutOfRangeAreRefused() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "bogus", "1"));
            assertTrue(unknown.getMessage().startsWith("unknown attribute bogus"), unknown.getMessage());
            IllegalArgumentException range = assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "40"));
            assertEquals("expected 0.0625..16 for scale", range.getMessage());
            assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "big"));
            assertEquals("guard: step_height is not set; vanilla default 0.6, range 0..10", NpcCommands.show(repository, "guard", "step_height").getFirst());
        }
    }

    @Test void theAttributeCommandCompletesAttributeIds() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        assertTrue(tree.values("attribute", new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN)).contains("scale"));
        assertTrue(tree.values("attribute", new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN)).contains("movement_speed"));
    }

    private static JsonObject attributes(NpcRepository repository) throws IOException { return repository.draft("guard").document().getAsJsonObject("attributes"); }
}
