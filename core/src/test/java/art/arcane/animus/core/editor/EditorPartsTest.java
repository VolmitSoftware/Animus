package art.arcane.animus.core.editor;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tokens, settings, event pacing, journal ids and the editor command options. */
class EditorPartsTest {
    @TempDir Path directory;

    @Test
    void tokens_survivRestartWithCounterAndRevocation() throws Exception {
        EditorHarness.TestClock clock = new EditorHarness.TestClock();
        String bearer;
        String id;
        try (DocumentStore store = new DocumentStore(directory)) {
            EditorTokens tokens = new EditorTokens(store, clock);
            EditorTokens.Pairing pairing = tokens.pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
            bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.of("Firefox")).getKey();
            id = pairing.tokenId();
            tokens.advance(id, 7);
        }
        try (DocumentStore store = new DocumentStore(directory)) {
            EditorTokens tokens = new EditorTokens(store, clock);
            assertEquals(id, tokens.authenticate(bearer).id());
            EditorError replay = assertThrows(EditorError.class, () -> tokens.advance(id, 7));
            assertEquals(EditorError.Code.COUNTER_REPLAYED, replay.code());
            assertEquals(List.of(id), tokens.revoke("all"));
        }
        try (DocumentStore store = new DocumentStore(directory)) {
            EditorError revoked = assertThrows(EditorError.class, () -> new EditorTokens(store, clock).authenticate(bearer));
            assertEquals(EditorError.Code.TOKEN_REVOKED, revoked.code());
        }
    }

    @Test
    void settings_defaultsRoundTripThroughConfigToml() throws Exception {
        try (DocumentStore store = new DocumentStore(directory)) {
            PluginSettings first = PluginSettings.load(store);
            assertEquals(EditorSettings.DEFAULT, first.editor());
            String toml = Files.readString(directory.resolve("config.toml"));
            assertTrue(toml.contains("listenerEnabled") && toml.contains("relayEnabled") && toml.contains("allowedOrigins"));
            assertEquals(EditorSettings.DEFAULT, PluginSettings.load(store).editor());
        }
    }

    @Test
    void settings_unknownEditorKey_isRefused() {
        JsonObject table = new JsonObject();
        table.addProperty("listnerEnabled", true);
        assertThrows(IllegalArgumentException.class, () -> EditorSettings.parse(table));
        JsonObject relay = new JsonObject();
        relay.addProperty("relayEnabled", true);
        assertEquals(EditorSettings.DEFAULT, EditorSettings.parse(relay));
    }

    @Test
    void events_catalogFramesCoalesceAndPaceOnePerTick() {
        EditorEvents events = new EditorEvents();
        List<JsonObject> sent = new ArrayList<>();
        events.add("tok_00000001", new EditorEventSink() {
            @Override public boolean send(JsonObject event) { sent.add(event); return true; }
            @Override public void close() {}
        }, (String npc) -> !npc.startsWith("hidden/"));
        for (String id : List.of("a/one", "a/two", "a/one", "hidden/x")) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "catalog");
            JsonArray documents = new JsonArray();
            documents.add(EditorChanges.catalogEntry(DocumentKind.NPC, id, "npcs/" + id + ".json", 1, "sha256:" + "0".repeat(64)));
            frame.add("documents", documents);
            events.publish(frame);
        }
        events.tick();
        events.tick();
        assertEquals(1, sent.size());
        assertEquals(2, sent.getFirst().getAsJsonArray("documents").size());
    }

    @Test
    void store_commitUnderChosenIdAndInverse() throws Exception {
        try (DocumentStore store = new DocumentStore(directory)) {
            String id = UUID.randomUUID().toString();
            assertEquals(id, store.commit(id, List.of(new DocumentStore.Change("routes/path.json", "missing", "{}\n"))));
            assertThrows(java.io.IOException.class, () -> store.commit(id, List.of(new DocumentStore.Change("routes/other.json", "missing", "{}\n"))));
            List<DocumentStore.Change> inverse = store.inverse(id);
            assertEquals(1, inverse.size());
            store.commit(inverse);
            assertEquals(null, store.read("routes/path.json"));
        }
    }

    @Test
    void service_pairLinkDecodesToReachableCode() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Path data = directory.resolve("server");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.toml"), "statusMessage = \"Hello\"\n[editor]\nlistenerEnabled = true\nrelayEnabled = false\nport = " + port + "\n");
        art.arcane.animus.core.AnimusRuntime runtime = new art.arcane.animus.core.AnimusRuntime(
                new art.arcane.animus.core.RuntimeEnvironment(art.arcane.animus.core.Platform.BUKKIT, "26.2", data));
        runtime.start();
        try (EditorHarness harness = new EditorHarness(directory.resolve("host"))) {
            EditorService service = new EditorService(runtime, harness.host, (String line) -> {});
            try {
                service.start();
                EditorService.PairingLink link = service.pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
                assertTrue(link.link().startsWith("https://animus-editor.web.app/#/pair/ANM1."));
                PairingCode code = PairingCode.decode(link.link().substring(link.link().indexOf("ANM1.")));
                assertEquals(Optional.of("http://127.0.0.1:" + port), code.directUrl());
                assertEquals(Optional.empty(), code.relayUrl(), "the relay is off in this config");
                assertEquals(EditorIdentity.loadOrCreate(runtime.documents()).fingerprint(), code.fingerprint());
                assertTrue(service.status().stream().anyMatch((String line) -> line.startsWith("Editor listener http://127.0.0.1:" + port)));
                assertTrue(service.handler().isPresent());
            } finally { service.close(); }
        } finally { runtime.close(); }
    }

    private static CommandActor actor(UUID player) {
        return new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) {}
            @Override public Optional<UUID> player() { return Optional.ofNullable(player); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }

    @Test
    void pairOptions_keyedValues() {
        EditorTokens.Grant grant = EditorCommands.grant(actor(EditorHarness.ADMIN), new String[]{"laptop", "scopes=read,validate", "groups=town,harbor", "expires=12h"}, 7);
        assertEquals("laptop", grant.label());
        assertEquals(List.of(EditorScope.READ, EditorScope.VALIDATE), grant.scopes());
        assertEquals(List.of("town", "harbor"), grant.groups());
        assertEquals(Duration.ofHours(12), grant.lifetime());
        assertEquals(Optional.of(EditorHarness.ADMIN), grant.admin());
        EditorTokens.Grant defaults = EditorCommands.grant(actor(null), new String[0], 7);
        assertEquals(EditorScope.DEFAULT_PAIRING, defaults.scopes());
        assertEquals(Duration.ofDays(7), defaults.lifetime());
        assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(actor(null), new String[]{"scopes=root"}, 7));
        assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(actor(null), new String[]{"expires=forever"}, 7));
    }
}
