package art.arcane.animus.core.npc;

import art.arcane.animus.core.*;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionRecoveryTest {
    @TempDir Path root;
    private static final String PATH = "npcs/town/guard.json";
    private static final String POINTER = "state/npc-definitions/last-valid/" + PATH;
    private AnimusRuntime runtime() { return new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root)); }
    private static String seed(NpcRepository repository) throws IOException {
        return repository.apply(new NpcRepository.Draft(PATH, "missing", 0, JsonDocuments.object(NpcRepositoryTest.JSON)));
    }
    private static String rename(NpcRepository repository, String id, String name) throws IOException {
        var draft = repository.draft(id); var json = draft.document(); json.addProperty("name", name);
        return repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), json));
    }

    @Test void restartRetainsKnownInvalidDefinitionUntilAnExplicitEditReplacesIt() throws Exception {
        String valid;
        try (AnimusRuntime runtime = runtime()) {
            runtime.start(); seed(runtime.npcs());
            List.of(new Administration(runtime).spawned("guard", true));
            valid = Files.readString(root.resolve(PATH));
        }
        String broken = valid.replace("\"y\": 64", "\"y\": \"broken\"");
        assertNotEquals(valid, broken); Files.writeString(root.resolve(PATH), broken);
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
            assertTrue(runtime.npcs().draft("guard").document().get("spawned").getAsBoolean());
            assertTrue(runtime.npcs().diagnostics().getFirst().contains("$.position.y"));
            assertTrue(runtime.npcs().diagnostics().getFirst().contains("durable recovery"));
            assertEquals(broken, Files.readString(root.resolve(PATH)));
            assertTrue(new Administration(runtime).status().stream().anyMatch(line -> line.contains("| 1 file problem, see /animus validate")));
            Files.writeString(root.resolve(PATH), broken.replace("\"Guard\"", "\"Unread hand edit\""));
            assertThrows(IOException.class, () -> rename(runtime.npcs(), "guard", "Do not overwrite"), "a change nobody read yet is never overwritten");
            Files.writeString(root.resolve(PATH), broken);
            assertTrue(rename(runtime.npcs(), "guard", "Explicit edit").endsWith("; replaced the refused hand edits in " + PATH));
            assertTrue(Files.readString(root.resolve(PATH)).contains("Explicit edit"));
            assertTrue(runtime.npcs().reload().isEmpty()); assertTrue(runtime.npcs().diagnostics().isEmpty());
        }
    }

    @Test void acceptedDeletionIsNotResurrectedByAnotherBrokenFileOrLaterPathReuse() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            JsonObject other = repository.draft("guard").document(); other.addProperty("id", "other"); other.addProperty("uuid", UUID.randomUUID().toString());
            repository.apply(new NpcRepository.Draft("npcs/town/other.json", "missing", 0, other));
        }
        Files.delete(root.resolve(PATH)); Files.writeString(root.resolve("npcs/town/other.json"), "{broken");
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store);
            assertEquals(1, repository.loadForStartup().size());
            assertTrue(repository.find("guard").isEmpty()); assertTrue(repository.find("other").isPresent());
            assertNull(store.read(POINTER));
        }
        Files.writeString(root.resolve(PATH), "{a new invalid file");
        try (AnimusRuntime runtime = runtime()) { assertThrows(IOException.class, runtime::start); }
        assertEquals("{a new invalid file", Files.readString(root.resolve(PATH)));
    }

    @Test void independentUndoRestoresItsOwnRecoveryPointerAndPreservesOtherEdits() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            JsonObject other = repository.draft("guard").document(); other.addProperty("id", "other"); other.addProperty("uuid", UUID.randomUUID().toString());
            repository.apply(new NpcRepository.Draft("npcs/town/other.json", "missing", 0, other));
            String first = rename(repository, "guard", "Changed Guard");
            rename(repository, "other", "Changed Other");
            store.rollback(first); assertTrue(repository.reloadAfterRollback().isEmpty());
            assertEquals("Guard", repository.find("guard").orElseThrow().name());
            assertEquals("Changed Other", repository.find("other").orElseThrow().name());
        }
        Files.writeString(root.resolve(PATH), "{broken");
        try (AnimusRuntime runtime = runtime()) {
            runtime.start(); assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
            assertEquals("Changed Other", runtime.npcs().find("other").orElseThrow().name());
        }
    }

    @Test void journalRecoveryPublishesDefinitionAndRecoveryPointerTogether() throws Exception {
        JsonObject committed;
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            String id = rename(repository, "guard", "After crash");
            committed = JsonDocuments.object(store.read("backups/" + id + ".json"));
        }
        assertEquals(2, committed.getAsJsonArray("entries").size());
        // Simulate process loss after replacing the source but before its checkpoint pointer.
        for (JsonElement item : committed.getAsJsonArray("entries")) {
            JsonObject entry = item.getAsJsonObject(); String path = entry.get("path").getAsString();
            String value = entry.get(path.equals(PATH) ? "after" : "before").getAsString();
            DocumentStore.atomic(root.resolve(path), value);
        }
        DocumentStore.atomic(root.resolve("state/pending.json"), JsonDocuments.write(committed));
        try (DocumentStore store = new DocumentStore(root)) { assertNull(store.read("state/pending.json")); }
        Files.writeString(root.resolve(PATH), "{later invalid edit");
        try (AnimusRuntime runtime = runtime()) {
            runtime.start(); assertEquals("After crash", runtime.npcs().find("guard").orElseThrow().name());
            assertEquals(2, runtime.npcs().find("guard").orElseThrow().revision());
        }
    }

    @Test void corruptedRecoveryObjectCannotActivateAnUnverifiedDefinition() throws Exception {
        String object;
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            object = JsonDocuments.object(store.read(POINTER)).get("objectHash").getAsString();
        }
        Files.writeString(root.resolve("state/objects/npc-definitions/" + object + ".json"), NpcRepositoryTest.JSON.replace("Guard", "Tampered"));
        Files.writeString(root.resolve(PATH), "{broken");
        try (AnimusRuntime runtime = runtime()) {
            assertTrue(assertThrows(IOException.class, runtime::start).getMessage().contains("hash changed"));
            assertFalse(runtime.isRunning());
        }
        assertEquals("{broken", Files.readString(root.resolve(PATH)));
    }

    @Test void startupDoesNotAllowAnOlderHandEditToBypassRevisionProtection() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            rename(repository, "guard", "Revision Two");
        }
        Files.writeString(root.resolve(PATH), NpcRepositoryTest.JSON);
        try (AnimusRuntime runtime = runtime()) {
            runtime.start(); assertEquals("Revision Two", runtime.npcs().find("guard").orElseThrow().name());
            assertTrue(runtime.npcs().diagnostics().getFirst().contains("$.revision"));
        }
    }

    @Test void restoredAndNewRecordsStillCannotShareAnIdentity() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
        }
        Files.writeString(root.resolve(PATH), "{broken");
        Files.writeString(root.resolve("npcs/town/other.json"), NpcRepositoryTest.JSON.replace("\"id\":\"guard\"", "\"id\":\"other\""));
        try (AnimusRuntime runtime = runtime()) { assertTrue(assertThrows(IOException.class, runtime::start).getMessage().contains("$.uuid: duplicate")); }
    }

    @Test void validationDoesNotActivateDefinitionsOrPublishRecoveryObjects() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change(PATH, "missing", NpcRepositoryTest.JSON)));
            NpcRepository repository = new NpcRepository(store);
            NpcRepository.Validation result = repository.validateStored();
            assertTrue(result.errors().isEmpty()); assertEquals(1, result.definitions());
            assertTrue(repository.snapshot().isEmpty());
            assertNull(store.read(POINTER)); assertFalse(Files.exists(root.resolve("state/objects")));
        }
    }

    @Test void deleteAndUndoRestoreTheCheckpointWithTheSameIdentity() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            UUID identity = repository.find("guard").orElseThrow().uuid();
            String undo = repository.delete("guard");
            assertNull(store.read(PATH)); assertNull(store.read(POINTER));
            store.rollback(undo); assertTrue(repository.reloadAfterRollback().isEmpty());
            assertEquals(identity, repository.find("guard").orElseThrow().uuid());
        }
        Files.writeString(root.resolve(PATH), "{broken");
        try (AnimusRuntime runtime = runtime()) { runtime.start(); assertTrue(runtime.npcs().find("guard").isPresent()); }
    }

    @Test void rawImportRollbackInvalidatesLaterCheckpointBeforePathReuse() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            String imported = store.commit(List.of(new DocumentStore.Change(PATH, "missing", NpcRepositoryTest.JSON)));
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup();
            assertNotNull(store.read(POINTER));
            String reversed = store.rollback(imported);
            assertNull(store.read(PATH)); assertNull(store.read(POINTER));
            // Undo of the rollback restores the same source and checkpoint atomically.
            store.rollback(reversed);
            assertEquals(NpcRepositoryTest.JSON, store.read(PATH)); assertNotNull(store.read(POINTER));
            store.rollback(imported);
        }
        Files.writeString(root.resolve(PATH), "{unrelated malformed replacement");
        try (AnimusRuntime runtime = runtime()) { assertThrows(IOException.class, runtime::start); }
        assertEquals("{unrelated malformed replacement", Files.readString(root.resolve(PATH)));
    }

    @Test void rawReplacementInvalidatesRecoveryAndUndoRestoresThePreviousCheckpoint() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            String original = store.read(PATH), pointer = store.read(POINTER);
            store.commit(List.of(new DocumentStore.Change(PATH, store.hash(PATH), original)));
            assertEquals(pointer, store.read(POINTER), "A raw no-op must not discard recovery");
            String replacement = store.commit(List.of(new DocumentStore.Change("npcs/town//guard.json", store.hash(PATH), original.replace("Guard", "Raw replacement"))));
            assertNull(store.read(POINTER));
            store.rollback(replacement);
            assertEquals(original, store.read(PATH)); assertEquals(pointer, store.read(POINTER));
        }
        Files.writeString(root.resolve(PATH), "{bad edit after undo");
        try (AnimusRuntime runtime = runtime()) { runtime.start(); assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name()); }
    }

    @Test void explicitRawDeletionClearsRecoveryEvenWhenSourceWasAlreadyRemoved() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = new NpcRepository(store); repository.loadForStartup(); seed(repository);
            Files.delete(root.resolve(PATH));
            store.commit(List.of(new DocumentStore.Change(PATH, "missing", null)));
            assertNull(store.read(POINTER));
        }
        Files.writeString(root.resolve(PATH), "{new invalid record");
        try (AnimusRuntime runtime = runtime()) { assertThrows(IOException.class, runtime::start); }
    }
}
