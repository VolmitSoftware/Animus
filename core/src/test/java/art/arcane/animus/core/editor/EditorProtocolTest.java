package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog summaries, batch reads, retry hints, the server name, sliding token expiry and the console lines, over the API. */
class EditorProtocolTest {
    @TempDir Path directory;
    private EditorHarness harness;
    private SchemaValidator schema;

    @BeforeEach void open() throws Exception {
        harness = new EditorHarness(directory.resolve("main"));
        schema = EditorMessageContractTest.validator();
    }

    @AfterEach void close() throws Exception { harness.close(); }

    private JsonObject valid(String definition, EditorResponse response) {
        assertEquals(200, response.status(), () -> response.body().toString());
        JsonObject body = EditorHarness.json(response);
        List<String> problems = schema.check(definition, body);
        assertTrue(problems.isEmpty(), () -> definition + " " + problems + " in " + body);
        return body;
    }

    private void error(String code, int status, EditorResponse response) {
        assertEquals(status, response.status(), () -> response.body().toString());
        JsonObject body = EditorHarness.json(response);
        assertTrue(schema.check("error-response", body).isEmpty(), body::toString);
        assertEquals(code, body.getAsJsonObject("error").get("code").getAsString());
    }

    private static JsonObject refs(String... kindAndId) {
        JsonArray refs = new JsonArray();
        for (int index = 0; index < kindAndId.length; index += 2) {
            JsonObject ref = new JsonObject();
            ref.addProperty("kind", kindAndId[index]);
            ref.addProperty("id", kindAndId[index + 1]);
            refs.add(ref);
        }
        JsonObject body = new JsonObject();
        body.add("refs", refs);
        return body;
    }

    /** A request on the relay path, as the relay client hands it to the API. */
    private EditorResponse relayed(EditorHarness on, String method, String target, String token, JsonObject body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Forwarded-For", "203.0.113.9");
        return on.request(new EditorRequest(EditorRequest.Transport.RELAY, method, target, headers, Optional.ofNullable(body)));
    }

    private static EditorSettings settings(int requestsPerSecond, String serverName) {
        JsonObject table = EditorSettings.DEFAULT.toJson();
        table.addProperty("requestsPerSecond", requestsPerSecond);
        table.addProperty("serverName", serverName);
        table.addProperty("listenerEnabled", false);
        return EditorSettings.parse(table);
    }

    @Test
    void catalog_everyEntryCarriesASummary_and1200DocumentsPageWithinTheBudget() throws Exception {
        int written = SyntheticStore.write(harness.root, 500, 400, 140, 110, 50);
        String token = harness.token();
        int entries = 0, pages = 0;
        String after = null;
        do {
            EditorResponse response = harness.send("GET", "/v1/catalog" + (after == null ? "" : "?after=" + after), token, null);
            JsonObject page = valid("catalog-response", response);
            assertTrue(page.toString().getBytes(StandardCharsets.UTF_8).length <= EditorDocumentRoutes.ANSWER_BYTES + 1024, "page bytes");
            for (JsonElement element : page.getAsJsonArray("documents")) {
                JsonObject entry = element.getAsJsonObject();
                assertTrue(entry.has("summary"), entry::toString);
                if (entry.get("id").getAsString().equals("world/citizens-7")) {
                    JsonObject summary = entry.getAsJsonObject("summary");
                    assertEquals("Town citizen 7", summary.get("name").getAsString());
                    assertEquals("world", summary.get("folder").getAsString());
                    assertEquals(List.of("route", "behavior"), summary.getAsJsonArray("features").asList().stream().map(JsonElement::getAsString).toList());
                }
            }
            entries += page.getAsJsonArray("documents").size();
            pages++;
            after = page.get("next").isJsonNull() ? null : page.get("next").getAsString();
        } while (after != null);
        assertTrue(entries >= written, entries + " entries");
        assertTrue(pages >= 2 && pages <= 4, pages + " pages");
        String edited = harness.read("npcs/world/citizens-7.json").replace("Town &6citizen 7", "Renamed");
        Files.writeString(harness.root.resolve("npcs/world/citizens-7.json"), edited);
        JsonObject npcs = valid("catalog-response", harness.send("GET", "/v1/catalog?kind=npc", token, null));
        boolean renamed = false;
        for (JsonElement element : npcs.getAsJsonArray("documents"))
            if (element.getAsJsonObject().get("id").getAsString().equals("world/citizens-7"))
                renamed = element.getAsJsonObject().getAsJsonObject("summary").get("name").getAsString().equals("Renamed");
        assertTrue(renamed, "a changed file is read again, not served from the cache");
    }

    @Test
    void documentsRead_answersInOrderWithPerRefErrors_andNeedsNoCounter() throws Exception {
        String token = harness.token();
        JsonObject body = refs("npc", "examples/shopkeeper", "npc", "examples/nobody", "nothing", "x", "npc", "Bad Id", "npc", "examples/greeter", "behavior", "shop:keeper");
        JsonObject answer = valid("documents-read-response", harness.send("POST", "/v1/documents/read", token, body, false));
        List<String> ids = answer.getAsJsonArray("documents").asList().stream().map((JsonElement element) -> element.getAsJsonObject().get("id").getAsString()).toList();
        assertEquals(List.of("examples/shopkeeper", "examples/greeter", "shop:keeper"), ids);
        for (JsonElement element : answer.getAsJsonArray("documents")) {
            JsonObject document = element.getAsJsonObject();
            assertTrue(document.has("source") && document.has("summary"), document::toString);
            assertEquals("sha256:" + JsonDocuments.hash(harness.read(document.get("path").getAsString())), document.get("hash").getAsString());
        }
        List<String> codes = answer.getAsJsonArray("errors").asList().stream().map((JsonElement element) -> element.getAsJsonObject().get("id").getAsString() + " " + element.getAsJsonObject().get("code").getAsString()).toList();
        assertEquals(List.of("examples/nobody not-found", "x invalid-request", "Bad Id invalid-request"), codes);
        assertEquals(0, answer.getAsJsonArray("remaining").size());
        JsonObject relay = valid("documents-read-response", relayed(harness, "POST", "/v1/documents/read", token, refs("npc", "examples/greeter")));
        assertEquals(1, relay.getAsJsonArray("documents").size(), "the relay path reads the same");
    }

    @Test
    void documentsRead_folderLimitedToken_reportsGroupDenied() throws Exception {
        String token = harness.token(new EditorTokens.Grant("", Optional.of(EditorHarness.ADMIN), EditorScope.DEFAULT_PAIRING, List.of("town"), Duration.ofDays(1)));
        JsonObject answer = valid("documents-read-response", harness.send("POST", "/v1/documents/read", token, refs("npc", "examples/greeter", "shop", "shop:bakery"), false));
        assertEquals("group-denied", answer.getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString());
        assertEquals("shop:bakery", answer.getAsJsonArray("documents").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void documentsRead_badRequests_areRefusedWhole() throws Exception {
        String token = harness.token();
        String[] many = new String[402];
        for (int index = 0; index < 201; index++) { many[index * 2] = "npc"; many[index * 2 + 1] = "town/n" + index; }
        error("invalid-request", 400, harness.send("POST", "/v1/documents/read", token, refs(many), false));
        error("invalid-request", 400, harness.send("POST", "/v1/documents/read", token, refs("npc", "examples/greeter", "npc", "examples/greeter"), false));
        error("invalid-request", 400, harness.send("POST", "/v1/documents/read", token, new JsonObject(), false));
        JsonObject extra = refs("npc", "examples/greeter");
        extra.addProperty("page", 2);
        error("invalid-request", 400, harness.send("POST", "/v1/documents/read", token, extra, false));
        String readOnly = harness.token(EditorHarness.grant(List.of(EditorScope.VALIDATE)));
        error("scope-missing", 403, harness.send("POST", "/v1/documents/read", readOnly, refs("npc", "examples/greeter"), false));
    }

    @Test
    void documentsRead_pastTheByteBudget_returnsTheRestAsRemaining_andABigDocumentAlone() throws Exception {
        String token = harness.token();
        String[] names = new String[20];
        for (int index = 0; index < 10; index++) {
            JsonObject animation = new JsonObject();
            JsonArray frames = new JsonArray();
            for (int frame = 0; frame < 1200; frame++) frames.add("&e" + "*".repeat(90) + frame);
            animation.addProperty("schemaVersion", 1);
            animation.add("frames", frames);
            Path file = harness.root.resolve("gui/text-animations/big/a" + index + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonDocuments.write(animation));
            names[index * 2] = "text-animation";
            names[index * 2 + 1] = "big:a" + index;
        }
        JsonObject first = valid("documents-read-response", harness.send("POST", "/v1/documents/read", token, refs(names), false));
        int sent = first.getAsJsonArray("documents").size(), left = first.getAsJsonArray("remaining").size();
        assertTrue(sent >= 1 && sent < 10, sent + " sent");
        assertEquals(10, sent + left);
        assertTrue(first.getAsJsonArray("documents").toString().getBytes(StandardCharsets.UTF_8).length <= EditorDocumentRoutes.ANSWER_BYTES);
        assertEquals("big:a" + sent, first.getAsJsonArray("remaining").get(0).getAsJsonObject().get("id").getAsString(), "remaining keeps request order");

        JsonObject huge = new JsonObject();
        JsonArray frames = new JsonArray();
        for (int frame = 0; frame < 12_000; frame++) frames.add("&e" + "*".repeat(90) + frame);
        huge.add("frames", frames);
        Files.writeString(harness.root.resolve("gui/text-animations/big/huge.json"), JsonDocuments.write(huge));
        JsonObject alone = valid("documents-read-response", harness.send("POST", "/v1/documents/read", token, refs("text-animation", "big:huge", "text-animation", "big:a0"), false));
        assertEquals(1, alone.getAsJsonArray("documents").size(), "a document past the budget comes alone on the direct path");
        assertEquals(1, alone.getAsJsonArray("remaining").size());
        JsonObject relay = valid("documents-read-response", relayed(harness, "POST", "/v1/documents/read", token, refs("text-animation", "big:huge")));
        assertEquals("too-large", relay.getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString(), "it would not fit the relay frame");
    }

    @Test
    void rateLimit_oneTokenPerBatch_andEvery429CarriesItsRetryHintOnBothPaths() throws Exception {
        try (EditorHarness limited = new EditorHarness(directory.resolve("slow"), settings(1, ""))) {
            String token = limited.token();
            String[] many = new String[400];
            for (int index = 0; index < 200; index++) { many[index * 2] = "npc"; many[index * 2 + 1] = "town/n" + index; }
            assertEquals(200, limited.send("POST", "/v1/documents/read", token, refs(many), false).status());
            assertEquals(200, limited.send("POST", "/v1/documents/read", token, refs(many), false).status());
            EditorResponse direct = limited.send("POST", "/v1/documents/read", token, refs(many), false);
            error("rate-limited", 429, direct);
            long millis = EditorHarness.json(direct).getAsJsonObject("error").get("retryAfterMillis").getAsLong();
            assertTrue(millis > 0 && millis <= 1000, millis + " ms");
            assertEquals("1", direct.headers().get("Retry-After"));
            EditorResponse relay = relayed(limited, "GET", "/v1/capabilities", token, null);
            error("rate-limited", 429, relay);
            assertTrue(EditorHarness.json(relay).getAsJsonObject("error").get("retryAfterMillis").getAsLong() > 0);
            assertEquals("1", relay.headers().get("Retry-After"));
            limited.clock.advance(Duration.ofSeconds(6));
            limited.api.housekeeping();
            String tokenId = token.substring(4, token.indexOf('.'));
            assertEquals(List.of("Editor " + tokenId + ": 2 requests refused by the rate limit in the last 6 s ([editor] requestsPerSecond = 1, concurrentRequestsPerToken = 8)"),
                    limited.warnings);
        }
    }

    @Test
    void serverName_isInHelloAndThePairAnswer_andTheSettingWins() throws Exception {
        assertEquals("Town", valid("hello-response", harness.send("GET", "/v1/hello", null, null)).get("serverName").getAsString());
        try (EditorHarness named = new EditorHarness(directory.resolve("named"), settings(20, "&6Brick &lworks"))) {
            assertEquals("Brick works", valid("hello-response", named.send("GET", "/v1/hello", null, null)).get("serverName").getAsString());
            EditorTokens.Pairing pairing = named.api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
            JsonObject body = new JsonObject();
            body.addProperty("tokenId", pairing.tokenId());
            body.addProperty("secret", pairing.secret());
            assertEquals("Brick works", valid("pair-response", named.send("POST", "/v1/pair", null, body, false)).get("serverName").getAsString());
        }
    }

    @Test
    void tokenExpiry_slidesOnUse_andIsStored() throws Exception {
        String token = harness.token();
        for (int week = 0; week < 3; week++) {
            harness.clock.advance(Duration.ofDays(6));
            assertEquals(200, harness.send("GET", "/v1/capabilities", token, null).status(), "used within its 7 days, week " + week);
        }
        String id = token.substring(4, token.indexOf('.'));
        EditorTokens reloaded = new EditorTokens(harness.store, harness.clock);
        assertEquals(harness.clock.instant().plus(Duration.ofDays(7)), reloaded.find(id).orElseThrow().expires(), "the slid expiry survives a restart");
        assertEquals(Duration.ofDays(7), reloaded.find(id).orElseThrow().lifetime());
        harness.clock.advance(Duration.ofDays(8));
        error("token-expired", 401, harness.send("GET", "/v1/capabilities", token, null));
    }

    @Test
    void revoke_cancelsALinkNotOpenedYet() throws Exception {
        EditorTokens.Pairing pairing = harness.api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        assertEquals(1, harness.api.parts().tokens().pendingPairings());
        assertEquals(List.of(pairing.tokenId()), harness.api.revoke(pairing.tokenId(), "token-revoked"));
        assertEquals(0, harness.api.parts().tokens().pendingPairings());
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        error("pairing-invalid", 401, harness.send("POST", "/v1/pair", null, body, false));
    }

    @Test
    void pairingFailures_areLoggedOnce_andToldToThePlayerWhoMadeTheLink() throws Exception {
        EditorTokens.Pairing pairing = harness.api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        Map<String, String> foreign = Map.of("Origin", "https://evil.example", EditorReplies.PEER_HEADER, "127.0.0.1");
        for (int attempt = 0; attempt < 2; attempt++)
            error("origin-not-allowed", 403, harness.request(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/hello", foreign, Optional.empty())));
        assertEquals(List.of("Editor refused origin https://evil.example: it is not in [editor] allowedOrigins"), harness.warnings);
        assertEquals(List.of("[Animus]: Brian's editor could not pair: origin https://evil.example is not in allowedOrigins"), harness.flushNotices());
        harness.clock.advance(Duration.ofMinutes(11));
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        error("pairing-expired", 410, harness.send("POST", "/v1/pair", null, body, false));
        assertTrue(harness.warnings.contains("Editor pairing " + pairing.tokenId() + " failed: the link expired before it was opened; run /animus editor pair again"), harness.warnings::toString);
        assertEquals(List.of("[Animus]: Brian's editor could not pair: the link expired; make a new one"), harness.flushNotices());
    }

    @Test
    void refusedEventStream_isLogged() throws Exception {
        EditorEventSink sink = new EditorEventSink() {
            @Override public boolean send(JsonObject event) { return true; }
            @Override public void close() {}
        };
        EditorEventStream stream = harness.api.openEvents(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/events",
                Map.of("Authorization", "Bearer anm_tok_missing00.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM", "Origin", EditorHarness.ORIGIN, EditorReplies.PEER_HEADER, "127.0.0.1"),
                Optional.empty()), sink).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(stream.open());
        assertEquals(List.of("Editor refused an event stream from 127.0.0.1: unauthorized"), harness.warnings);
    }

    @Test
    void defaultPairing_coversSpawnAndImport() throws Exception {
        String token = harness.token();
        assertEquals(200, harness.send("GET", "/v1/imports", token, null).status());
        List<String> scopes = new ArrayList<>();
        for (EditorTokens.Token held : harness.api.parts().tokens().list()) held.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
        assertTrue(scopes.containsAll(List.of("spawn", "import", "terrain")) && !scopes.contains("apply:commands"), scopes::toString);
    }
}
