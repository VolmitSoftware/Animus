package art.arcane.animus.core.editor;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void settings_relayKeysShareTheTable() {
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
        Path data = directory.resolve("server");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.toml"), "[editor]\nlistenerEnabled = true\nrelayEnabled = false\nport = 0\n");
        art.arcane.animus.core.AnimusRuntime runtime = new art.arcane.animus.core.AnimusRuntime(
                new art.arcane.animus.core.RuntimeEnvironment(art.arcane.animus.core.Platform.BUKKIT, "26.2", data));
        runtime.start();
        try (EditorHarness harness = new EditorHarness(directory.resolve("host"))) {
            EditorService service = new EditorService(runtime, harness.host, (String line) -> {}, (String line) -> {});
            try {
                service.start();
                EditorService.PairingLink link = service.pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
                assertTrue(link.link().startsWith("https://animus.volmitsoftware.com/#/pair/ANM1."));
                PairingCode code = PairingCode.decode(link.link().substring(link.link().indexOf("ANM1.")));
                // Port 0 binds a free port, and the link carries the one actually bound.
                String direct = code.directUrl().orElseThrow();
                assertTrue(direct.startsWith("http://127.0.0.1:"), direct);
                int port = Integer.parseInt(direct.substring("http://127.0.0.1:".length()));
                assertTrue(port > 0, direct);
                assertEquals(Optional.empty(), code.relayUrl(), "the relay is off in this config");
                assertEquals(EditorIdentity.loadOrCreate(runtime.documents()).fingerprint(), code.fingerprint());
                assertEquals(Optional.of("Town"), code.serverName(), "the platform's name while [editor] serverName is empty");
                assertTrue(service.status().stream().anyMatch((String line) -> line.startsWith("Editor ready | listener 127.0.0.1:" + port + " (http) ")));
                assertTrue(service.handler().isPresent());
            } finally { service.close(); }
        } finally { runtime.close(); }
    }

    @Test
    void pairCommand_isOneLineForAPlayerAndTwoWithTheScopesForTheConsole() throws Exception {
        Path data = directory.resolve("paired");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.toml"), "[editor]\nlistenerEnabled = true\nrelayEnabled = false\nport = 0\n");
        art.arcane.animus.core.AnimusRuntime runtime = new art.arcane.animus.core.AnimusRuntime(
                new art.arcane.animus.core.RuntimeEnvironment(art.arcane.animus.core.Platform.BUKKIT, "26.2", data));
        runtime.start();
        try (EditorHarness harness = new EditorHarness(directory.resolve("host"))) {
            EditorService service = new EditorService(runtime, harness.host, (String line) -> {}, (String line) -> {});
            try {
                service.start();
                EditorCommands commands = new EditorCommands(() -> service, Runnable::run);
                List<ChatLine> player = new ArrayList<>();
                commands.pair(recorder(EditorHarness.ADMIN, player), "laptop", "", "", "");
                assertEquals(1, player.size());
                String link = player.getFirst().console().substring(player.getFirst().console().indexOf("https://"));
                assertTrue(link.contains("#/pair/ANM1."), link);
                String shown = PlainTextComponentSerializer.plainText().serialize(player.getFirst().component());
                assertEquals("[Animus]: Link ready. Open it within 10 minutes [Open editor] [Copy] [Revoke]", shown);
                assertFalse(shown.contains("tok_"), "no bare token in chat: " + shown);
                List<String> console = new ArrayList<>();
                commands.pair(recorder(null, console), "", "", "", "");
                assertEquals(2, console.size());
                assertTrue(RichText.plain(console.getFirst()).matches("\\[Animus]: Link ready\\. Open it within 10 minutes https://\\S+#/pair/ANM1\\.\\S+"), console.getFirst());
                assertTrue(RichText.plain(console.get(1)).matches("\\[Animus]: Scopes read validate preview apply:npcs apply:behaviors apply:gui apply:rigs import spawn terrain; ends after 7d unused; token tok_\\S+"),
                        console.get(1));
            } finally { service.close(); }
        } finally { runtime.close(); }
    }

    private static CommandActor recorder(UUID player, List<?> lines) {
        @SuppressWarnings("unchecked") List<Object> sink = (List<Object>) lines;
        return new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { sink.add(message); }
            @Override public void rich(ChatLine line) { sink.add(player == null ? line.console() : line); }
            @Override public Optional<UUID> player() { return Optional.ofNullable(player); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
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
        EditorTokens.Grant grant = EditorCommands.grant(actor(EditorHarness.ADMIN), "laptop", "read,validate", "town,harbor", "12h", 7);
        assertEquals("laptop", grant.label());
        assertEquals(List.of(EditorScope.READ, EditorScope.VALIDATE), grant.scopes());
        assertEquals(List.of("town", "harbor"), grant.groups());
        assertEquals(Duration.ofHours(12), grant.lifetime());
        assertEquals(Optional.of(EditorHarness.ADMIN), grant.admin());
        EditorTokens.Grant defaults = EditorCommands.grant(actor(null), "", "", "", "", 7);
        assertEquals(EditorScope.DEFAULT_PAIRING, defaults.scopes());
        assertEquals(Duration.ofDays(7), defaults.lifetime());
        assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(actor(null), "", "root", "", "", 7));
        assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(actor(null), "", "", "", "forever", 7));
    }

    @Test
    void pairOptions_foldersAreNpcFolderNames_andAFolderCoversTheFoldersInsideIt() throws Exception {
        for (String folder : List.of("Town", "town/", "/town", "town//market", "town/../harbor", "town market", "-town", "a/b/c/d/e/f/g/h/i")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(actor(null), "", "", folder, "", 7), folder);
            assertEquals("folders take lowercase folder names under npcs/, such as town or town/market; " + folder + " is not one", refused.getMessage());
        }
        assertEquals(List.of("town/market", "harbor"), EditorCommands.grant(actor(null), "", "", " town/market , harbor ", "", 7).groups());
        try (DocumentStore store = new DocumentStore(directory)) {
            EditorTokens tokens = new EditorTokens(store, new EditorHarness.TestClock());
            EditorTokens.Pairing pairing = tokens.pair(EditorCommands.grant(actor(null), "", "", "town", "", 7));
            String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();
            EditorTokens.Token token = tokens.authenticate(bearer);
            assertTrue(EditorApi.visible(token, DocumentKind.NPC, "town/guard"));
            assertTrue(EditorApi.visible(token, DocumentKind.NPC, "town/market/baker"), "town covers the folders inside it");
            assertFalse(EditorApi.visible(token, DocumentKind.NPC, "townhall/clerk"), "whole folder names only, not a text prefix");
            assertFalse(EditorApi.visible(token, DocumentKind.NPC, "guard"), "nor the npcs/ folder itself");
        }
    }

    @Test
    void pairOptions_commandScopeNeedsItsOwnPermissionAndIsNeverADefault() {
        assertFalse(EditorScope.DEFAULT_PAIRING.contains(EditorScope.APPLY_COMMANDS));
        assertEquals(java.util.Arrays.stream(EditorScope.values()).filter((EditorScope scope) -> scope != EditorScope.APPLY_COMMANDS).toList(), EditorScope.DEFAULT_PAIRING,
                "every other scope, spawn and import included, is a default");
        assertTrue(EditorScope.parse("admin").isEmpty(), "the unused admin scope is gone");
        CommandActor admin = new CommandActor() {
            @Override public boolean permitted(String node) { return !node.equals(EditorCommands.COMMANDS_PERMISSION); }
            @Override public void tell(String message) {}
            @Override public Optional<UUID> player() { return Optional.of(EditorHarness.ADMIN); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> EditorCommands.grant(admin, "", "read,apply:behaviors,apply:commands", "", "", 7));
        assertTrue(refused.getMessage().contains(EditorCommands.COMMANDS_PERMISSION), refused.getMessage());
        assertEquals(List.of(EditorScope.APPLY_BEHAVIORS, EditorScope.APPLY_COMMANDS), EditorCommands.grant(actor(EditorHarness.ADMIN), "", "apply:behaviors,apply:commands", "", "", 7).scopes());
        assertEquals(List.of(EditorScope.APPLY_COMMANDS), EditorCommands.grant(actor(null), "", "apply:commands", "", "", 7).scopes());
    }

    @Test
    void npcEdit_scheduleArrivalCommandsNeedTheCommandsPermissionInGame() throws Exception {
        CommandActor editor = new CommandActor() {
            @Override public boolean permitted(String node) { return !node.equals(EditorCommands.COMMANDS_PERMISSION); }
            @Override public void tell(String message) {}
            @Override public Optional<UUID> player() { return Optional.of(EditorHarness.ADMIN); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        com.google.gson.JsonObject scheduled = art.arcane.animus.core.storage.JsonDocuments.object(
                "{\"modules\":{\"animus:schedule\":{\"locations\":[{\"time\":1000,\"x\":0,\"y\":64,\"z\":0,\"commands\":[\"op Mallory\"]}]}}}");
        assertTrue(EditorCommands.scheduleRefusal(editor, scheduled).orElseThrow().contains(EditorCommands.COMMANDS_PERMISSION));
        assertTrue(EditorCommands.scheduleRefusal(actor(EditorHarness.ADMIN), scheduled).isEmpty());
        assertTrue(EditorCommands.scheduleRefusal(actor(null), scheduled).isEmpty(), "the console may");
        com.google.gson.JsonObject quiet = scheduled.deepCopy();
        quiet.getAsJsonObject("modules").getAsJsonObject("animus:schedule").getAsJsonArray("locations").get(0).getAsJsonObject().remove("commands");
        assertTrue(EditorCommands.scheduleRefusal(editor, quiet).isEmpty(), "a schedule without commands needs only animus.npc.edit");
    }

    @Test
    void importApply_commandGrantsNeedTheCommandsPermissionInGameToo() {
        CommandActor admin = new CommandActor() {
            @Override public boolean permitted(String node) { return !node.equals(EditorCommands.COMMANDS_PERMISSION); }
            @Override public void tell(String message) {}
            @Override public Optional<UUID> player() { return Optional.of(EditorHarness.ADMIN); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        assertTrue(EditorCommands.applyRefusal(admin, List.of("citizens")).orElseThrow().contains(EditorCommands.COMMANDS_PERMISSION));
        assertTrue(EditorCommands.applyRefusal(admin, List.of()).isEmpty(), "an import that runs no commands needs no grant");
        assertTrue(EditorCommands.applyRefusal(actor(EditorHarness.ADMIN), List.of("citizens")).isEmpty());
        assertTrue(EditorCommands.applyRefusal(actor(null), List.of("citizens")).isEmpty(), "the console may");
        assertTrue(EditorCommands.releaseRefusal(admin, true).orElseThrow().contains(EditorCommands.COMMANDS_PERMISSION), "releasing a held behavior that runs commands");
        assertTrue(EditorCommands.releaseRefusal(admin, false).isEmpty());
        assertTrue(EditorCommands.releaseRefusal(actor(null), true).isEmpty(), "the console may");
    }
}
