package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A batch over many NPCs, such as a trait-group assignment, skips a member whose hand edit was refused instead of failing. */
class RefusedBatchMemberTest {
    @TempDir Path root;

    private static List<NpcRepository.Draft> grouped(NpcRepository repository, List<String> ids) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = repository.draft(id);
            JsonObject document = draft.document();
            JsonArray groups = new JsonArray();
            groups.add("guards");
            document.add("groups", groups);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        return drafts;
    }

    @Test void aGroupBatchSkipsTheRefusedMemberAndNamesIt() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", NpcRepositoryTest.JSON),
                    new DocumentStore.Change("npcs/town/baker.json", "missing", NpcRepositoryTest.BAKER),
                    new DocumentStore.Change("groups/guards.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"guards\",\"values\":{\"teamColor\":\"aqua\"}}")));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty(), repository.diagnostics().toString());
            String broken = NpcRepositoryTest.JSON.replace("\"y\":64", "\"y\":\"bad\"");
            Files.writeString(root.resolve("npcs/town/guard.json"), broken);
            assertEquals(1, repository.reload().size());

            String result = repository.applyAll(grouped(repository, List.of("guard", "baker")));
            assertTrue(result.endsWith("; skipped guard: npcs/town/guard.json has refused hand edits; fix the file or /animus reload"), result);
            assertEquals(List.of("guards"), repository.find("baker").orElseThrow().groups());
            assertTrue(repository.find("guard").orElseThrow().groups().isEmpty());
            assertEquals(broken, Files.readString(root.resolve("npcs/town/guard.json")), "a batch never overwrites a refused file");

            IOException alone = assertThrows(IOException.class, () -> repository.applyAll(grouped(repository, List.of("guard"))));
            assertEquals("Skipped guard: npcs/town/guard.json has refused hand edits; fix the file or /animus reload", alone.getMessage());
        }
    }
}
