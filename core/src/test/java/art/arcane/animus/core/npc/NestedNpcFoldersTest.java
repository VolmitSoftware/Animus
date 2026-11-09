package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** NPC files may sit directly in npcs/ or in folders of the owner's choosing, up to eight deep. */
class NestedNpcFoldersTest {
    @TempDir Path root;

    private static String definition(String id, String group) throws IOException {
        JsonObject json = JsonDocuments.object(NpcRepositoryTest.JSON);
        json.addProperty("id", id);
        json.addProperty("group", group);
        json.addProperty("uuid", UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        return JsonDocuments.write(json);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void definitionsLoadFromEveryFolderDepthAndKeepTheirFile() throws Exception {
        write("npcs/loose.json", definition("loose", "town"));
        write("npcs/bosses/dragon.json", definition("dragon", "bosses"));
        write("npcs/world/town/market/blacksmith.json", definition("blacksmith", "world"));
        write("npcs/a/b/c/d/e/f/g/h/deep.json", definition("deep", "a"));
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            assertEquals(List.of(), npcs.loadForStartup());
            assertEquals("npcs/loose.json", npcs.find("loose").orElseThrow().path());
            assertEquals("", npcs.find("loose").orElseThrow().group());
            assertEquals("npcs/bosses/dragon.json", npcs.find("dragon").orElseThrow().path());
            assertEquals("world/town/market", npcs.find("blacksmith").orElseThrow().group());
            assertEquals("npcs/a/b/c/d/e/f/g/h/deep.json", npcs.find("deep").orElseThrow().path());

            NpcRepository.Draft draft = npcs.draft("blacksmith");
            JsonObject renamed = draft.document();
            renamed.addProperty("name", "Smith");
            npcs.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), renamed));
            assertEquals("Smith", JsonDocuments.object(Files.readString(root.resolve("npcs/world/town/market/blacksmith.json"))).get("name").getAsString());
            assertEquals("world/town/market", JsonDocuments.object(Files.readString(root.resolve("npcs/world/town/market/blacksmith.json"))).get("group").getAsString());
            assertEquals(List.of(), npcs.reload());
        }
    }

    @Test void aMovedFileKeepsItsIdentityAndAFileNamedOtherThanItsIdIsRefused() throws Exception {
        write("npcs/town/guard.json", definition("guard", "town"));
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            npcs.loadForStartup();
            Files.createDirectories(root.resolve("npcs/town/gate"));
            Files.move(root.resolve("npcs/town/guard.json"), root.resolve("npcs/town/gate/guard.json"));
            assertEquals(List.of(), npcs.reload());
            assertEquals("npcs/town/gate/guard.json", npcs.find("guard").orElseThrow().path());
            write("npcs/town/renamed.json", definition("other", "town"));
            assertTrue(npcs.reload().getFirst().contains("npcs/town/renamed.json"));
            assertTrue(npcs.find("other").isEmpty());
        }
    }

    @Test void theRecoveryCopyWorksThreeFoldersDeep() throws Exception {
        String path = "npcs/world/town/market/guard.json", pointer = "state/npc-definitions/last-valid/" + path;
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            npcs.loadForStartup();
            npcs.apply(new NpcRepository.Draft(path, "missing", 0, JsonDocuments.object(definition("guard", "world/town/market"))));
            assertNotNull(store.read(pointer), "the edit records a recovery copy");
        }
        String valid = Files.readString(root.resolve(path));
        Files.writeString(root.resolve(path), valid.replace("\"y\": 64", "\"y\": \"broken\""));
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            npcs.loadForStartup();
            assertEquals("Guard", npcs.find("guard").orElseThrow().name());
            assertTrue(npcs.diagnostics().getFirst().contains("durable recovery"), npcs.diagnostics().toString());

            String changed = valid.replace("\"Guard\"", "\"Captain\"");
            store.commit(List.of(new DocumentStore.Change(path, store.hash(path), changed)));
            assertNull(store.read(pointer), "a raw source change invalidates the recovery copy");
        }
    }

    @Test void anEmptyGroupNamesAnNpcDirectlyInNpcs() throws Exception {
        assertEquals(List.of(), NpcDefinition.validate(JsonDocuments.object(definition("loose", ""))));
        write("npcs/loose.json", definition("loose", ""));
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            assertEquals(List.of(), npcs.loadForStartup());
            assertEquals("", npcs.find("loose").orElseThrow().group());
            npcs.apply(new NpcRepository.Draft("npcs/fresh.json", "missing", 0, JsonDocuments.object(definition("fresh", ""))));
            assertEquals("npcs/fresh.json", npcs.find("fresh").orElseThrow().path());
            assertEquals(List.of(), npcs.reload());
        }
    }

    @Test void aFolderDeeperThanEightIsSkippedAndTheRestLoads() throws Exception {
        write("npcs/a/b/c/d/e/f/g/h/i/deep.json", definition("deep", "a"));
        write("npcs/a/shallow.json", definition("shallow", "a"));
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            repository.loadForStartup();
            assertTrue(repository.find("shallow").isPresent());
            assertTrue(repository.find("deep").isEmpty());
            assertThrows(IOException.class, () -> store.commit(List.of(new DocumentStore.Change("state/npc-definitions/last-valid/npcs/a/b/c/d/e/f/g/h/i/deep.json", "missing", "{}"))));
        }
    }
}
