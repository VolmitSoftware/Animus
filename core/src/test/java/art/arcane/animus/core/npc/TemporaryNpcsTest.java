package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TemporaryNpcsTest {
    @TempDir Path root;

    private static JsonObject npc(String id, UUID uuid) throws IOException {
        return JsonDocuments.object("""
                {"schemaVersion":2,"revision":1,"uuid":"%s","id":"%s","group":"","name":"Temp","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
                """.formatted(uuid, id));
    }

    @Test void aTemporaryNpcIsPublishedButNeverWrittenAndFileCommandsRefuseIt() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.reload();
            NpcDefinition added = repository.addTemporary("plugin:Demo", npc("visitor", UUID.randomUUID()));
            assertEquals(1, added.revision());
            assertEquals(added, repository.snapshot().get("visitor"));
            assertTrue(repository.stored().isEmpty(), "writers start from files only");
            assertFalse(Files.exists(root.resolve("npcs/visitor.json")));
            assertEquals(Optional.of("plugin:Demo"), repository.temporary("visitor").map(NpcRepository.Temporary::owner));
            IOException draft = assertThrows(IOException.class, () -> repository.draft("visitor"));
            assertEquals("visitor is a temporary NPC from plugin:Demo; it has no file", draft.getMessage());
            assertThrows(IOException.class, () -> repository.delete("visitor"));
            repository.reload();
            assertTrue(repository.find("visitor").isPresent(), "a reload keeps temporary NPCs");
        }
    }

    @Test void takenIdsAndUuidsAreRefusedAndASavedNpcThatTakesTheIdEndsTheTemporaryOne() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.reload();
            UUID uuid = UUID.randomUUID();
            repository.addTemporary("plugin:Demo", npc("visitor", uuid));
            IOException id = assertThrows(IOException.class, () -> repository.addTemporary("plugin:Other", npc("visitor", UUID.randomUUID())));
            assertEquals("An NPC named visitor already exists", id.getMessage());
            assertThrows(IOException.class, () -> repository.addTemporary("plugin:Other", npc("other", uuid)));
            store.commit(List.of(new DocumentStore.Change("npcs/visitor.json", "missing", JsonDocuments.write(npc("visitor", UUID.randomUUID())))));
            repository.reload();
            assertTrue(repository.temporary("visitor").isEmpty(), "the saved NPC wins its id");
            assertEquals(1, repository.snapshot().size());
        }
    }

    @Test void replacingKeepsIdentityAndRaisesTheRevisionAndRemovalIsPerOwner() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.reload();
            UUID uuid = UUID.randomUUID();
            NpcDefinition first = repository.addTemporary("plugin:Demo", npc("visitor", uuid));
            repository.addTemporary("plugin:Demo", npc("second", UUID.randomUUID()));
            repository.addTemporary("module:other", npc("third", UUID.randomUUID()));
            JsonObject renamed = first.json(); renamed.addProperty("name", "Renamed");
            NpcDefinition replaced = repository.replaceTemporary("visitor", renamed);
            assertEquals(2, replaced.revision());
            assertEquals("Renamed", repository.find("visitor").orElseThrow().name());
            JsonObject moved = replaced.json(); moved.addProperty("uuid", UUID.randomUUID().toString());
            assertThrows(IOException.class, () -> repository.replaceTemporary("visitor", moved), "the UUID cannot change");
            JsonObject stale = first.json();
            assertThrows(IOException.class, () -> repository.replaceTemporary("visitor", stale), "a stale revision is a conflict");
            assertEquals(List.of("second", "visitor"), repository.removeTemporaries("plugin:Demo"));
            assertEquals(Map.of("third", "module:other"), Map.of("third", repository.temporary("third").orElseThrow().owner()));
            assertTrue(repository.find("visitor").isEmpty());
            assertTrue(repository.removeTemporary("third"));
            assertFalse(repository.removeTemporary("third"));
        }
    }

    @Test void moduleRulesRefuseChangedModuleDataOnly() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.reload();
            repository.moduleRules((Optional<JsonObject> previous, JsonObject next) -> {
                JsonObject modules = next.has("modules") ? next.getAsJsonObject("modules") : new JsonObject();
                JsonObject before = previous.filter((JsonObject old) -> old.has("modules")).map((JsonObject old) -> old.getAsJsonObject("modules")).orElse(new JsonObject());
                boolean changed = !String.valueOf(modules.get("demo:clicks")).equals(String.valueOf(before.get("demo:clicks")));
                return changed && modules.has("demo:clicks") && !modules.getAsJsonObject("demo:clicks").has("count") ? List.of("$.modules.demo:clicks.count: expected a number") : List.of();
            });
            JsonObject bad = npc("guard", UUID.randomUUID());
            JsonObject modules = new JsonObject(); modules.add("demo:clicks", new JsonObject()); bad.add("modules", modules);
            IOException refused = assertThrows(IOException.class, () -> repository.apply(new NpcRepository.Draft("npcs/guard.json", "missing", 0, bad)));
            assertEquals("$.modules.demo:clicks.count: expected a number", refused.getMessage());
            assertThrows(IOException.class, () -> repository.addTemporary("plugin:Demo", bad));
            modules.getAsJsonObject("demo:clicks").addProperty("count", 0);
            repository.apply(new NpcRepository.Draft("npcs/guard.json", "missing", 0, bad));
            assertEquals(1, repository.stored().size());
        }
    }
}
