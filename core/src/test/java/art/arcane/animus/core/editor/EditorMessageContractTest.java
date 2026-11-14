package art.arcane.animus.core.editor;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared message fixtures against editor-v1.schema.json, and the Java request readers against the request fixtures. */
class EditorMessageContractTest {
    static SchemaValidator validator() throws IOException {
        return new SchemaValidator(SharedFixtures.object(SharedFixtures.schemaDirectory().resolve("editor-v1.schema.json")));
    }

    private static List<Path> fixtures(String folder) throws IOException {
        try (Stream<Path> files = Files.list(SharedFixtures.fixture("editor/" + folder))) {
            return files.filter((Path file) -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String definition(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.indexOf('.'));
    }

    @Test
    void validator_everyValidMessageFixture_isAccepted() throws IOException {
        SchemaValidator validator = validator();
        List<String> problems = new ArrayList<>();
        for (Path file : fixtures("messages")) {
            List<String> found = validator.check(definition(file), SharedFixtures.object(file));
            if (!found.isEmpty()) problems.add(file.getFileName() + " " + found);
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
        assertTrue(fixtures("messages").size() >= 20);
    }

    @Test
    void validator_everyInvalidMessageFixture_isRefused() throws IOException {
        SchemaValidator validator = validator();
        for (Path file : fixtures("messages/invalid"))
            assertFalse(validator.check(definition(file), SharedFixtures.object(file)).isEmpty(), file.getFileName() + " was accepted");
    }

    @Test
    void changeSetReader_validFixture_readsEveryDocument() throws Exception {
        JsonObject body = SharedFixtures.object(SharedFixtures.fixture("editor/messages/change-set.0.json"));
        EditorMessages.ChangeSet set = EditorMessages.changeSet(body, 512);
        assertEquals("6f1c2d3e4f5a6b7c", set.idempotencyKey());
        assertEquals(body.getAsJsonArray("documents").size(), set.documents().size());
        assertTrue(set.documents().stream().anyMatch(EditorMessages.Change::delete));
        assertTrue(set.documents().stream().anyMatch((EditorMessages.Change change) -> change.baseRevision().isEmpty()));
    }

    @Test
    void changeSetReader_moveFixture_readsTheOldId() throws Exception {
        EditorMessages.ChangeSet set = EditorMessages.changeSet(SharedFixtures.object(SharedFixtures.fixture("editor/messages/change-set.1.json")), 512);
        EditorMessages.Change move = set.documents().getFirst();
        assertTrue(move.move());
        assertEquals("examples/greeter", move.from().orElseThrow());
        assertEquals("town/market/greeter", move.id());
    }

    @Test
    void changeSetReader_moveFromAnIdListedAgain_isRefused() throws IOException {
        JsonObject body = SharedFixtures.object(SharedFixtures.fixture("editor/messages/change-set.1.json"));
        JsonObject other = body.getAsJsonArray("documents").get(0).getAsJsonObject().deepCopy();
        other.remove("from");
        other.addProperty("id", "examples/greeter");
        body.getAsJsonArray("documents").add(other);
        assertThrows(EditorError.class, () -> EditorMessages.changeSet(body, 512));
    }

    @Test
    void changeSetReader_invalidFixtures_areRefused() throws IOException {
        for (Path file : fixtures("messages/invalid")) {
            if (!definition(file).equals("change-set")) continue;
            JsonObject body = SharedFixtures.object(file);
            assertThrows(EditorError.class, () -> EditorMessages.changeSet(body, 512), file.getFileName().toString());
        }
    }

    @Test
    void changeSetReader_unknownField_isRefused() throws IOException {
        JsonObject body = SharedFixtures.object(SharedFixtures.fixture("editor/messages/change-set.0.json"));
        body.addProperty("extra", 1);
        EditorError refused = assertThrows(EditorError.class, () -> EditorMessages.changeSet(body, 512));
        assertEquals(EditorError.Code.INVALID_REQUEST, refused.code());
    }

    @Test
    void changeSetReader_overDocumentLimit_isTooLarge() throws IOException {
        JsonObject body = SharedFixtures.object(SharedFixtures.fixture("editor/messages/change-set.0.json"));
        EditorError refused = assertThrows(EditorError.class, () -> EditorMessages.changeSet(body, 1));
        assertEquals(EditorError.Code.TOO_LARGE, refused.code());
    }

    @Test
    void errorCodes_matchSchemaEnumAndEndpointStatuses() throws IOException {
        JsonObject endpoints = SharedFixtures.object(SharedFixtures.schemaDirectory().resolve("editor-v1.endpoints.json"));
        JsonObject statuses = endpoints.getAsJsonObject("errorStatus");
        assertEquals(statuses.size(), EditorError.Code.values().length);
        for (EditorError.Code code : EditorError.Code.values()) assertEquals(statuses.get(code.wireName()).getAsInt(), code.status(), code.wireName());
        SchemaValidator validator = validator();
        for (EditorError.Code code : EditorError.Code.values()) {
            art.arcane.animus.core.editor.transport.EditorResponse response = EditorReplies.error(new EditorError(code, "message", 100, null));
            assertTrue(validator.check("error-response", response.body().orElseThrow()).isEmpty(), code.wireName());
        }
    }

    @Test
    void scopeMissing_namesEveryScopeTheSchemaKnows() throws IOException {
        SchemaValidator validator = validator();
        for (EditorScope scope : EditorScope.values()) {
            JsonObject body = EditorReplies.error(EditorError.scopeMissing(scope, "This token lacks " + scope.wireName() + ".")).body().orElseThrow().getAsJsonObject();
            assertEquals(scope.wireName(), body.getAsJsonObject("error").get("scope").getAsString());
            assertTrue(validator.check("error-response", body).isEmpty(), scope.wireName());
        }
        JsonObject unknown = EditorReplies.error(EditorError.scopeMissing(EditorScope.READ, "x")).body().orElseThrow().getAsJsonObject().deepCopy();
        unknown.getAsJsonObject("error").addProperty("scope", "apply:everything");
        assertFalse(validator.check("error-response", unknown).isEmpty(), "the scope is one of the schema's scopes");
    }
}
