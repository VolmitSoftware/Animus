package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthRecordsTest {
    private static final UUID GHOST = UUID.fromString("12345678-1234-1234-1234-123456789abd");
    private static final String JSON = """
            {"schemaVersion":1,"revision":2,"uuid":"12345678-1234-1234-1234-123456789abd","id":"ghost","group":"town","name":"Ghost","type":"minecraft:player","spawned":true,
             "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"modules":{"animus:health":{"killable":true,"respawn":{"afterDeath":"permadeath"}}}}
            """;
    @TempDir Path root;

    @Test void aPermadeathSurvivesAReloadAndRevivalClearsIt() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/ghost.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store);
            repository.loadForStartup();
            assertEquals("ghost", HealthRecords.markDead(repository, GHOST).orElseThrow().id());
            assertTrue(HealthRecords.markDead(repository, GHOST).isEmpty(), "Marked once");
            NpcRepository restarted = new NpcRepository(store);
            restarted.loadForStartup();
            NpcDefinition dead = restarted.find("ghost").orElseThrow();
            assertTrue(dead.dead());
            assertFalse(DefinitionSpec.spawned(dead), "A dead NPC is not presented");
            assertTrue(CombatSettings.active(dead).isEmpty(), "Nor does its health run");
            assertTrue(HealthRecords.revive(restarted, "ghost").isPresent());
            NpcDefinition alive = restarted.find("ghost").orElseThrow();
            assertFalse(alive.dead());
            assertTrue(DefinitionSpec.spawned(alive));
            assertTrue(CombatSettings.active(alive).isPresent());
            assertTrue(HealthRecords.revive(restarted, "ghost").isEmpty(), "Reviving a living NPC changes nothing in the file");
        }
    }
}
