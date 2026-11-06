package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** In-game notices for what the editor API changes on the server, over a copy of schema/fixtures/export. */
class EditorNoticesApiTest {
    @TempDir Path directory;
    private EditorHarness harness;

    @BeforeEach void open() throws Exception {
        harness = new EditorHarness(directory);
        // The shared export fixture holds an NPC that does not parse; a registry with it refuses to spawn or import.
        Files.delete(harness.root.resolve("npcs/examples/broken.json"));
        harness.npcs.reload();
    }

    @AfterEach void close() throws Exception { harness.close(); }

    private JsonObject greeter(String token) throws Exception { return EditorHarness.json(harness.send("GET", "/v1/documents/npc/examples/greeter", token, null)); }

    private static JsonObject rename(String key, JsonObject current, String name) {
        JsonObject source = current.getAsJsonObject("source").deepCopy();
        source.addProperty("name", name);
        JsonObject change = new JsonObject();
        change.addProperty("kind", "npc");
        change.addProperty("id", "examples/greeter");
        change.add("baseRevision", current.get("revision"));
        change.add("baseHash", current.get("hash"));
        change.add("source", source);
        JsonArray documents = new JsonArray();
        documents.add(change);
        JsonObject set = new JsonObject();
        set.addProperty("idempotencyKey", key);
        set.add("documents", documents);
        return set;
    }

    @Test
    void pairApplyAndUndo_eachNotifyAfterTheReload() throws Exception {
        String token = harness.token();
        assertEquals(List.of("[Animus]: Brian's editor paired"), harness.flushNotices());
        JsonObject current = greeter(token);
        EditorResponse applied = harness.send("POST", "/v1/changes", token, rename("key-0000000000000001", current, "Host"));
        String changeId = EditorHarness.json(applied).get("changeId").getAsString();
        assertEquals(List.of("[Animus]: Brian's editor applied 1 NPC (tx " + changeId.substring(0, 8) + ")"), harness.flushNotices());
        EditorNotice notice = harness.host.notices.getLast();
        assertEquals(Optional.of(EditorHarness.ADMIN), notice.owner());
        assertEquals(Optional.of("/animus rollback " + changeId), notice.chat().orElseThrow().suggest());
        assertEquals(changeId, notice.chat().orElseThrow().copy().orElseThrow().value());
        assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.APPLY), notice.sound().orElseThrow());

        JsonObject undo = new JsonObject();
        undo.addProperty("idempotencyKey", "key-0000000000000002");
        EditorResponse undone = harness.send("POST", "/v1/changes/" + changeId + "/undo", token, undo);
        assertEquals(200, undone.status(), () -> undone.body().toString());
        String undoId = EditorHarness.json(undone).get("changeId").getAsString();
        assertEquals(List.of("[Animus]: Brian's editor undid 1 NPC (tx " + undoId.substring(0, 8) + ")"), harness.flushNotices());
        assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.ROLLBACK), harness.host.notices.getLast().sound().orElseThrow());
    }

    @Test
    void replayedApply_isNotAnnouncedTwice() throws Exception {
        String token = harness.token();
        harness.flushNotices();
        JsonObject body = rename("key-0000000000000003", greeter(token), "Once");
        harness.send("POST", "/v1/changes", token, body);
        harness.send("POST", "/v1/changes", token, body);
        assertEquals(1, harness.flushNotices().size());
    }

    @Test
    void reloadProblems_reportTheRealOutcome() throws Exception {
        String token = harness.token();
        harness.flushNotices();
        harness.host.reloadProblems = List.of("npcs/examples/greeter.json: unknown world");
        harness.send("POST", "/v1/changes", token, rename("key-0000000000000004", greeter(token), "Lost"));
        List<String> lines = harness.flushNotices();
        assertTrue(lines.getFirst().contains("; 1 problem (tx "), lines.toString());
        assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.REFUSED), harness.host.notices.getLast().sound().orElseThrow());
    }

    @Test
    void refusedApplies_dingLow() throws Exception {
        String token = harness.token();
        harness.flushNotices();
        JsonObject stale = greeter(token);
        harness.send("POST", "/v1/changes", token, rename("key-0000000000000005", stale, "First"));
        harness.flushNotices();
        assertEquals(409, harness.send("POST", "/v1/changes", token, rename("key-0000000000000006", stale, "Second")).status());
        assertEquals(List.of("[Animus]: Brian's editor apply refused: conflict"), harness.flushNotices());
        assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.REFUSED), harness.host.notices.getLast().sound().orElseThrow());

        String reader = harness.token(EditorHarness.grant(List.of(EditorScope.READ, EditorScope.APPLY_GUI)));
        harness.flushNotices();
        assertEquals(403, harness.send("POST", "/v1/changes", reader, rename("key-0000000000000007", greeter(reader), "Nope")).status());
        assertEquals(List.of("[Animus]: Brian's editor apply refused: scope-missing"), harness.flushNotices());
    }

    @Test
    void spawnAndDespawn_andARefusedSpawn() throws Exception {
        String token = harness.token(EditorHarness.grant(List.of(EditorScope.READ, EditorScope.SPAWN)));
        harness.flushNotices();
        assertEquals(200, harness.send("POST", "/v1/npcs/examples%2Fgreeter/despawn", token, null).status());
        assertEquals(List.of("[Animus]: Brian's editor despawned examples/greeter"), harness.flushNotices());
        harness.host.activation = List.of("unknown world");
        harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", token, null);
        assertEquals(List.of("[Animus]: Brian's editor spawn refused: invalid-request"), harness.flushNotices());
        harness.host.activation = List.of();
        harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", token, null);
        assertEquals(List.of("[Animus]: Brian's editor spawned examples/greeter"), harness.flushNotices());
    }

    @Test
    void revokes_sayWhy() throws Exception {
        String token = harness.token();
        String id = token.substring(4, token.indexOf('.'));
        harness.flushNotices();
        harness.api.revoke(id, "token-revoked");
        assertEquals(List.of("[Animus]: Brian's editor was revoked by command"), harness.flushNotices());
        assertEquals(Optional.of(EditorHarness.ADMIN), harness.host.notices.getLast().owner(), "the owner hears the revoke too");

        harness.token();
        harness.flushNotices();
        harness.host.admin = EditorHost.Admin.NOT_ADMIN;
        harness.api.recheckAdmins();
        harness.lane.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(List.of("[Animus]: Brian's editor was revoked after losing animus.admin"), harness.flushNotices());
    }

    @Test
    void stagedImportApplied_isOneNotice() throws Exception {
        List<EditorScope> importer = List.of(EditorScope.READ, EditorScope.IMPORT, EditorScope.APPLY_NPCS, EditorScope.APPLY_BEHAVIORS, EditorScope.APPLY_GUI);
        String token = harness.token(EditorHarness.grant(importer));
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Origin", EditorHarness.ORIGIN);
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Animus-Counter", Long.toString(harness.nextCounter()));
        headers.put(EditorReplies.PEER_HEADER, "127.0.0.1");
        byte[] save = Files.readAllBytes(SharedFixtures.fixture("citizens/runtime.yml"));
        harness.request(new art.arcane.animus.core.editor.transport.EditorRequest(art.arcane.animus.core.editor.transport.EditorRequest.Transport.DIRECT, "POST",
                "/v1/imports?file=citizens", headers, Optional.of(new com.google.gson.JsonPrimitive(java.util.Base64.getEncoder().encodeToString(save)))));
        String importId = EditorHarness.json(harness.send("POST", "/v1/imports/stage", token, new JsonObject())).getAsJsonObject("citizens").get("importId").getAsString();
        harness.flushNotices();
        assertEquals(200, harness.send("POST", "/v1/imports/" + importId + "/apply", token, null).status());
        List<String> lines = harness.flushNotices();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().startsWith("[Animus]: Brian's editor applied an import of 6 NPCs"), lines.toString());
        assertEquals(EditorNotifySettings.DEFAULT.sound(EditorNotifySettings.Kind.IMPORT), harness.host.notices.getLast().sound().orElseThrow());
    }
}
