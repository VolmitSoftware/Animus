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
        assertEquals("apply:commands", error.has("scope") ? error.get("scope").getAsString() : null, error.toString());
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

    private static JsonObject say(String id, String capability) throws Exception {
        return JsonDocuments.object("""
                {"schemaVersion": 1, "revision": 1, "id": "%s", "entry": "main",
                 "routines": {"main": {"type": "action", "capability": "%s", "arguments": {"text": "Hello"}}},
                 "triggers": [{"id": "click", "event": "interaction"}]}
                """.formatted(id, capability));
    }

    @Test
    void apply_guiActionsThatCreateItemsOrMoney_needApplyCommands() throws Exception {
        JsonObject current = stored("inventory", "shop:counter");
        List<String> actions = List.of("{\"type\": \"give\", \"item\": {\"type\": \"item\", \"item\": \"minecraft:diamond\"}, \"amount\": 1}",
                "{\"type\": \"economy\", \"op\": \"deposit\", \"amount\": 5}");
        for (String action : actions) {
            JsonObject source = current.getAsJsonObject("source").deepCopy();
            source.getAsJsonObject("keys").getAsJsonObject("X").getAsJsonArray("actions").add(JsonDocuments.object(action));
            refused(harness.send("POST", "/v1/changes", plain, set("key-00000000000002" + (action.contains("give") ? "01" : "02"), change("inventory", "shop:counter", current, source))));
        }
    }

    @Test
    void apply_shopThatTradesAtAPrice_needsOnlyItsApplyScope() throws Exception {
        JsonObject current = stored("shop", "shop:bakery");
        JsonObject source = current.getAsJsonObject("source").deepCopy();
        source.addProperty("title", "&6Cheaper");
        EditorResponse applied = harness.send("POST", "/v1/changes", plain, set("key-0000000000000203", change("shop", "shop:bakery", current, source)));
        assertEquals(200, applied.status(), "priced buys, sells and trades are the shop's own job: " + applied.body());
    }

    /** The bakery with one more entry in its first page's buy grid. */
    private JsonObject bakeryWith(String entry) throws Exception { return bakeryWith("buy", entry); }

    private JsonObject bakeryWith(String grid, String entry) throws Exception {
        JsonObject source = stored("shop", "shop:bakery").getAsJsonObject("source").deepCopy();
        source.getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject(grid).add("20", JsonDocuments.object(entry));
        return source;
    }

    private EditorResponse applyShop(String key, String token, JsonObject source) throws Exception {
        return harness.send("POST", "/v1/changes", token, set(key, change("shop", "shop:bakery", stored("shop", "shop:bakery"), source)));
    }

    @Test
    void apply_shopThatRunsCommands_needsApplyCommands() throws Exception {
        refused(applyShop("key-0000000000000220", plain, bakeryWith("""
                {"id": "pet", "type": "command", "item": {"id": "minecraft:bone"}, "price": 50, "commands": [{"command": "op Mallory"}]}""")));
        refused(applyShop("key-0000000000000221", plain, bakeryWith("""
                {"id": "pet", "type": "buy", "item": {"id": "minecraft:bone"}, "price": 50, "commands": [{"command": "op Mallory"}]}""")));
        EditorResponse granted = applyShop("key-0000000000000222", commands, bakeryWith("""
                {"id": "pet", "type": "command", "item": {"id": "minecraft:bone"}, "price": 50, "commands": [{"command": "say hi"}]}"""));
        assertEquals(200, granted.status(), () -> granted.body().toString());
    }

    @Test
    void apply_shopThatHandsOutGoodsForNothing_needsApplyCommands() throws Exception {
        refused(applyShop("key-0000000000000223", plain, bakeryWith("""
                {"id": "free", "type": "buy", "item": {"id": "minecraft:diamond"}, "price": 0}""")));
        refused(applyShop("key-0000000000000224", plain, bakeryWith("""
                {"id": "free", "type": "buy", "item": {"id": "minecraft:diamond"}, "price": 9, "discount": {"price": 0}}""")));
        EditorResponse levels = applyShop("key-0000000000000225", plain, bakeryWith("""
                {"id": "free", "type": "buy", "item": {"id": "minecraft:diamond"}, "price": 0, "levels": 3}"""));
        assertEquals(200, levels.status(), "experience levels are a price: " + levels.body());
    }

    @Test
    void apply_shopWithTypeKeysOutsideItsEntries_isReadAsTheParserReadsIt() throws Exception {
        JsonObject source = stored("shop", "shop:bakery").getAsJsonObject("source").deepCopy();
        source.add("imported", JsonDocuments.object("{\"type\": \"command\", \"commands\": [{\"command\": \"op Mallory\"}]}"));
        EditorResponse applied = applyShop("key-0000000000000226", plain, source);
        assertEquals(200, applied.status(), "imported metadata runs nothing: " + applied.body());
    }

    @Test
    void apply_shopEntryTypeSpellings_areReadAsTheParserReadsThem() throws Exception {
        int key = 300;
        for (String spelling : List.of("command", "Command", "COMMAND"))
            refused(applyShop("key-0000000000000" + key++, plain, bakeryWith("""
                    {"id": "pet", "type": "%s", "item": {"id": "minecraft:bone"}, "price": 50, "commands": [{"command": "op Mallory"}]}""".formatted(spelling))));
        for (String spelling : List.of("buy", "Buy", "BUY"))
            refused(applyShop("key-0000000000000" + key++, plain, bakeryWith("""
                    {"id": "free", "type": "%s", "item": {"id": "minecraft:diamond"}, "price": 0}""".formatted(spelling))));
        for (String spelling : List.of("buy", "Buy", "BUY")) {
            EditorResponse priced = applyShop("key-0000000000000" + key++, plain, bakeryWith("""
                    {"id": "cake", "type": "%s", "item": {"id": "minecraft:cake"}, "price": 9}""".formatted(spelling)));
            assertEquals(200, priced.status(), spelling + ": " + priced.body());
        }
        String before = harness.read("shops/shop/bakery.json");
        for (String spelling : List.of("ſell", "statıc", "STATİC", "ｓｅｌｌ")) {
            for (String token : List.of(plain, commands)) {
                EditorResponse unread = applyShop("key-0000000000000" + key++, token, bakeryWith("sell", """
                        {"id": "rye", "type": "%s", "item": {"id": "minecraft:wheat"}, "price": 2}""".formatted(spelling)));
                assertTrue(unread.status() == 403 || unread.status() == 422, spelling + " must never be written: " + unread.status() + " " + unread.body());
            }
        }
        assertEquals(before, harness.read("shops/shop/bakery.json"));
    }

    @Test
    void apply_questThatPaysItems_needsApplyCommands_andAPlainQuestDoesNot() throws Exception {
        Path here = Path.of("").toAbsolutePath();
        String herbs = java.nio.file.Files.readString((java.nio.file.Files.isDirectory(here.resolve("examples")) ? here : here.getParent()).resolve("examples/quests/town/herbs.json"));
        JsonObject paying = JsonDocuments.object(herbs);
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000210", change("quest", "town:herbs", null, paying))));
        JsonObject plainQuest = paying.deepCopy();
        plainQuest.getAsJsonObject("rewards").remove("items");
        plainQuest.getAsJsonObject("rewards").remove("money");
        plainQuest.getAsJsonObject("rewards").remove("experience");
        EditorResponse applied = harness.send("POST", "/v1/changes", plain, set("key-0000000000000211", change("quest", "town:herbs", null, plainQuest)));
        assertEquals(200, applied.status(), () -> applied.body().toString());
        JsonObject broken = plainQuest.deepCopy();
        broken.addProperty("id", "town:other");
        EditorResponse refused = harness.send("POST", "/v1/changes", commands, set("key-0000000000000212", change("quest", "town:herbs", stored("quest", "town:herbs"), broken)));
        assertEquals(422, refused.status(), "the quest parser checks editor applies: " + refused.body());
    }

    @Test
    void apply_questThatPaysExperience_needsApplyCommands() throws Exception {
        JsonObject experience = herbs();
        experience.getAsJsonObject("rewards").remove("items");
        experience.getAsJsonObject("rewards").remove("money");
        experience.getAsJsonObject("rewards").remove("flags");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000213", change("quest", "town:herbs", null, experience))));
    }

    @Test
    void apply_questInARawTextNamespace_needsApplyCommands() throws Exception {
        harness.host.rawTextBehaviors = List.of("town:gate");
        JsonObject flags = herbs();
        flags.getAsJsonObject("rewards").remove("items");
        flags.getAsJsonObject("rewards").remove("money");
        flags.getAsJsonObject("rewards").remove("experience");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000214", change("quest", "town:herbs", null, flags))));
    }

    private static JsonObject herbs() throws Exception {
        Path here = Path.of("").toAbsolutePath();
        return JsonDocuments.object(java.nio.file.Files.readString((java.nio.file.Files.isDirectory(here.resolve("examples")) ? here : here.getParent()).resolve("examples/quests/town/herbs.json")));
    }

    private static JsonObject arrival(String command) throws Exception {
        return JsonDocuments.object("""
                {"animus:schedule": {"locations": [{"time": 1000, "x": 0, "y": 64, "z": 0, "commands": ["%s"]}]}}
                """.formatted(command));
    }

    @Test
    void apply_npcWithScheduleArrivalCommands_needsApplyCommands() throws Exception {
        JsonObject current = stored("npc", "examples/greeter");
        JsonObject scheduled = current.getAsJsonObject("source").deepCopy();
        scheduled.add("modules", arrival("op Mallory"));
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000215", change("npc", "examples/greeter", current, scheduled))));
        EditorResponse granted = harness.send("POST", "/v1/changes", commands, set("key-0000000000000216", change("npc", "examples/greeter", current, scheduled)));
        assertEquals(200, granted.status(), () -> granted.body().toString());
    }

    @Test
    void apply_groupWithScheduleArrivalCommands_needsApplyCommands() throws Exception {
        JsonObject group = JsonDocuments.object("{\"schemaVersion\": 2, \"revision\": 1, \"id\": \"guards\", \"values\": {}}");
        group.getAsJsonObject("values").add("modules", arrival("op Mallory"));
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000217", change("group", "guards", null, group))));
    }

    private JsonObject greeterWith(String modules) throws Exception {
        JsonObject source = stored("npc", "examples/greeter").getAsJsonObject("source").deepCopy();
        source.add("modules", JsonDocuments.object(modules));
        return source;
    }

    private EditorResponse applyGreeter(String key, String token, JsonObject source) throws Exception {
        return harness.send("POST", "/v1/changes", token, set(key, change("npc", "examples/greeter", stored("npc", "examples/greeter"), source)));
    }

    @Test
    void apply_npcWhoseDeathDropsItemsOrExperience_needsApplyCommands() throws Exception {
        List<String> drops = List.of(
                "{\"animus:health\": {\"death\": {\"drops\": [{\"item\": {\"id\": \"minecraft:diamond\"}}]}}}",
                "{\"animus:health\": {\"death\": {\"experience\": 50}}}",
                "{\"animus:health\": {\"death\": {\"dropEquipment\": true}}}");
        int key = 400;
        for (String modules : drops) refused(applyGreeter("key-0000000000000" + key++, plain, greeterWith(modules)));
        EditorResponse plainHealth = applyGreeter("key-0000000000000" + key++, plain, greeterWith("{\"animus:health\": {\"death\": {\"experience\": 0, \"dropEquipment\": false}}}"));
        assertEquals(200, plainHealth.status(), "an NPC that drops nothing needs only its apply scope: " + plainHealth.body());
        EditorResponse granted = applyGreeter("key-0000000000000" + key++, commands, greeterWith(drops.getFirst()));
        assertEquals(200, granted.status(), () -> granted.body().toString());
        JsonObject looters = group("looters", "{\"modules\": " + drops.get(2) + "}");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000" + key++, change("group", "looters", null, looters))));
        assertEquals(200, harness.send("POST", "/v1/changes", commands, set("key-0000000000000" + key++, change("group", "looters", null, looters))).status());
        JsonObject member = greeterWith("{}");
        member.add("groups", com.google.gson.JsonParser.parseString("[\"looters\"]").getAsJsonArray());
        member.add("equipment", JsonDocuments.object("{\"head\": {\"id\": \"minecraft:netherite_helmet\"}}"));
        refused(applyGreeter("key-0000000000000" + key, plain, member));
    }

    private static JsonObject quester(String node) throws Exception {
        return JsonDocuments.object("""
                {"schemaVersion": 1, "revision": 1, "id": "town:quester", "entry": "main", "variables": {"go": {"type": "boolean", "initial": true}},
                 "routines": {"main": %s}, "triggers": [{"id": "click", "event": "interaction"}]}
                """.formatted(node));
    }

    @Test
    void apply_behaviorThatPaysQuestRewards_needsApplyCommands() throws Exception {
        List<String> paying = List.of(
                "{\"type\": \"action\", \"capability\": \"animus:complete-quest\", \"arguments\": {\"quest\": \"town:herbs\"}}",
                "{\"type\": \"action\", \"capability\": \"animus:complete-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"rewards\": true}}",
                "{\"type\": \"action\", \"capability\": \"animus:advance-quest\", \"arguments\": {\"quest\": \"town:herbs\"}}",
                "{\"type\": \"action\", \"capability\": \"animus:start-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"force\": true}}",
                "{\"type\": \"action\", \"capability\": \"animus:start-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"force\": null}, \"bindings\": {\"/force\": {\"variable\": \"go\"}}}",
                "{\"type\": \"action\", \"capability\": \"animus:complete-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"rewards\": null}, \"bindings\": {\"/rewards\": {\"variable\": \"go\"}}}");
        int key = 420;
        for (String node : paying) refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000" + key++, change("behavior", "town:quester", null, quester(node)))));
        List<String> unpaid = List.of(
                "{\"type\": \"action\", \"capability\": \"animus:complete-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"rewards\": false}}",
                "{\"type\": \"action\", \"capability\": \"animus:start-quest\", \"arguments\": {\"quest\": \"town:herbs\", \"force\": false}}",
                "{\"type\": \"action\", \"capability\": \"animus:start-quest\", \"arguments\": {\"quest\": \"town:herbs\"}}");
        for (String node : unpaid) {
            JsonObject current = harness.read("behaviors/town/quester.json") == null ? null : stored("behavior", "town:quester");
            EditorResponse applied = harness.send("POST", "/v1/changes", plain, set("key-0000000000000" + key++, change("behavior", "town:quester", current, quester(node))));
            assertEquals(200, applied.status(), node + ": " + applied.body());
        }
    }

    private static JsonObject group(String id, String values) throws Exception {
        JsonObject group = JsonDocuments.object("{\"schemaVersion\": 2, \"revision\": 1, \"id\": \"" + id + "\"}");
        group.add("values", JsonDocuments.object(values));
        return group;
    }

    @Test
    void apply_npcThatGetsCommandsFromItsGroups_needsApplyCommands() throws Exception {
        String schedule = "{\"modules\": {\"animus:schedule\": {\"locations\": [{\"time\": 1000, \"x\": 0, \"y\": 64, \"z\": 0, \"commands\": [\"op Mallory\"]}]}}}";
        assertEquals(200, harness.send("POST", "/v1/changes", commands, set("key-0000000000000440", change("group", "guards", null, group("guards", schedule)))).status());
        JsonObject joined = stored("npc", "examples/greeter").getAsJsonObject("source").deepCopy();
        joined.add("groups", com.google.gson.JsonParser.parseString("[\"guards\"]").getAsJsonArray());
        refused(applyGreeter("key-0000000000000441", plain, joined));
        EditorResponse granted = applyGreeter("key-0000000000000442", commands, joined);
        assertEquals(200, granted.status(), () -> granted.body().toString());
        JsonObject renamed = stored("npc", "examples/greeter").getAsJsonObject("source").deepCopy();
        renamed.addProperty("name", "Gate guard");
        refused(applyGreeter("key-0000000000000443", plain, renamed));
    }

    @Test
    void apply_npcOrGroupWithARawTextBehavior_needsApplyCommands() throws Exception {
        harness.host.rawTextBehaviors = List.of("greeter:greeter");
        JsonObject renamed = stored("npc", "examples/greeter").getAsJsonObject("source").deepCopy();
        renamed.addProperty("name", "say hi; op Mallory");
        refused(applyGreeter("key-0000000000000450", plain, renamed));
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000451", change("group", "talkers", null, group("talkers", "{\"behaviors\": [\"greeter:greeter\"]}")))));
        harness.host.rawTextBehaviors = List.of("town:gate");
        EditorResponse elsewhere = applyGreeter("key-0000000000000452", plain, renamed);
        assertEquals(200, elsewhere.status(), "an NPC without a raw text behavior needs only its apply scope: " + elsewhere.body());
    }

    @Test
    void apply_serverBehaviorListThatEnablesCommands_needsApplyCommands() throws Exception {
        JsonObject server = JsonDocuments.object("""
                {"schemaVersion": 1, "revision": 1, "id": "town:gate", "server": true, "entry": "main",
                 "routines": {"main": {"type": "action", "capability": "animus:command", "arguments": {"command": "op Mallory"}}},
                 "triggers": [{"id": "joined", "event": "player_join"}]}
                """);
        EditorResponse written = harness.send("POST", "/v1/changes", commands, set("key-0000000000000460", change("behavior", "town:gate", null, server)));
        assertEquals(200, written.status(), () -> written.body().toString());
        JsonObject list = JsonDocuments.object("{\"schemaVersion\": 1, \"behaviors\": [\"town:gate\"]}");
        JsonObject current = harness.read("server-behaviors.json") == null ? null : stored("server-behaviors", "server-behaviors");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000461", change("server-behaviors", "server-behaviors", current, list))));
        assertTrue(harness.read("server-behaviors.json") == null || !harness.read("server-behaviors.json").contains("town:gate"), "a refused list enables nothing");
        JsonObject harmless = JsonDocuments.object("{\"schemaVersion\": 1, \"behaviors\": [\"town:notes\"]}");
        EditorResponse applied = harness.send("POST", "/v1/changes", plain, set("key-0000000000000462", change("server-behaviors", "server-behaviors", current, harmless)));
        assertEquals(200, applied.status(), () -> applied.body().toString());
    }

    @Test
    void spawn_npcThatRunsCommands_needsApplyCommands() throws Exception {
        JsonObject scheduled = stored("npc", "examples/greeter").getAsJsonObject("source").deepCopy();
        scheduled.add("modules", arrival("op Mallory"));
        scheduled.addProperty("spawned", false);
        assertEquals(200, applyGreeter("key-0000000000000470", commands, scheduled).status());
        harness.npcs.reload();
        String before = harness.read("npcs/examples/greeter.json");
        refused(harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", plain, null));
        assertEquals(before, harness.read("npcs/examples/greeter.json"), "a refused spawn writes nothing");
        EditorResponse granted = harness.send("POST", "/v1/npcs/examples%2Fgreeter/spawn", commands, null);
        assertEquals(200, granted.status(), () -> granted.body().toString());
    }

    @Test
    void apply_behaviorThatGivesItems_needsApplyCommands() throws Exception {
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000205", change("behavior", "town:gift", null, say("town:gift", "animus:give-item")))));
    }

    @Test
    void apply_anyBehaviorBesideARawTextBehavior_needsApplyCommands() throws Exception {
        harness.host.rawTextBehaviors = List.of("town:reward");
        refused(harness.send("POST", "/v1/changes", plain, set("key-0000000000000206", change("behavior", "town:notes", null, say("town:notes", "animus:say")))));
        EditorResponse elsewhere = harness.send("POST", "/v1/changes", plain, set("key-0000000000000207", change("behavior", "village:notes", null, say("village:notes", "animus:say"))));
        assertEquals(200, elsewhere.status(), "flags are shared by namespace, so only the raw text behavior's namespace is gated: " + elsewhere.body());
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
        assertEquals(409, harness.send("POST", "/v1/imports/" + id + "/apply", importer, null).status(), "with an empty allow-list an import cannot run commands");
        harness.host.commandGrants = true;
        refused(harness.send("POST", "/v1/imports/" + id + "/apply", importer, null));
    }
}
