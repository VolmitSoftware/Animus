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
            {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"modules":{"absent:module":{"preserve":true}}}
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
            Files.writeString(root.resolve(draft.path()), JSON.replace("\"Guard\"", "\"Hand edit not read yet\""));
            IOException conflict = assertThrows(IOException.class, () -> repository.apply(draft));
            assertEquals("Conflict: npcs/town/guard.json changed since Animus read it; fix the file or /animus reload", conflict.getMessage());
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
    @Test void anEditOrDeleteReplacesARefusedHandEditAndLeavesOtherNpcsAlone() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON), new DocumentStore.Change("npcs/town/baker.json", "missing", BAKER)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            String broken = JSON.replace("\"y\":64", "\"y\":\"bad\"");
            Files.writeString(root.resolve("npcs/town/guard.json"), broken);
            assertEquals(1, repository.reload().size());

            NpcRepository.Draft draft = repository.draft("guard");
            JsonObject edited = draft.document(); edited.addProperty("name", "Watchman");
            String saved = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
            assertTrue(saved.endsWith("; replaced the refused hand edits in npcs/town/guard.json"), saved);
            assertTrue(Files.readString(root.resolve("npcs/town/guard.json")).contains("\"Watchman\""));
            assertTrue(repository.diagnostics().isEmpty(), repository.diagnostics().toString());
            assertFalse(repository.apply(repository.draft("guard")).contains("refused"), "the note appears only while refused edits are on disk");

            Files.writeString(root.resolve("npcs/town/guard.json"), broken);
            assertEquals(1, repository.reload().size());
            String deleted = repository.delete("guard");
            assertTrue(deleted.endsWith("; removed the refused hand edits in npcs/town/guard.json"), deleted);
            assertFalse(Files.exists(root.resolve("npcs/town/guard.json")));
            assertTrue(repository.find("guard").isEmpty());

            String baker = repository.delete("baker");
            assertFalse(baker.contains(";"), baker);
            assertTrue(repository.find("baker").isEmpty());
        }
    }

    @Test void aHotloadRefusalRecordedForTheFileLetsTheNextEditReplaceIt() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            String refused = JSON.replace("\"Guard\"", "\"Refused by hotload\"");
            Files.writeString(root.resolve("npcs/town/guard.json"), refused);
            assertThrows(IOException.class, () -> repository.delete("guard"), "an unreviewed change is a real conflict");
            repository.refused("npcs/town/guard.json", JsonDocuments.hash(refused));
            assertTrue(repository.delete("guard").endsWith("; removed the refused hand edits in npcs/town/guard.json"));
        }
    }

    static final String BAKER = JSON.replace("12345678-1234-1234-1234-123456789abc", "22345678-1234-1234-1234-123456789abc")
            .replace("\"id\":\"guard\"", "\"id\":\"baker\"").replace("\"name\":\"Guard\"", "\"name\":\"Baker\"");

    @Test void duplicateIdentityRefusesOnlyTheNewcomer() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON)));
            NpcRepository repository = new NpcRepository(store); repository.reload();
            DocumentStore.atomic(root.resolve("npcs/town/duplicate.json"), JSON.replace("\"id\":\"guard\"", "\"id\":\"duplicate\""));
            List<String> problems = repository.reload();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.getFirst().startsWith("npcs/town/duplicate.json: $.uuid: duplicate"), problems.toString());
            assertEquals(1, repository.all().size());
            assertTrue(repository.find("guard").isPresent());
        }
    }

    @Test void aValidEditAppliesWhileAnotherFileIsInvalid() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON), new DocumentStore.Change("npcs/town/baker.json", "missing", BAKER)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            Files.writeString(root.resolve("npcs/town/guard.json"), JSON.replace("\"y\":64", "\"y\":\"bad\"").replace("\"Guard\"", "\"Broken\""));
            Files.writeString(root.resolve("npcs/town/baker.json"), BAKER.replace("\"Baker\"", "\"Head Baker\""));
            List<String> problems = repository.reload();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.getFirst().startsWith("npcs/town/guard.json: $.position.y"), problems.toString());
            assertEquals("Head Baker", repository.find("baker").orElseThrow().name());
            assertEquals("Guard", repository.find("guard").orElseThrow().name(), "the invalid file keeps its own last valid version");
            assertTrue(repository.diagnostics().getFirst().startsWith("npcs/town/guard.json"), repository.diagnostics().toString());
            Files.writeString(root.resolve("npcs/town/guard.json"), JSON.replace("\"Guard\"", "\"Fixed Guard\""));
            assertTrue(repository.reload().isEmpty());
            assertEquals("Fixed Guard", repository.find("guard").orElseThrow().name());
            assertTrue(repository.diagnostics().isEmpty());
        }
    }

    @Test void aNewInvalidFileIsSkippedAndAnEditThatStealsAnIdentityKeepsItsOwnLastVersion() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", JSON), new DocumentStore.Change("npcs/town/baker.json", "missing", BAKER)));
            NpcRepository repository = new NpcRepository(store); repository.reload();
            Files.writeString(root.resolve("npcs/town/new.json"), "{not json");
            Files.writeString(root.resolve("npcs/town/baker.json"), JSON.replace("\"id\":\"guard\"", "\"id\":\"baker\"").replace("\"Guard\"", "\"Thief\""));
            List<String> problems = repository.reload();
            assertEquals(2, problems.size(), problems.toString());
            assertTrue(problems.stream().anyMatch((String problem) -> problem.startsWith("npcs/town/new.json: ")), problems.toString());
            assertTrue(problems.stream().anyMatch((String problem) -> problem.startsWith("npcs/town/baker.json: $.uuid: duplicate")), problems.toString());
            assertEquals("Baker", repository.find("baker").orElseThrow().name());
            assertEquals("Guard", repository.find("guard").orElseThrow().name());
            assertEquals(2, repository.all().size());
        }
    }
}
