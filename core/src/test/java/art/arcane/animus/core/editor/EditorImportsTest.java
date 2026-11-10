package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Upload, stage, review, apply and rollback of a Citizens save through {@code /v1/imports}. */
class EditorImportsTest {
    private static final List<EditorScope> IMPORTER = List.of(EditorScope.READ, EditorScope.IMPORT, EditorScope.APPLY_NPCS, EditorScope.APPLY_BEHAVIORS, EditorScope.APPLY_GUI);

    @TempDir Path temp;
    private EditorHarness harness;
    private String token;

    @BeforeEach
    void open() throws Exception {
        harness = new EditorHarness(temp);
        // The shared export fixture holds an NPC that does not parse; a registry with it refuses to reload after an import.
        Files.delete(harness.root.resolve("npcs/examples/broken.json"));
        harness.npcs.reload();
        token = harness.token(EditorHarness.grant(IMPORTER));
    }

    @AfterEach
    void close() throws Exception { harness.close(); }

    private EditorResponse upload(String query, byte[] bytes, EditorRequest.Transport transport) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", EditorHarness.ORIGIN);
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Animus-Counter", Long.toString(harness.nextCounter()));
        headers.put(EditorReplies.PEER_HEADER, "127.0.0.1");
        return harness.request(new EditorRequest(transport, "POST", "/v1/imports" + query, headers, Optional.of(new JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))));
    }

    private static JsonObject ok(EditorResponse response) {
        assertEquals(200, response.status(), () -> response.body().map(JsonElement::toString).orElse(""));
        return EditorHarness.json(response);
    }

    private static String error(EditorResponse response) { return EditorHarness.json(response).getAsJsonObject("error").get("code").getAsString(); }

    private byte[] save() throws Exception { return Files.readAllBytes(SharedFixtures.fixture("citizens/runtime.yml")); }

    @Test
    void import_stagedByAnOlderConverter_isRefusedWithTheStepThatStagesItAgain() throws Exception {
        ok(upload("?file=citizens", save(), EditorRequest.Transport.RELAY));
        String importId = ok(harness.send("POST", "/v1/imports/stage", token, new JsonObject())).getAsJsonObject("citizens").get("importId").getAsString();
        art.arcane.animus.core.migration.OlderPreviews.age(harness.root.resolve("imports/" + importId));
        List<EditorScope> withCommands = new ArrayList<>(IMPORTER);
        withCommands.add(EditorScope.APPLY_COMMANDS);
        token = harness.token(EditorHarness.grant(withCommands));
        JsonObject refused = EditorHarness.json(harness.send("POST", "/v1/imports/" + importId + "/apply", token, null)).getAsJsonObject("error");
        assertEquals("conflict", refused.get("code").getAsString());
        String message = refused.get("message").getAsString();
        assertTrue(message.startsWith(art.arcane.animus.core.migration.OlderPreviews.refusal(importId)), message);
        assertTrue(message.endsWith(" is schemaVersion 1, this one writes 2; upload and stage the save again, then apply"), message);
        assertFalse(Files.exists(harness.root.resolve("npcs/citizens")), "nothing was installed");
    }

    @Test
    void import_uploadStageApplyRollback_runsTheDropInFlow() throws Exception {
        assertEquals("none", ok(harness.send("GET", "/v1/imports", token, null)).get("state").getAsString());
        JsonObject uploaded = ok(upload("?file=citizens", save(), EditorRequest.Transport.RELAY));
        assertEquals("imports/saves.yml", uploaded.getAsJsonObject("uploads").get("citizens").getAsString());
        assertTrue(Files.isRegularFile(harness.root.resolve("imports/saves.yml")));

        JsonObject staged = ok(harness.send("POST", "/v1/imports/stage", token, new JsonObject()));
        assertEquals("staged", staged.get("state").getAsString());
        String importId = staged.getAsJsonObject("citizens").get("importId").getAsString();
        assertEquals(6, staged.getAsJsonObject("reports").getAsJsonObject("citizens").get("records").getAsInt());
        assertFalse(staged.getAsJsonArray("lines").isEmpty());
        assertFalse(Files.exists(harness.root.resolve("npcs/citizens")), "staging installs nothing");

        String stale = "0".repeat(64);
        assertEquals("conflict", error(harness.send("POST", "/v1/imports/" + stale + "/apply", token, null)));

        assertEquals("citizens", staged.getAsJsonArray("commandNamespaces").get(0).getAsString(), "the runtime fixture has a command NPC");
        assertEquals("scope-missing", error(harness.send("POST", "/v1/imports/" + importId + "/apply", token, null)),
                "applying adds citizens to the command allow-list, which needs apply:commands");
        List<EditorScope> withCommands = new ArrayList<>(IMPORTER);
        withCommands.add(EditorScope.APPLY_COMMANDS);
        token = harness.token(EditorHarness.grant(withCommands));
        int before = harness.host.applied.size();
        JsonObject applied = ok(harness.send("POST", "/v1/imports/" + importId + "/apply", token, null));
        assertEquals("applied", applied.get("state").getAsString());
        assertEquals(6, applied.get("npcs").getAsInt());
        assertTrue(harness.host.applied.size() > before, "the platform reloads after apply");
        assertFalse(applied.has("pending"), "nothing waits once applied: " + applied);
        assertTrue(harness.npcs.all().stream().anyMatch((art.arcane.animus.core.npc.NpcDefinition npc) -> npc.imported() && npc.spawned()), "imported NPCs spawn on apply");
        assertEquals("conflict", error(upload("?file=citizens", save(), EditorRequest.Transport.DIRECT)), "no upload over an applied import");

        assertEquals(404, harness.send("POST", "/v1/imports/" + importId + "/accept", token, new JsonObject()).status(), "there is no accept step");

        JsonObject rolled = ok(harness.send("POST", "/v1/imports/" + importId + "/rollback", token, null));
        assertEquals("rolled-back", rolled.get("state").getAsString());
        assertTrue(harness.host.rollbacks.contains(true));
        List<String> events = new ArrayList<>();
        Files.list(harness.root.resolve("state/editor/audit")).forEach((Path file) -> {
            try { events.add(Files.readString(file)); } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        });
        String audit = String.join("\n", events);
        for (String event : List.of("import-upload", "import-stage", "import-apply", "import-rollback")) assertTrue(audit.contains("\"" + event + "\""), event);
    }

    @Test
    void import_refusesBadInputScopesAndGroups() throws Exception {
        assertEquals("invalid-request", error(upload("?file=nope", save(), EditorRequest.Transport.DIRECT)));
        assertEquals("invalid-request", error(upload("?file=dtltraders&name=../x.yml", save(), EditorRequest.Transport.DIRECT)));
        assertEquals("invalid-request", error(upload("?file=citizens&name=x.yml", save(), EditorRequest.Transport.DIRECT)));
        ok(upload("?file=dtltraders&name=shops/baker/baker.yml", "x: 1\n".getBytes(), EditorRequest.Transport.DIRECT));
        assertTrue(Files.isRegularFile(harness.root.resolve("imports/dtltraders/shops/baker/baker.yml")));
        JsonObject mode = new JsonObject();
        mode.addProperty("mode", "duplicate");
        assertEquals("invalid-request", error(harness.send("POST", "/v1/imports/stage", token, mode)), "duplicate needs a label");
        assertEquals("invalid-request", error(harness.send("POST", "/v1/imports/stage", token, new JsonObject())), "nothing to stage");

        String reader = harness.token(EditorHarness.grant(List.of(EditorScope.READ)));
        assertEquals("scope-missing", error(harness.send("GET", "/v1/imports", reader, null)));
        String grouped = harness.token(new EditorTokens.Grant("", Optional.of(EditorHarness.ADMIN), IMPORTER, List.of("town"), Duration.ofDays(1)));
        assertEquals("group-denied", error(harness.send("POST", "/v1/imports/stage", grouped, new JsonObject())));
    }

    @Test
    void import_whileCitizensRuns_refusesApply() throws Exception {
        ok(upload("", save(), EditorRequest.Transport.DIRECT));
        String importId = ok(harness.send("POST", "/v1/imports/stage", token, new JsonObject())).getAsJsonObject("citizens").get("importId").getAsString();
        harness.citizensRunning = true;
        List<EditorScope> withCommands = new ArrayList<>(IMPORTER);
        withCommands.add(EditorScope.APPLY_COMMANDS);
        EditorResponse refused = harness.send("POST", "/v1/imports/" + importId + "/apply", harness.token(EditorHarness.grant(withCommands)), null);
        assertEquals("conflict", error(refused));
        assertTrue(EditorHarness.json(refused).getAsJsonObject("error").get("message").getAsString().contains("Citizens is still enabled"));
    }
}
