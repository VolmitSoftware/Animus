package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CombatDeletionsTest {
    private static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String JSON = """
            {"schemaVersion":2,"revision":3,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":true,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
            """;
    @TempDir Path root;

    @Test void aDeletionOnDeathIsAnOrdinaryTransactionThatRollsBack() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store);
            repository.loadForStartup();
            Optional<CombatDeletions.Deleted> deleted = CombatDeletions.delete(repository, GUARD);
            assertEquals("guard", deleted.orElseThrow().id());
            assertTrue(repository.find("guard").isEmpty());
            store.rollback(deleted.get().transaction());
            repository.reloadAfterRollback();
            assertEquals(3, repository.find("guard").orElseThrow().revision(), "Rollback restores the definition exactly");
        }
    }
    @Test void aDefinitionThatIsAlreadyGoneIsNotAnError() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.loadForStartup();
            assertTrue(CombatDeletions.delete(repository, GUARD).isEmpty());
        }
    }
}
