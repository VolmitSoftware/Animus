package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NpcRepositoryTest {
    @TempDir Path root;
    static final String JSON = """
            {"schemaVersion":1,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"modules":{"absent:module":{"preserve":true}}}
            """;
    @Test void reloadRetainsLastValidAndDraftsDetectExternalChanges() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            var draft = repository.draft("guard");
            Files.writeString(root.resolve(draft.path()), JSON.replace("\"y\":64", "\"y\":\"bad\""));
            assertTrue(repository.reload().getFirst().contains("$.position.y"));
            assertEquals("Guard", repository.find("guard").orElseThrow().name());
            assertThrows(IOException.class, () -> repository.apply(draft));
            Files.writeString(root.resolve(draft.path()), JSON);
            var edited = draft.document(); edited.addProperty("name", "Watchman");
            repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
            assertEquals(2, repository.find("guard").orElseThrow().revision());
            assertTrue(repository.find("guard").orElseThrow().json().getAsJsonObject("modules").has("absent:module"));
            assertThrows(IOException.class, () -> repository.apply(draft));
            JsonObject leaked = repository.find("guard").orElseThrow().json(); leaked.addProperty("name", "Mutated");
            assertEquals("Watchman", repository.find("guard").orElseThrow().name());
        }
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.reload();
            assertEquals("Watchman", repository.find("guard").orElseThrow().name());
        }
    }
    @Test void duplicateIdentityRejectsWholeReload() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store); repository.reload();
            DocumentStore.atomic(root.resolve("npcs/town/duplicate.json"), JSON.replace("\"id\":\"guard\"", "\"id\":\"duplicate\""));
            assertFalse(repository.reload().isEmpty());
            assertEquals(1, repository.all().size());
        }
    }
}
