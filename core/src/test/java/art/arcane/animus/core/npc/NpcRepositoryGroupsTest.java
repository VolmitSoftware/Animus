package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcRepositoryGroupsTest {
    @TempDir Path root;
    static final String GUARD = """
            {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"groups":["watch"],"tags":["gate"]}
            """;
    static final String BAKER = GUARD.replace("12345678-1234-1234-1234-123456789abc", "22345678-1234-1234-1234-123456789abc")
            .replace("\"id\":\"guard\"", "\"id\":\"baker\"").replace(",\"groups\":[\"watch\"]", "");

    static String group(String values) { return "{\"schemaVersion\":2,\"revision\":1,\"id\":\"watch\",\"values\":" + values + "}"; }

    private NpcRepository loaded(DocumentStore store, String watch) throws IOException {
        store.commit(List.of(new DocumentStore.Change("groups/watch.json", "missing", watch), new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD),
                new DocumentStore.Change("npcs/town/baker.json", "missing", BAKER)));
        NpcRepository repository = new NpcRepository(store);
        assertTrue(repository.reload().isEmpty());
        return repository;
    }

    @Test
    void snapshotHoldsEffectiveDefinitionsWhileDraftsStayStored() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"teamColor\":\"gold\",\"tags\":[\"guard\"]}"));
            NpcDefinition effective = repository.find("guard").orElseThrow();
            assertEquals("gold", effective.json().get("teamColor").getAsString());
            assertEquals(List.of("guard", "gate"), effective.tags());
            assertFalse(repository.draft("guard").document().has("teamColor"));
            assertEquals("watch", repository.provenance("guard").get("teamColor"));
            assertEquals("npc", repository.provenance("guard").get("tags"));
            assertTrue(repository.provenance("baker").isEmpty());
        }
    }

    @Test
    void anEditNamingAnUnknownGroupIsRefused() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"glowing\":true}"));
            NpcRepository.Draft draft = repository.draft("baker");
            JsonObject edited = draft.document();
            edited.add("groups", JsonParser.parseString("[\"missing\"]"));
            IOException refused = assertThrows(IOException.class, () -> repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited)));
            assertEquals("$.groups[0]: unknown group missing", refused.getMessage());
            Files.writeString(root.resolve("npcs/town/baker.json"), JsonParser.parseString(BAKER).getAsJsonObject().toString().replace("\"revision\":1", "\"revision\":2")
                    .replace("\"tags\"", "\"groups\":[\"missing\"],\"tags\""));
            List<String> problems = repository.reload();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.getFirst().startsWith("npcs/town/baker.json: $.groups[0]: unknown group missing; kept the last valid revision 1"), problems.toString());
            assertTrue(repository.find("baker").orElseThrow().groups().isEmpty());
        }
    }

    @Test
    void aDeletedGroupLeavesItsNpcsOnTheirLastValidEffectiveDefinition() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"teamColor\":\"gold\"}"));
            Files.delete(root.resolve("groups/watch.json"));
            assertTrue(repository.reload().isEmpty());
            assertEquals("gold", repository.find("guard").orElseThrow().json().get("teamColor").getAsString());
            assertEquals("$.groups[0]: unknown group watch", repository.groupProblems().get("npcs/town/guard.json"));
            NpcDefinition kept = repository.find("guard").orElseThrow();
            repository.reload();
            assertSame(kept, repository.find("guard").orElseThrow(), "a problem that has not changed keeps the same definition");
        }
    }

    @Test
    void aGroupEditGivesItsNpcsNewEffectiveDefinitionsAndAnInvalidOneIsKept() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"teamColor\":\"gold\"}"));
            NpcDefinition before = repository.find("guard").orElseThrow();
            Files.writeString(root.resolve("groups/watch.json"), group("{\"teamColor\":\"aqua\"}").replace("\"revision\":1", "\"revision\":2"));
            assertTrue(repository.reload().isEmpty());
            NpcDefinition after = repository.find("guard").orElseThrow();
            assertNotSame(before, after);
            assertEquals("aqua", after.json().get("teamColor").getAsString());
            Files.writeString(root.resolve("groups/watch.json"), group("{\"teamColor\":\"plaid\"}").replace("\"revision\":1", "\"revision\":3"));
            List<String> problems = repository.reload();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.getFirst().startsWith("groups/watch.json: $.values.teamColor"), problems.toString());
            assertTrue(problems.getFirst().endsWith("kept the last valid revision 2"), problems.toString());
            assertEquals("aqua", repository.find("guard").orElseThrow().json().get("teamColor").getAsString());
        }
    }

    @Test
    void aCombinationThatIsInvalidAsAWholeIsAGroupProblem() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"glowing\":true}"));
            StringBuilder tags = new StringBuilder("[");
            for (int i = 0; i < 32; i++) tags.append(i == 0 ? "" : ",").append("\"t").append(i).append('"');
            Files.writeString(root.resolve("groups/watch.json"), group("{\"tags\":" + tags + "]}").replace("\"revision\":1", "\"revision\":2"));
            assertTrue(repository.reload().isEmpty());
            assertTrue(repository.groupProblems().get("npcs/town/guard.json").contains("$.tags: expected at most 32 tags"), repository.groupProblems().toString());
            assertTrue(repository.find("guard").orElseThrow().json().get("glowing").getAsBoolean());
        }
    }

    @Test
    void applyAllRefusesTheWholeBatchWhenOneResultIsInvalid() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store, group("{\"glowing\":true}"));
            NpcRepository.Draft guard = repository.draft("guard"), baker = repository.draft("baker");
            JsonObject good = baker.document();
            good.add("groups", JsonParser.parseString("[\"watch\"]"));
            JsonObject bad = guard.document();
            bad.add("groups", JsonParser.parseString("[\"watch\",\"missing\"]"));
            IOException refused = assertThrows(IOException.class, () -> repository.applyAll(List.of(
                    new NpcRepository.Draft(baker.path(), baker.expectedHash(), baker.expectedRevision(), good),
                    new NpcRepository.Draft(guard.path(), guard.expectedHash(), guard.expectedRevision(), bad))));
            assertTrue(refused.getMessage().contains("guard: $.groups[1]: unknown group missing"), refused.getMessage());
            assertTrue(repository.find("baker").orElseThrow().groups().isEmpty());
            repository.applyAll(List.of(new NpcRepository.Draft(baker.path(), baker.expectedHash(), baker.expectedRevision(), good)));
            assertTrue(repository.find("baker").orElseThrow().json().get("glowing").getAsBoolean());
            assertEquals(2, repository.draft("baker").expectedRevision());
            assertTrue(repository.reload().isEmpty());
        }
    }
}
