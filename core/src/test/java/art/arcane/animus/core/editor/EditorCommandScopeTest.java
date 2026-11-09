package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Documents that run commands change through the editor only with apply:commands, which no default pairing grants. */
class EditorCommandScopeTest {
    private static final String GATE = "behaviors/town/gate.json";
    @TempDir Path directory;
    private EditorHarness harness;
    private String plain, commands;

    @BeforeEach void open() throws Exception {
        harness = new EditorHarness(directory);
        plain = harness.token();
        List<EditorScope> scopes = new ArrayList<>(EditorScope.DEFAULT_PAIRING);
        scopes.add(EditorScope.APPLY_COMMANDS);
        commands = harness.token(EditorHarness.grant(scopes));
    }

    @AfterEach void close() throws Exception { harness.close(); }

    private static JsonObject gate(String command, String text) throws Exception {
        return JsonDocuments.object("""
                {"schemaVersion": 1, "revision": 1, "id": "town:gate", "entry": "main",
                 "routines": {"main": {"type": "sequence", "steps": [
                   {"type": "action", "capability": "animus:say", "arguments": {"text": "%s"}},
                   {"type": "action", "capability": "animus:command", "arguments": {"command": "%s"}}]}},
                 "triggers": [{"id": "click", "event": "interaction"}]}
                """.formatted(text, command));
    }

    private static JsonObject change(String kind, String id, JsonObject current, JsonObject source) {
        JsonObject change = new JsonObject();
        change.addProperty("kind", kind);
        change.addProperty("id", id);
        if (current == null) { change.add("baseRevision", com.google.gson.JsonNull.INSTANCE); change.add("baseHash", com.google.gson.JsonNull.INSTANCE); }
        else { change.add("baseRevision", current.get("revision")); change.add("baseHash", current.get("hash")); }
        if (source == null) change.addProperty("delete", true); else change.add("source", source);
        return change;
    }

    private static JsonObject set(String key, JsonObject change) {
        JsonObject set = new JsonObject();
        set.addProperty("idempotencyKey", key);
        JsonArray documents = new JsonArray();
        documents.add(change);
        set.add("documents", documents);
        return set;
    }

    private static void refused(EditorResponse response) {
        assertEquals(403, response.status(), () -> response.body().toString());
        JsonObject error = EditorHarness.json(response).getAsJsonObject("error");
        assertEquals("scope-missing", error.get("code").getAsString());
        assertTrue(error.get("message").getAsString().contains("apply:commands"), error.toString());
    }

    private JsonObject stored(String kind, String id) throws Exception {
        return EditorHarness.json(harness.send("GET", "/v1/documents/" + kind + "/" + id, commands, null));
    }

    @Test
    void apply_newBehaviorThatRunsACommand_needsApplyCommands() throws Exception {
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000101", change("behavior", "town:gate", null, gate("op Mallory", "Opening")))));
        assertNull(harness.read(GATE), "a refused change writes nothing");
        EditorResponse granted = harness.send("POST", "/v1/changes", commands, set("key-0000000000000102", change("behavior", "town:gate", null, gate("op Mallory", "Opening"))));
        assertEquals(200, granted.status(), () -> granted.body().toString());
        assertNotNull(harness.read(GATE));
    }

    @Test
    void apply_anyEditToABehaviorThatRunsCommands_needsApplyCommands() throws Exception {
        assertEquals(200, harness.send("POST", "/v1/changes", commands, set("key-0000000000000103", change("behavior", "town:gate", null, gate("say opened", "Opening")))).status());
        String before = harness.read(GATE);
        JsonObject current = stored("behavior", "town:gate");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000104", change("behavior", "town:gate", current, gate("say opened", "Welcome")))));
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000105", change("behavior", "town:gate", current, gate("op Mallory", "Opening")))));
        assertEquals(before, harness.read(GATE));
    }

    @Test
    void apply_inventoryButtonThatRunsACommand_needsApplyCommands() throws Exception {
        JsonObject current = stored("inventory", "shop:counter");
        JsonObject source = current.getAsJsonObject("source").deepCopy();
        JsonObject command = new JsonObject();
        command.addProperty("type", "command");
        command.addProperty("command", "op Mallory");
        source.getAsJsonObject("keys").getAsJsonObject("X").getAsJsonArray("actions").add(command);
        String before = harness.read("gui/inventories/shop/counter.json");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000106", change("inventory", "shop:counter", current, source))));
        assertEquals(before, harness.read("gui/inventories/shop/counter.json"));
        JsonObject renamed = current.getAsJsonObject("source").deepCopy();
        renamed.addProperty("title", "&8Counter");
        assertEquals(200, harness.send("POST", "/v1/changes", plain, set("key-0000000000000107", change("inventory", "shop:counter", current, renamed))).status(),
                "a document without commands needs only its apply scope");
    }

    @Test
    void undo_restoringABehaviorThatRunsCommands_needsApplyCommands() throws Exception {
        assertEquals(200, harness.send("POST", "/v1/changes", commands, set("key-0000000000000108", change("behavior", "town:gate", null, gate("say opened", "Opening")))).status());
        EditorResponse deleted = harness.send("POST", "/v1/changes", commands, set("key-0000000000000109", change("behavior", "town:gate", stored("behavior", "town:gate"), null)));
        assertEquals(200, deleted.status(), () -> deleted.body().toString());
        assertNull(harness.read(GATE));
        JsonObject undo = new JsonObject();
        undo.addProperty("idempotencyKey", "key-0000000000000110");
        refused(harness.send("POST", "/v1/changes/" + EditorHarness.json(deleted).get("changeId").getAsString() + "/undo", plain, undo));
        assertNull(harness.read(GATE));
    }

    @Test
    void importSteps_thatCouldLetCommandsRun_needApplyCommands() throws Exception {
        List<EditorScope> scopes = new ArrayList<>(EditorScope.DEFAULT_PAIRING);
        scopes.add(EditorScope.IMPORT);
        String importer = harness.token(EditorHarness.grant(scopes));
        String id = "a".repeat(64);
        JsonObject accept = new JsonObject();
        JsonArray categories = new JsonArray();
        categories.add("Commands");
        accept.add("categories", categories);
        refused(harness.send("POST", "/v1/imports/" + id + "/accept", importer, accept));
        assertEquals(409, harness.send("POST", "/v1/imports/" + id + "/apply", importer, null).status(), "with an empty allow-list an import cannot run commands");
        harness.host.commandGrants = true;
        refused(harness.send("POST", "/v1/imports/" + id + "/apply", importer, null));
    }
}
