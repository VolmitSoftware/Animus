package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API over a copy of schema/fixtures/export, with every response checked against editor-v1.schema.json. */
class EditorApiTest {
    private static final String GREETER = "npcs/examples/greeter.json";
    @TempDir Path directory;
    private EditorHarness harness;
    private SchemaValidator schema;

    @BeforeEach void open() throws Exception {
        harness = new EditorHarness(directory);
        schema = EditorMessageContractTest.validator();
    }

    @AfterEach void close() throws Exception { harness.close(); }

    private JsonObject valid(String definition, EditorResponse response) {
        JsonObject body = EditorHarness.json(response);
        List<String> problems = schema.check(definition, body);
        assertTrue(problems.isEmpty(), () -> definition + " " + problems + " in " + body);
        return body;
    }

    private void error(String code, int status, EditorResponse response) {
        assertEquals(status, response.status(), () -> response.body().toString());
        assertEquals(code, valid("error-response", response).getAsJsonObject("error").get("code").getAsString());
    }

    private JsonObject greeter() throws Exception { return EditorHarness.json(harness.send("GET", "/v1/documents/npc/examples/greeter", harness.token(), null)); }

    private static JsonObject change(String kind, String id, JsonObject current, JsonObject source) {
        JsonObject change = new JsonObject();
        change.addProperty("kind", kind);
        change.addProperty("id", id);
        if (current == null) { change.add("baseRevision", com.google.gson.JsonNull.INSTANCE); change.add("baseHash", com.google.gson.JsonNull.INSTANCE); }
        else { change.add("baseRevision", current.get("revision")); change.add("baseHash", current.get("hash")); }
        if (source == null) change.addProperty("delete", true); else change.add("source", source);
        return change;
    }

    private static JsonObject set(String key, JsonObject... changes) {
        JsonObject set = new JsonObject();
        set.addProperty("idempotencyKey", key);
        JsonArray documents = new JsonArray();
        for (JsonObject change : changes) documents.add(change);
        set.add("documents", documents);
        return set;
    }

    private static JsonObject renamed(JsonObject document, String name) {
        JsonObject source = document.getAsJsonObject("source").deepCopy();
        source.addProperty("name", name);
        return source;
    }

    @Test
    void hello_withoutToken_answersServerIdentity() throws Exception {
        JsonObject body = valid("hello-response", harness.send("GET", "/v1/hello", null, null));
        assertEquals(harness.api.parts().identity().fingerprint(), body.get("fingerprint").getAsString());
        assertEquals("paper", body.get("platform").getAsString());
    }

    @Test
    void pair_secretUsedOnce_issuesTokenThenRefuses() throws Exception {
        EditorTokens.Pairing pairing = harness.api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        body.addProperty("client", "Firefox");
        JsonObject paired = valid("pair-response", harness.send("POST", "/v1/pair", null, body, false));
        assertEquals(pairing.tokenId(), paired.get("tokenId").getAsString());
        error("pairing-invalid", 401, harness.send("POST", "/v1/pair", null, body, false));
        String tokens = harness.read(EditorTokens.PATH);
        assertFalse(tokens.contains(paired.get("token").getAsString().substring(20)), "the token secret is never stored");
    }

    @Test
    void pair_afterTenMinutes_isExpired() throws Exception {
        EditorTokens.Pairing pairing = harness.api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        harness.clock.advance(Duration.ofMinutes(11));
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        error("pairing-expired", 410, harness.send("POST", "/v1/pair", null, body, false));
    }

    @Test
    void pair_tooManyAttempts_isRateLimited() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", "tok_unknown1");
        body.addProperty("secret", "ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM");
        for (int attempt = 0; attempt < harness.settings.pairAttemptsPerMinute(); attempt++)
            error("pairing-invalid", 401, harness.send("POST", "/v1/pair", null, body, false));
        error("rate-limited", 429, harness.send("POST", "/v1/pair", null, body, false));
    }

    @Test
    void request_withoutOrWithBadToken_isUnauthorized() throws Exception {
        error("unauthorized", 401, harness.send("GET", "/v1/catalog", null, null));
        error("unauthorized", 401, harness.send("GET", "/v1/catalog", "anm_tok_nothere1.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM", null));
    }

    @Test
    void request_expiredOrRevokedToken_saysWhy() throws Exception {
        String token = harness.token();
        String id = token.substring(4, token.indexOf('.'));
        harness.api.revoke(id, "token-revoked");
        error("token-revoked", 401, harness.send("GET", "/v1/catalog", token, null));
        String other = harness.token();
        harness.clock.advance(Duration.ofDays(8));
        error("token-expired", 401, harness.send("GET", "/v1/catalog", other, null));
    }

    @Test
    void request_queuedWhenTheTokenIsRevoked_neitherWritesNorReads() throws Exception {
        String token = harness.token();
        String before = harness.read(GREETER);
        JsonObject current = greeter();
        CountDownLatch hold = new CountDownLatch(1);
        harness.lane.execute(() -> {
            try { hold.await(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
        CompletableFuture<EditorResponse> apply = harness.api.handle(harness.request("POST", "/v1/changes", token, set("key-0000000000000031",
                change("npc", "examples/greeter", current, renamed(current, "Late"))))).toCompletableFuture();
        CompletableFuture<EditorResponse> read = harness.api.handle(harness.request("GET", "/v1/documents/npc/examples/greeter", token, null)).toCompletableFuture();
        harness.api.revoke(token.substring(4, token.indexOf('.')), "token-revoked");
        hold.countDown();
        error("token-revoked", 401, apply.get(5, TimeUnit.SECONDS));
        error("token-revoked", 401, read.get(5, TimeUnit.SECONDS));
        assertEquals(before, harness.read(GREETER), "a revoked session's queued apply writes nothing");
        assertTrue(harness.host.applied.isEmpty());
    }

    @Test
    void request_queuedWhenTheTokenExpires_isRefused() throws Exception {
        String token = harness.token();
        CountDownLatch hold = new CountDownLatch(1);
        harness.lane.execute(() -> {
            try { hold.await(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
        CompletableFuture<EditorResponse> read = harness.api.handle(harness.request("GET", "/v1/catalog", token, null)).toCompletableFuture();
        harness.clock.advance(Duration.ofDays(8));
        hold.countDown();
        error("token-expired", 401, read.get(5, TimeUnit.SECONDS));
    }

    @Test
    void apply_twoSessionsOnOneDocument_secondIsConflictNotOverwrite() throws Exception {
        String first = harness.token(), second = harness.token();
        JsonObject base = greeter();
        assertEquals(200, harness.send("POST", "/v1/changes", first, set("key-0000000000000032", change("npc", "examples/greeter", base, renamed(base, "Mine")))).status());
        EditorResponse late = harness.send("POST", "/v1/changes", second, set("key-0000000000000033", change("npc", "examples/greeter", base, renamed(base, "Theirs"))));
        assertEquals(409, late.status());
        JsonObject conflict = valid("change-conflict", late).getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals(base.get("revision").getAsLong(), conflict.get("baseRevision").getAsLong());
        assertEquals(2, conflict.getAsJsonObject("current").get("revision").getAsLong());
        assertEquals("Mine", conflict.getAsJsonObject("current").getAsJsonObject("source").get("name").getAsString());
        assertTrue(harness.read(GREETER).contains("\"Mine\""));
    }

    @Test
    void apply_afterAnInGameCommandEdit_isConflict_andAStaleCommandIsRefusedToo() throws Exception {
        String token = harness.token();
        JsonObject opened = greeter();
        NpcRepository.Draft draft = harness.npcs.draft("greeter");
        JsonObject commanded = draft.document();
        commanded.addProperty("name", "Commanded");
        harness.npcs.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), commanded));
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000034", change("npc", "examples/greeter", opened, renamed(opened, "Edited"))));
        assertEquals(409, response.status());
        assertEquals("Commanded", valid("change-conflict", response).getAsJsonArray("conflicts").get(0).getAsJsonObject()
                .getAsJsonObject("current").getAsJsonObject("source").get("name").getAsString());

        NpcRepository.Draft stale = harness.npcs.draft("greeter");
        JsonObject fresh = greeter();
        assertEquals(200, harness.send("POST", "/v1/changes", token, set("key-0000000000000035", change("npc", "examples/greeter", fresh, renamed(fresh, "Editor")))).status());
        JsonObject old = stale.document();
        old.addProperty("name", "Old command");
        java.io.IOException refused = assertThrows(java.io.IOException.class,
                () -> harness.npcs.apply(new NpcRepository.Draft(stale.path(), stale.expectedHash(), stale.expectedRevision(), old)));
        assertTrue(refused.getMessage().contains("Conflict"), refused.getMessage());
        assertTrue(harness.read(GREETER).contains("\"Editor\""), "the command never overwrites the editor's apply");
    }

    @Test
    void request_foreignOrigin_isRefusedAndAllowedOriginGetsCors() throws Exception {
        String token = harness.token();
        Map<String, String> headers = new LinkedHashMap<>(Map.of("Origin", "https://evil.example", "Authorization", "Bearer " + token));
        error("origin-not-allowed", 403, harness.request(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/catalog", headers, Optional.empty())));
        EditorResponse allowed = harness.send("GET", "/v1/catalog", token, null);
        assertEquals(EditorHarness.ORIGIN, allowed.headers().get("Access-Control-Allow-Origin"));
        headers.put("Origin", "https://evil.example");
        EditorResponse relayed = harness.request(new EditorRequest(EditorRequest.Transport.RELAY, "GET", "/v1/catalog", headers, Optional.empty()));
        assertEquals(200, relayed.status(), "the relay checks origins on its own handshake");
    }

    @Test
    void request_missingScope_isRefused() throws Exception {
        String token = harness.token(EditorHarness.grant(List.of(EditorScope.READ)));
        error("scope-missing", 403, harness.send("POST", "/v1/validate", token, set("6f1c2d3e4f5a6b7c", change("npc", "examples/greeter", null, new JsonObject()))));
        JsonObject current = greeter();
        error("scope-missing", 403, harness.send("POST", "/v1/changes", token, set("6f1c2d3e4f5a6b7c", change("npc", "examples/greeter", current, renamed(current, "x")))));
    }

    @Test
    void request_replayedCounter_isRefused() throws Exception {
        String token = harness.token();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + token, "X-Animus-Counter", "5");
        JsonObject body = new JsonObject();
        body.add("documents", new JsonArray());
        EditorRequest request = new EditorRequest(EditorRequest.Transport.RELAY, "POST", "/v1/validate", headers, Optional.of(body));
        error("invalid-request", 400, harness.request(request));
        error("counter-replayed", 409, harness.request(request));
    }

    @Test
    void request_overRate_isLimited() throws Exception {
        EditorSettings slow = new EditorSettings(true, EditorSettings.DEFAULT.editorUrl(), false, "127.0.0.1", 9797, "", "", "", EditorSettings.DEFAULT.allowedOrigins(),
                7, 30, 4096, 16384, 512, 8, 1, 10, 64, 20, 4, 64, 15, 24, art.arcane.animus.core.config.EditorNotifySettings.DEFAULT);
        try (EditorHarness limited = new EditorHarness(directory.resolve("slow"), slow)) {
            String token = limited.token();
            assertEquals(200, limited.send("GET", "/v1/capabilities", token, null).status());
            assertEquals(200, limited.send("GET", "/v1/capabilities", token, null).status());
            error("rate-limited", 429, limited.send("GET", "/v1/capabilities", token, null));
        }
    }

    @Test
    void request_fullFileLane_answersBusy() throws Exception {
        String token = harness.token();
        harness.host.rejectFiles = true;
        EditorResponse response = harness.send("GET", "/v1/catalog", token, null);
        error("busy", 503, response);
        assertTrue(EditorHarness.json(response).getAsJsonObject("error").has("retryAfterMillis"));
    }

    @Test
    void capabilitiesAndSchemas_matchTheirMessages() throws Exception {
        String token = harness.token();
        JsonObject capabilities = valid("capabilities-response", harness.send("GET", "/v1/capabilities", token, null));
        assertFalse(capabilities.getAsJsonObject("features").getAsJsonObject("combat").get("available").getAsBoolean());
        JsonObject schemas = valid("schemas-response", harness.send("GET", "/v1/schemas", token, null)).getAsJsonObject("schemas");
        for (String name : List.of("npc", "behavior", "pack", "completions")) assertTrue(schemas.has(name), name);
    }

    @Test
    void catalog_exportFixture_listsExpectedDocuments() throws Exception {
        JsonObject body = valid("catalog-response", harness.send("GET", "/v1/catalog", harness.token(), null));
        Set<String> listed = new HashSet<>();
        for (JsonElement element : body.getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            listed.add(entry.get("kind").getAsString() + " " + entry.get("id").getAsString() + " " + entry.get("path").getAsString());
            assertEquals("sha256:" + JsonDocuments.hash(harness.read(entry.get("path").getAsString())), entry.get("hash").getAsString());
        }
        Set<String> expected = new HashSet<>();
        for (JsonElement element : SharedFixtures.object(SharedFixtures.fixture("export.expected.json")).getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            expected.add(entry.get("kind").getAsString() + " " + entry.get("id").getAsString() + " " + entry.get("path").getAsString());
        }
        assertEquals(expected, listed);
        assertTrue(body.get("next").isJsonNull());
    }

    @Test
    void catalog_groupLimitedToken_hidesOtherGroups() throws Exception {
        String token = harness.token(new EditorTokens.Grant("", Optional.of(EditorHarness.ADMIN), EditorScope.DEFAULT_PAIRING, List.of("town"), Duration.ofDays(1)));
        JsonObject body = valid("catalog-response", harness.send("GET", "/v1/catalog?kind=npc", token, null));
        assertEquals(0, body.getAsJsonArray("documents").size());
        error("group-denied", 403, harness.send("GET", "/v1/documents/npc/examples/greeter", token, null));
    }

    @Test
    void document_existingAndMissing() throws Exception {
        JsonObject body = valid("document-response", harness.send("GET", "/v1/documents/npc/examples/greeter", harness.token(), null));
        assertEquals(1, body.get("revision").getAsLong());
        assertEquals("Greeter", body.getAsJsonObject("source").get("name").getAsString());
        error("not-found", 404, harness.send("GET", "/v1/documents/npc/examples/nobody", harness.token(), null));
        JsonObject behavior = valid("document-response", harness.send("GET", "/v1/documents/behavior/shop:keeper", harness.token(), null));
        assertEquals("packs/shop/behaviors/shop/keeper.json", behavior.get("path").getAsString());
    }

    @Test
    void validate_reportsDiagnosticsAndWritesNothing() throws Exception {
        String before = harness.read(GREETER);
        JsonObject current = greeter();
        JsonObject broken = renamed(current, "x");
        broken.addProperty("type", 5);
        JsonObject body = new JsonObject();
        JsonArray documents = new JsonArray();
        documents.add(change("npc", "examples/greeter", current, broken));
        body.add("documents", documents);
        JsonObject result = valid("validate-response", harness.send("POST", "/v1/validate", harness.token(), body));
        assertFalse(result.get("valid").getAsBoolean());
        assertFalse(result.getAsJsonArray("documents").get(0).getAsJsonObject().getAsJsonArray("diagnostics").isEmpty());
        assertEquals(before, harness.read(GREETER));
    }

    @Test
    void apply_edit_writesThroughJournalAndReplaysByKey() throws Exception {
        String token = harness.token();
        JsonObject current = greeter();
        JsonObject body = set("key-0000000000000001", change("npc", "examples/greeter", current, renamed(current, "Host")));
        EditorResponse response = harness.send("POST", "/v1/changes", token, body);
        assertEquals(200, response.status());
        JsonObject applied = valid("change-applied", response);
        JsonObject version = applied.getAsJsonArray("documents").get(0).getAsJsonObject();
        assertEquals(2, version.get("revision").getAsLong());
        String stored = harness.read(GREETER);
        assertEquals("sha256:" + JsonDocuments.hash(stored), version.get("hash").getAsString());
        assertTrue(stored.contains("\"Host\""));
        assertNotNull(harness.read("backups/" + applied.get("changeId").getAsString() + ".json"), "the change id names its rollback receipt");
        assertEquals(List.of(Set.of(DocumentKind.NPC)), harness.host.applied);
        JsonObject status = valid("change-status-response", harness.send("GET", "/v1/changes/key-0000000000000001", token, null));
        assertEquals("applied", status.get("status").getAsString());
        assertEquals(applied, harness.json(harness.send("POST", "/v1/changes", token, body)), "a retry answers the stored result");
        assertEquals(1, harness.host.applied.size(), "a retry writes nothing");
        JsonObject other = set("key-0000000000000001", change("npc", "examples/greeter", greeter(), renamed(greeter(), "Else")));
        error("invalid-request", 400, harness.send("POST", "/v1/changes", token, other));
        assertEquals("unknown", valid("change-status-response", harness.send("GET", "/v1/changes/key-0000000000000009", token, null)).get("status").getAsString());
    }

    @Test
    void apply_staleBase_isConflictWithServerVersionAndAllOrNothing() throws Exception {
        String token = harness.token();
        JsonObject stale = greeter();
        assertEquals(200, harness.send("POST", "/v1/changes", token, set("key-0000000000000001", change("npc", "examples/greeter", stale, renamed(stale, "First")))).status());
        String shopBefore = harness.read("shops/shop/bakery.json");
        JsonObject shop = EditorHarness.json(harness.send("GET", "/v1/documents/shop/shop:bakery", token, null));
        JsonObject shopSource = shop.getAsJsonObject("source").deepCopy();
        shopSource.addProperty("title", "&6Changed");
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000002",
                change("shop", "shop:bakery", shop, shopSource), change("npc", "examples/greeter", stale, renamed(stale, "Second"))));
        assertEquals(409, response.status());
        JsonObject conflict = valid("change-conflict", response).getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals("First", conflict.getAsJsonObject("current").getAsJsonObject("source").get("name").getAsString());
        assertEquals(shopBefore, harness.read("shops/shop/bakery.json"), "nothing is written when any document conflicts");
        assertEquals("conflict", EditorHarness.json(harness.send("GET", "/v1/changes/key-0000000000000002", token, null)).get("status").getAsString());
    }

    @Test
    void apply_invalidDocument_isRefusedWithDiagnostics() throws Exception {
        JsonObject current = greeter();
        JsonObject broken = renamed(current, "x");
        broken.remove("position");
        EditorResponse response = harness.send("POST", "/v1/changes", harness.token(), set("key-0000000000000003", change("npc", "examples/greeter", current, broken)));
        assertEquals(422, response.status());
        valid("change-invalid", response);
        assertTrue(harness.host.applied.isEmpty());
    }

    @Test
    void apply_createAndDelete_thenUndoRestores() throws Exception {
        String token = harness.token();
        JsonObject created = greeter().getAsJsonObject("source").deepCopy();
        created.addProperty("id", "host");
        created.addProperty("uuid", "11111111-2222-3333-4444-555555555555");
        JsonObject shop = EditorHarness.json(harness.send("GET", "/v1/documents/shop/shop:bakery", token, null));
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000004",
                change("npc", "examples/host", null, created), change("shop", "shop:bakery", shop, null)));
        JsonObject applied = valid("change-applied", response);
        assertNotNull(harness.read("npcs/examples/host.json"));
        assertNull(harness.read("shops/shop/bakery.json"));
        JsonObject undo = new JsonObject();
        undo.addProperty("idempotencyKey", "key-0000000000000005");
        EditorResponse undone = harness.send("POST", "/v1/changes/" + applied.get("changeId").getAsString() + "/undo", token, undo);
        assertEquals(200, undone.status(), () -> undone.body().toString());
        valid("change-applied", undone);
        assertNull(harness.read("npcs/examples/host.json"));
        assertNotNull(harness.read("shops/shop/bakery.json"));
        assertTrue(harness.host.rollbacks.getLast());
        undo.addProperty("idempotencyKey", "key-0000000000000006");
        EditorResponse again = harness.send("POST", "/v1/changes/" + applied.get("changeId").getAsString() + "/undo", token, undo);
        assertEquals(409, again.status(), "a second undo would overwrite the first one's result");
        valid("change-conflict", again);
    }

    @Test
    void apply_recording_isStoredInItsSerializedForm() throws Exception {
        JsonObject source = art.arcane.animus.core.storage.JsonDocuments.object(SharedFixtures.text(SharedFixtures.fixture("recordings/roundtrip-input.json")));
        EditorResponse response = harness.send("POST", "/v1/changes", harness.token(), set("key-0000000000000031", change("recording", "market-loop", null, source)));
        valid("change-applied", response);
        assertEquals(SharedFixtures.text(SharedFixtures.fixture("recordings/market-loop.json")), harness.read("recordings/market-loop.json"));
        JsonObject broken = source.deepCopy();
        broken.addProperty("durationTicks", 81);
        JsonObject current = EditorHarness.json(harness.send("GET", "/v1/documents/recording/market-loop", harness.token(), null));
        EditorResponse refused = harness.send("POST", "/v1/changes", harness.token(), set("key-0000000000000032", change("recording", "market-loop", current, broken)));
        assertEquals(422, refused.status());
        valid("change-invalid", refused);
    }

    @Test
    void apply_duplicateNpcId_isRefused() throws Exception {
        JsonObject created = greeter().getAsJsonObject("source").deepCopy();
        created.addProperty("group", "other");
        created.addProperty("uuid", "11111111-2222-3333-4444-555555555555");
        EditorResponse response = harness.send("POST", "/v1/changes", harness.token(), set("key-0000000000000007", change("npc", "other/greeter", null, created)));
        assertEquals(422, response.status());
    }

    /** A change that moves [current] (the document at its old id) to [id] with [source]. */
    private static JsonObject move(String id, String from, JsonObject current, JsonObject source) {
        JsonObject change = change("npc", id, current, source);
        JsonObject origin = new JsonObject();
        origin.addProperty("id", from);
        change.add("from", origin);
        return change;
    }

    private JsonObject movedGreeter() throws Exception {
        JsonObject source = greeter().getAsJsonObject("source").deepCopy();
        source.addProperty("group", "town/market");
        return source;
    }

    @Test
    void apply_move_writesNewPathRemovesOldAndKeepsTheRevisionChain() throws Exception {
        String token = harness.token();
        Frames sink = new Frames();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + token, "Origin", EditorHarness.ORIGIN);
        harness.api.openEvents(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/events", headers, Optional.empty()), sink).toCompletableFuture().get(5, TimeUnit.SECONDS);
        JsonObject current = greeter();
        long before = current.get("revision").getAsLong();
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000011", move("town/market/greeter", "examples/greeter", current, movedGreeter())));
        assertEquals(200, response.status(), () -> response.body().toString());
        JsonObject version = valid("change-applied", response).getAsJsonArray("documents").get(0).getAsJsonObject();
        assertEquals("town/market/greeter", version.get("id").getAsString());
        assertEquals("examples/greeter", version.getAsJsonObject("from").get("id").getAsString());
        assertEquals(before + 1, version.get("revision").getAsLong());
        assertNull(harness.read(GREETER));
        String stored = harness.read("npcs/town/market/greeter.json");
        assertNotNull(stored);
        assertEquals("sha256:" + JsonDocuments.hash(stored), version.get("hash").getAsString());
        for (int tick = 0; tick < 4; tick++) harness.api.parts().events().tick();
        JsonObject catalog = sink.frames.stream().filter((JsonObject frame) -> frame.get("type").getAsString().equals("catalog")).findFirst().orElseThrow();
        assertTrue(schema.check("event", catalog).isEmpty(), catalog::toString);
        assertEquals("town/market/greeter", catalog.getAsJsonArray("documents").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("examples/greeter", catalog.getAsJsonArray("deleted").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void apply_move_whenTheOldDocumentChanged_isConflictNamingTheOldId() throws Exception {
        String token = harness.token();
        JsonObject stale = greeter();
        assertEquals(200, harness.send("POST", "/v1/changes", token, set("key-0000000000000012", change("npc", "examples/greeter", stale, renamed(stale, "First")))).status());
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000013", move("town/market/greeter", "examples/greeter", stale, movedGreeter())));
        assertEquals(409, response.status());
        JsonObject conflict = valid("change-conflict", response).getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals("examples/greeter", conflict.get("id").getAsString());
        assertEquals("First", conflict.getAsJsonObject("current").getAsJsonObject("source").get("name").getAsString());
        assertNotNull(harness.read(GREETER));
        assertNull(harness.read("npcs/town/market/greeter.json"));
    }

    @Test
    void apply_move_ontoAnExistingDocument_isConflictNamingTheTarget() throws Exception {
        String token = harness.token();
        JsonObject current = greeter();
        JsonObject occupant = current.getAsJsonObject("source").deepCopy();
        occupant.addProperty("id", "keeper");
        occupant.addProperty("group", "town/market");
        occupant.addProperty("uuid", "11111111-2222-3333-4444-555555555555");
        assertEquals(200, harness.send("POST", "/v1/changes", token, set("key-0000000000000014", change("npc", "town/market/keeper", null, occupant))).status());
        JsonObject source = movedGreeter();
        source.addProperty("id", "keeper");
        EditorResponse response = harness.send("POST", "/v1/changes", token, set("key-0000000000000015", move("town/market/keeper", "examples/greeter", current, source)));
        assertEquals(409, response.status());
        JsonObject conflict = valid("change-conflict", response).getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals("town/market/keeper", conflict.get("id").getAsString());
        assertTrue(conflict.get("baseRevision").isJsonNull());
        assertNotNull(harness.read(GREETER));
    }

    @Test
    void undo_move_movesTheDocumentBack() throws Exception {
        String token = harness.token();
        String original = harness.read(GREETER);
        JsonObject applied = valid("change-applied", harness.send("POST", "/v1/changes", token, set("key-0000000000000016",
                move("town/market/greeter", "examples/greeter", greeter(), movedGreeter()))));
        JsonObject undo = new JsonObject();
        undo.addProperty("idempotencyKey", "key-0000000000000017");
        EditorResponse undone = harness.send("POST", "/v1/changes/" + applied.get("changeId").getAsString() + "/undo", token, undo);
        assertEquals(200, undone.status(), () -> undone.body().toString());
        JsonArray documents = valid("change-applied", undone).getAsJsonArray("documents");
        assertEquals(1, documents.size());
        JsonObject back = documents.get(0).getAsJsonObject();
        assertEquals("examples/greeter", back.get("id").getAsString());
        assertEquals("town/market/greeter", back.getAsJsonObject("from").get("id").getAsString());
        assertEquals(original, harness.read(GREETER));
        assertNull(harness.read("npcs/town/market/greeter.json"));
        assertTrue(harness.host.rollbacks.getLast());
    }

    @Test
    void preview_refusedCapability_consoleToken_andRunningPreview() throws Exception {
        String token = harness.token();
        JsonObject behavior = EditorHarness.json(harness.send("GET", "/v1/documents/behavior/greeter:greeter", token, null));
        JsonObject commanding = behavior.getAsJsonObject("source").deepCopy();
        JsonObject step = new JsonObject();
        step.addProperty("type", "action");
        step.addProperty("capability", "animus:command");
        step.add("arguments", new JsonObject());
        commanding.getAsJsonObject("routines").getAsJsonObject("main").getAsJsonArray("steps").add(step);
        JsonObject request = new JsonObject();
        JsonArray documents = new JsonArray();
        documents.add(change("behavior", "greeter:greeter", behavior, commanding));
        request.add("documents", documents);
        error("preview-refused", 422, harness.send("POST", "/v1/previews", token, request));

        String console = harness.token(new EditorTokens.Grant("", Optional.empty(), EditorScope.DEFAULT_PAIRING, List.of(), Duration.ofDays(1)));
        JsonObject current = greeter();
        JsonObject npcPreview = new JsonObject();
        JsonArray npcs = new JsonArray();
        npcs.add(change("npc", "examples/greeter", current, renamed(current, "Preview")));
        npcPreview.add("documents", npcs);
        error("player-offline", 409, harness.send("POST", "/v1/previews", console, npcPreview));

        String before = harness.read(GREETER);
        JsonObject running = valid("preview-response", harness.send("POST", "/v1/previews", token, npcPreview));
        assertEquals(List.of("examples/greeter"), List.of(running.getAsJsonArray("npcs").get(0).getAsString()));
        assertEquals(before, harness.read(GREETER), "a preview writes nothing");
        assertEquals(204, harness.send("DELETE", "/v1/previews/" + running.get("previewId").getAsString(), token, null).status());
        assertEquals(List.of(running.get("previewId").getAsString()), harness.host.ended);
    }

    @Test
    void preview_listsTheLiveNpcsItHidesFromTheViewer() throws Exception {
        // The export fixture's broken NPC keeps the registry from loading; without it the greeter is live.
        Files.delete(harness.root.resolve("npcs/examples/broken.json"));
        assertEquals(List.of(), harness.npcs.reload());
        JsonObject current = greeter();
        JsonObject created = renamed(current, "Newcomer");
        created.addProperty("id", "newcomer");
        created.addProperty("uuid", "11111111-2222-3333-4444-555555555556");
        JsonObject request = new JsonObject();
        JsonArray documents = new JsonArray();
        documents.add(change("npc", "examples/greeter", current, renamed(current, "Preview")));
        documents.add(change("npc", "examples/newcomer", null, created));
        request.add("documents", documents);
        JsonObject running = valid("preview-response", harness.send("POST", "/v1/previews", harness.token(), request));
        List<String> hidden = new ArrayList<>();
        running.getAsJsonArray("hidden").forEach((JsonElement id) -> hidden.add(id.getAsString()));
        assertEquals(List.of("examples/greeter"), hidden, "only an existing live NPC is hidden; a new one has nothing to hide");
    }

    @Test
    void preview_expires() throws Exception {
        JsonObject current = greeter();
        JsonObject request = new JsonObject();
        JsonArray documents = new JsonArray();
        documents.add(change("npc", "examples/greeter", current, renamed(current, "Preview")));
        request.add("documents", documents);
        valid("preview-response", harness.send("POST", "/v1/previews", harness.token(), request));
        harness.clock.advance(Duration.ofMinutes(16));
        harness.api.housekeeping();
        assertEquals(1, harness.host.ended.size());
        assertEquals(0, harness.api.parts().previews().count());
    }

    /** A sink that records frames. */
    private static final class Frames implements EditorEventSink {
        final List<JsonObject> frames = new ArrayList<>();
        boolean closed;

        @Override public synchronized boolean send(JsonObject event) { frames.add(event); return !closed; }

        @Override public synchronized void close() { closed = true; }
    }

    @Test
    void events_receiveChangesAndCloseOnRevoke() throws Exception {
        String token = harness.token();
        Frames sink = new Frames();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + token, "Origin", EditorHarness.ORIGIN);
        EditorEventStream stream = harness.api.openEvents(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/events", headers, Optional.empty()), sink)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(stream.open());
        JsonObject current = greeter();
        assertEquals(200, harness.send("POST", "/v1/changes", token, set("key-0000000000000008", change("npc", "examples/greeter", current, renamed(current, "Evented")))).status());
        for (int tick = 0; tick < 4; tick++) harness.api.parts().events().tick();
        assertEquals(List.of("change", "catalog"), sink.frames.stream().map((JsonObject frame) -> frame.get("type").getAsString()).toList());
        for (JsonObject frame : sink.frames) assertTrue(schema.check("event", frame).isEmpty(), frame::toString);
        harness.api.revoke(token.substring(4, token.indexOf('.')), "token-revoked");
        assertEquals("closing", sink.frames.getLast().get("type").getAsString());
        assertTrue(schema.check("event", sink.frames.getLast()).isEmpty());
        assertTrue(sink.closed);
    }

    @Test
    void events_badToken_isRefused() throws Exception {
        EditorEventStream stream = harness.api.openEvents(new EditorRequest(EditorRequest.Transport.RELAY, "GET", "/v1/events", Map.of("Authorization", "Bearer nope"), Optional.empty()),
                new Frames()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(stream.open());
        error("unauthorized", 401, stream.response());
    }

    @Test
    void spawn_and_despawn_followCommandChecks() throws Exception {
        String token = harness.token(EditorHarness.grant(List.of(EditorScope.READ, EditorScope.SPAWN)));
        // The registry publishes nothing while any definition is broken, as on a live server.
        Files.delete(harness.root.resolve("npcs/examples/broken.json"));
        assertTrue(harness.npcs.reload().isEmpty());
        JsonObject despawned = valid("spawn-response", harness.send("POST", "/v1/npcs/examples%2Fgreeter/despawn", token, null));
        assertFalse(despawned.get("spawned").getAsBoolean());
        assertTrue(harness.read(GREETER).contains("\"spawned\": false"));
        harness.host.activation = List.of("unknown world");
        error("invalid-request", 400, harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", token, null));
        harness.host.activation = List.of();
        JsonObject spawned = valid("spawn-response", harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", token, null));
        assertTrue(spawned.get("spawned").getAsBoolean());
        assertTrue(harness.read(GREETER).contains("\"spawned\": true"));
    }

    @Test
    void audit_recordsPairingAndApply() throws Exception {
        String token = harness.token();
        JsonObject current = greeter();
        harness.send("POST", "/v1/changes", token, set("key-0000000000000010", change("npc", "examples/greeter", current, renamed(current, "Audited"))));
        Path day = harness.root.resolve(EditorAudit.DIRECTORY).resolve("2026-11-04.jsonl");
        List<String> lines = Files.readAllLines(day);
        assertTrue(lines.stream().anyMatch((String line) -> line.contains("\"event\":\"pair\"")));
        assertTrue(lines.stream().anyMatch((String line) -> line.contains("\"event\":\"apply\"") && line.contains("npc:examples/greeter")));
    }

    @Test
    void unknownProtocolAndEndpoint() throws Exception {
        error("not-found", 404, harness.send("GET", "/v1/nothing", harness.token(), null));
    }
}
