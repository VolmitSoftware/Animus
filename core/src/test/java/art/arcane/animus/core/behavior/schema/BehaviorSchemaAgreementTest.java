package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The generated schema and the compiler judge the same documents the same way, except for the checks JSON Schema cannot express. */
class BehaviorSchemaAgreementTest {
    private static OfflineCapabilities capabilities;
    private static JsonSchemaCheck behaviors;
    private static JsonSchemaCheck npcs;
    private static JsonSchemaCheck packs;
    @TempDir Path temporary;

    @BeforeAll static void generate() {
        capabilities = new OfflineCapabilities();
        behaviors = new JsonSchemaCheck(BehaviorSchemaGenerator.behaviorSchema(capabilities.capabilities()));
        npcs = new JsonSchemaCheck(NpcSchemaGenerator.schema());
        packs = new JsonSchemaCheck(BehaviorSchemaGenerator.packSchema());
    }
    @AfterAll static void close() { capabilities.close(); }

    private static String document(String routine, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"sample:test\",\"entry\":\"main\"," + extra + "\"routines\":{\"main\":" + routine + "}}";
    }
    private static String action(String capability, String arguments) {
        return document("{\"type\":\"action\",\"capability\":\"animus:" + capability + "\",\"arguments\":" + arguments + "}", "");
    }
    private static String trigger(String body) {
        return document("{\"type\":\"wait\",\"ticks\":1}", "\"triggers\":[{\"id\":\"one\"," + body + "}],");
    }
    private static String variable(String body) {
        return document("{\"type\":\"wait\",\"ticks\":1}", "\"variables\":{\"one\":" + body + "},");
    }
    private OfflineBehaviorValidation.Report compile(String source) throws IOException {
        Path file = temporary.resolve("behaviors/sample/test.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return OfflineBehaviorValidation.validate(temporary, capabilities);
    }
    private static List<String> schema(String source) throws IOException { return behaviors.validate(JsonDocuments.object(source)); }

    @Test void everyShippedBehaviorValidatesUnderBothSchemaAndCompiler() throws Exception {
        int documents = 0;
        for (Path pack : RepositoryFiles.packDirectories()) {
            for (Path file : RepositoryFiles.jsonUnder(pack.resolve("behaviors"))) {
                assertEquals(List.of(), behaviors.validate(JsonDocuments.object(Files.readString(file))), file.toString());
                documents++;
            }
            OfflineBehaviorValidation.Report report = OfflineBehaviorValidation.validate(pack, capabilities);
            assertTrue(report.valid(), pack + ": " + report.lines());
        }
        assertTrue(documents >= 8, "expected every example pack to ship behaviors, saw " + documents);
        Path reference = RepositoryFiles.root().resolve("examples/behaviors/reference/module-sequence.json");
        assertEquals(List.of(), behaviors.validate(JsonDocuments.object(Files.readString(reference))));
        assertTrue(OfflineBehaviorValidation.validate(RepositoryFiles.root().resolve("examples"), capabilities).valid());
    }

    @Test void everyShippedNpcAndManifestValidatesUnderBoth() throws Exception {
        for (Path pack : RepositoryFiles.packDirectories()) {
            for (Path file : RepositoryFiles.jsonUnder(pack.resolve("npcs"))) {
                JsonObject npc = JsonDocuments.object(Files.readString(file));
                assertEquals(List.of(), NpcDefinition.validate(npc), file.toString());
                assertEquals(List.of(), npcs.validate(npc), file.toString());
            }
            String manifest = Files.readString(pack.resolve("pack.json"));
            assertEquals(List.of(), packs.validate(JsonDocuments.object(manifest)), pack.toString());
            assertEquals(pack.getFileName().toString(), PackManifest.parse(manifest, pack.getFileName().toString()).id());
        }
    }

    @Test void representativeInvalidDocumentsFailUnderBoth() throws Exception {
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("no routines", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"sample:test\",\"entry\":\"main\"}");
        invalid.put("no revision", "{\"schemaVersion\":1,\"id\":\"sample:test\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}");
        invalid.put("revision zero", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("\"revision\":1", "\"revision\":0"));
        invalid.put("schema version", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        invalid.put("unknown document field", document("{\"type\":\"wait\",\"ticks\":1}", "\"author\":\"me\","));
        invalid.put("no entry routine name", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("\"entry\":\"main\"", "\"entry\":\"Main!\""));
        invalid.put("routine name", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("\"main\":{", "\"Bad Name\":{"));
        invalid.put("unknown node", document("{\"type\":\"teleport\"}", ""));
        invalid.put("wait zero", document("{\"type\":\"wait\",\"ticks\":0}", ""));
        invalid.put("wait too long", document("{\"type\":\"wait\",\"ticks\":72001}", ""));
        invalid.put("repeat too many", document("{\"type\":\"repeat\",\"count\":129,\"body\":{\"type\":\"wait\",\"ticks\":1}}", ""));
        invalid.put("parallel empty", document("{\"type\":\"parallel\",\"branches\":[]}", ""));
        invalid.put("field of another node", document("{\"type\":\"wait\",\"ticks\":1,\"count\":3}", ""));
        invalid.put("misspelled field", document("{\"type\":\"action\",\"capabilty\":\"animus:say\"}", ""));
        invalid.put("condition without then", document("{\"type\":\"condition\",\"capability\":\"animus:flag-state\",\"arguments\":{\"name\":\"met\"}}", ""));
        invalid.put("variable type", variable("{\"type\":\"list\",\"initial\":1}"));
        invalid.put("variable initial type", variable("{\"type\":\"integer\",\"initial\":\"one\"}"));
        invalid.put("variable scope", variable("{\"type\":\"integer\",\"initial\":1,\"scope\":\"world\"}"));
        invalid.put("execution with lifetime", variable("{\"type\":\"integer\",\"initial\":1,\"lifetime\":\"transient\"}"));
        invalid.put("npc without lifetime", variable("{\"type\":\"integer\",\"initial\":1,\"scope\":\"npc\"}"));
        invalid.put("variable lifetime", variable("{\"type\":\"integer\",\"initial\":1,\"scope\":\"npc\",\"lifetime\":\"forever\"}"));
        invalid.put("trigger event", trigger("\"event\":\"chat\""));
        invalid.put("trigger field of another event", trigger("\"event\":\"spawn\",\"radius\":3"));
        invalid.put("proximity without phase", trigger("\"event\":\"proximity\",\"radius\":3"));
        invalid.put("proximity radius", trigger("\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":65"));
        invalid.put("proximity zero radius", trigger("\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":0"));
        invalid.put("interaction action", trigger("\"event\":\"interaction\",\"action\":\"poke\""));
        invalid.put("timer without interval", trigger("\"event\":\"timer\""));
        invalid.put("waypoint range", trigger("\"event\":\"waypoint\",\"waypoint\":4096"));
        invalid.put("dialogue without id", trigger("\"event\":\"dialogue_choice\""));
        invalid.put("module key", trigger("\"event\":\"module\",\"key\":\"nonamespace\""));
        invalid.put("cooldown negative", trigger("\"event\":\"spawn\",\"cooldownTicks\":-1"));
        invalid.put("say without text", action("say", "{}"));
        invalid.put("say unknown argument", action("say", "{\"text\":\"Hi\",\"voice\":\"loud\"}"));
        invalid.put("say audience", action("say", "{\"text\":\"Hi\",\"audience\":\"everyone\"}"));
        invalid.put("radius type", action("player-in-range", "{}"));
        invalid.put("navigate route and coordinates", action("navigate", "{\"route\":\"a\",\"x\":1,\"y\":2,\"z\":3}"));
        invalid.put("navigate neither", action("navigate", "{\"wait\":true}"));
        invalid.put("sound volume", action("play-sound", "{\"sound\":\"minecraft:a\",\"volume\":9}"));
        invalid.put("combat operation", action("combat-control", "{\"operation\":\"charge\"}"));
        invalid.put("arguments omitted", document("{\"type\":\"action\",\"capability\":\"animus:say\"}", ""));
        invalid.put("unknown built-in action", action("sya", "{\"text\":\"Hi\"}"));
        invalid.put("unknown built-in condition", document("{\"type\":\"condition\",\"capability\":\"animus:flagstate\",\"then\":{\"type\":\"wait\",\"ticks\":1}}", ""));
        invalid.put("capability form", document("{\"type\":\"action\",\"capability\":\"say\"}", ""));
        invalid.put("reserved id", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("sample:test", "animus:flags/town"));
        invalid.put("uppercase id", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("sample:test", "Sample:Test"));
        for (Map.Entry<String, String> specimen : invalid.entrySet()) {
            assertFalse(schema(specimen.getValue()).isEmpty(), "schema accepted: " + specimen.getKey());
            assertFalse(compile(specimen.getValue()).valid(), "compiler accepted: " + specimen.getKey());
        }
    }

    /** These need a whole-document or semantic view. The compiler is authoritative; a schema pass never promises acceptance. */
    @Test void checksJsonSchemaCannotExpressStayWithTheCompiler() throws Exception {
        Map<String, String> compilerOnly = new LinkedHashMap<>();
        compilerOnly.put("entry routine exists", document("{\"type\":\"wait\",\"ticks\":1}", "").replace("\"entry\":\"main\"", "\"entry\":\"other\""));
        compilerOnly.put("called routine exists", document("{\"type\":\"call\",\"routine\":\"other\"}", ""));
        compilerOnly.put("set declared variable", document("{\"type\":\"set\",\"variable\":\"other\",\"value\":1}", ""));
        compilerOnly.put("trigger routine exists", trigger("\"event\":\"spawn\",\"routine\":\"other\""));
        compilerOnly.put("duplicate trigger id", document("{\"type\":\"wait\",\"ticks\":1}", "\"triggers\":[{\"id\":\"one\",\"event\":\"spawn\"},{\"id\":\"one\",\"event\":\"death\"}],"));
        compilerOnly.put("flag name characters", action("set-flag", "{\"name\":\"Not Valid\"}"));
        compilerOnly.put("command slash", action("command", "{\"command\":\"/say hi\"}"));
        compilerOnly.put("duplicate option id", action("choices", "{\"id\":\"a\",\"prompt\":\"b\",\"options\":[{\"id\":\"x\",\"text\":\"1\"},{\"id\":\"x\",\"text\":\"2\"}]}"));
        for (Map.Entry<String, String> specimen : compilerOnly.entrySet()) {
            assertEquals(List.of(), schema(specimen.getValue()), "schema now expresses: " + specimen.getKey());
            assertFalse(compile(specimen.getValue()).valid(), "compiler accepted: " + specimen.getKey());
        }
    }

    @Test void representativeValidDocumentsPassBoth() throws Exception {
        Map<String, String> valid = new LinkedHashMap<>();
        valid.put("every node", document("{\"type\":\"sequence\",\"steps\":[{\"type\":\"wait\",\"ticks\":72000},{\"type\":\"repeat\",\"count\":0,\"body\":{\"type\":\"call\",\"routine\":\"main\"}},"
                + "{\"type\":\"parallel\",\"branches\":[{\"type\":\"set\",\"variable\":\"count\",\"value\":2},{\"type\":\"set\",\"variable\":\"other\",\"value\":{\"variable\":\"count\"}}]},"
                + "{\"type\":\"condition\",\"capability\":\"animus:flag-state\",\"arguments\":{\"name\":\"met\"},\"then\":{\"type\":\"sequence\",\"steps\":[]},\"else\":{\"type\":\"sequence\",\"steps\":[]}}]}",
                "\"variables\":{\"count\":{\"type\":\"integer\",\"initial\":0},\"other\":{\"type\":\"integer\",\"initial\":0}},"));
        valid.put("every scope", document("{\"type\":\"wait\",\"ticks\":1}", "\"variables\":{"
                + "\"a\":{\"type\":\"boolean\",\"initial\":true,\"scope\":\"execution\"},\"b\":{\"type\":\"integer\",\"initial\":1,\"scope\":\"npc\",\"lifetime\":\"transient\"},"
                + "\"c\":{\"type\":\"decimal\",\"initial\":0.5,\"scope\":\"player\",\"lifetime\":\"persistent\"},\"d\":{\"type\":\"string\",\"initial\":\"x\",\"scope\":\"relationship\",\"lifetime\":\"persistent\"},"
                + "\"e\":{\"type\":\"string\",\"initial\":\"\",\"scope\":\"global\",\"lifetime\":\"transient\"}},"));
        valid.put("every trigger", document("{\"type\":\"wait\",\"ticks\":1}", "\"triggers\":["
                + "{\"id\":\"a\",\"event\":\"interaction\",\"action\":\"any\"},{\"id\":\"b\",\"event\":\"proximity\",\"phase\":\"leave\",\"radius\":64},{\"id\":\"c\",\"event\":\"spawn\",\"cooldownTicks\":0},"
                + "{\"id\":\"d\",\"event\":\"despawn\"},{\"id\":\"e\",\"event\":\"damage\"},{\"id\":\"f\",\"event\":\"death\"},{\"id\":\"g\",\"event\":\"waypoint\",\"waypoint\":-1},"
                + "{\"id\":\"h\",\"event\":\"timer\",\"intervalTicks\":72000},{\"id\":\"i\",\"event\":\"dialogue_choice\",\"dialogue\":\"x\",\"choice\":\"y\",\"routine\":\"main\"},{\"id\":\"j\",\"event\":\"module\",\"key\":\"mod:event\"}],"));
        valid.put("module capability", document("{\"type\":\"action\",\"capability\":\"mod:anything\",\"arguments\":{\"free\":[1,2]}}", ""));
        valid.put("bound arguments skip literal checks", document("{\"type\":\"action\",\"capability\":\"animus:say\",\"arguments\":{\"text\":null},\"bindings\":{\"/text\":{\"variable\":\"line\"}}}",
                "\"variables\":{\"line\":{\"type\":\"string\",\"initial\":\"Hi\"}},"));
        valid.put("no argument capability", document("{\"type\":\"action\",\"capability\":\"animus:stop-navigation\"}", ""));
        for (Map.Entry<String, String> specimen : valid.entrySet()) {
            assertEquals(List.of(), schema(specimen.getValue()), specimen.getKey());
            OfflineBehaviorValidation.Report report = compile(specimen.getValue());
            assertTrue(report.valid(), specimen.getKey() + ": " + report.lines());
        }
    }

    @Test void invalidNpcDefinitionsFailUnderBoth() throws Exception {
        String base = Files.readString(RepositoryFiles.packs().resolve("greeter/npcs/examples/greeter.json"));
        Map<String, JsonObject> invalid = new LinkedHashMap<>();
        for (String key : List.of("uuid", "id", "group", "name", "type", "spawned", "position", "revision")) {
            JsonObject missing = JsonDocuments.object(base);
            missing.remove(key);
            invalid.put("missing " + key, missing);
        }
        JsonObject badBehavior = JsonDocuments.object(base);
        badBehavior.getAsJsonArray("behaviors").set(0, new com.google.gson.JsonPrimitive("Not A Reference"));
        invalid.put("behavior reference", badBehavior);
        for (String tags : List.of("[\"Guard\"]", "[\"guard\",\"guard\"]", "\"guard\"")) {
            JsonObject badTags = JsonDocuments.object(base);
            badTags.add("tags", JsonDocuments.object("{\"t\":" + tags + "}").get("t"));
            invalid.put("tags " + tags, badTags);
        }
        JsonObject manyTags = JsonDocuments.object(base);
        com.google.gson.JsonArray many = new com.google.gson.JsonArray();
        for (int n = 0; n < 33; n++) many.add("t" + n);
        manyTags.add("tags", many);
        invalid.put("too many tags", manyTags);
        JsonObject badSkin = JsonDocuments.object(base);
        badSkin.addProperty("skin", "short");
        invalid.put("skin", badSkin);
        JsonObject far = JsonDocuments.object(base);
        far.getAsJsonObject("position").addProperty("x", 40_000_000);
        invalid.put("position", far);
        JsonObject mount = JsonDocuments.object(base);
        mount.add("modules", JsonDocuments.object("{\"animus:mount\":{\"enabled\":true}}"));
        invalid.put("mount without vehicle", mount);
        JsonObject ride = JsonDocuments.object(base);
        ride.add("modules", JsonDocuments.object("{\"animus:riding\":{\"enabled\":true,\"speed\":99}}"));
        invalid.put("riding speed", ride);
        JsonObject route = JsonDocuments.object(base);
        route.add("modules", JsonDocuments.object("{\"animus:navigation\":{\"enabled\":true,\"route\":\"a\",\"loop\":true}}"));
        invalid.put("route with its own settings", route);
        JsonObject speed = JsonDocuments.object(base);
        speed.add("modules", JsonDocuments.object("{\"animus:navigation\":{\"enabled\":true,\"speed\":9,\"points\":[{\"x\":0,\"y\":64,\"z\":0}]}}"));
        invalid.put("navigation speed", speed);
        for (String conflict : List.of("\"route\":\"a\"", "\"points\":[{\"x\":0,\"y\":64,\"z\":0}]", "\"order\":\"random\"", "\"playback\":\"reverse\"")) {
            JsonObject recording = JsonDocuments.object(base);
            recording.add("modules", JsonDocuments.object("{\"animus:navigation\":{\"enabled\":true,\"recording\":\"market-walk\"," + conflict + "}}"));
            invalid.put("recording with " + conflict, recording);
        }
        for (String reference : List.of("\"recording\":\"Market Walk\"", "\"playback\":\"once\",\"points\":[{\"x\":0,\"y\":64,\"z\":0}]")) {
            JsonObject recording = JsonDocuments.object(base);
            recording.add("modules", JsonDocuments.object("{\"animus:navigation\":{\"enabled\":true," + reference + "}}"));
            invalid.put("navigation " + reference, recording);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pose", "{\"pose\":\"flying\"}");
        fields.put("pose for a non-player body", "{\"type\":\"minecraft:villager\",\"pose\":\"sitting\"}");
        fields.put("armor stand on a player", "{\"armorStand\":{\"small\":true}}");
        fields.put("armor stand angle", "{\"type\":\"minecraft:armor_stand\",\"armorStand\":{\"head\":[0,400,0]}}");
        fields.put("armor stand unknown part", "{\"type\":\"minecraft:armor_stand\",\"armorStand\":{\"tail\":[0,0,0]}}");
        fields.put("hologram without lines", "{\"hologram\":{\"billboard\":\"center\"}}");
        fields.put("hologram unknown field", "{\"hologram\":{\"lines\":[\"Hi\"],\"colour\":\"red\"}}");
        fields.put("hologram billboard", "{\"hologram\":{\"lines\":[\"Hi\"],\"billboard\":\"spin\"}}");
        fields.put("nameplate", "{\"nameplate\":{\"visible\":\"no\"}}");
        fields.put("owner", "{\"owner\":\"not-a-uuid\"}");
        fields.put("inventory size", "{\"inventory\":{\"size\":10,\"slots\":{}}}");
        fields.put("inventory slot key", "{\"inventory\":{\"size\":9,\"slots\":{\"first\":{\"id\":\"minecraft:stone\"}}}}");
        fields.put("equipment slot", "{\"equipment\":{\"hand\":{\"id\":\"minecraft:stone\"}}}");
        fields.put("equipment count", "{\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"count\":100}}}");
        fields.put("team colour", "{\"teamColor\":\"pink\"}");
        fields.put("movement field", "{\"movement\":{\"sped\":2}}");
        fields.put("named pose pitch", "{\"poses\":{\"rest\":{\"yaw\":0,\"pitch\":120}}}");
        fields.put("combat unknown setting", "{\"modules\":{\"animus:combat\":{\"enabled\":true,\"meele\":{}}}}");
        fields.put("combat health", "{\"modules\":{\"animus:combat\":{\"defense\":{\"maximumHealth\":0}}}}");
        fields.put("combat potion without effect", "{\"modules\":{\"animus:combat\":{\"ranged\":{\"weapon\":\"potion\"}}}}");
        fields.put("combat delete without -1", "{\"modules\":{\"animus:combat\":{\"respawn\":{\"deleteOnDeath\":true}}}}");
        fields.put("combat scan rule in ignores", "{\"modules\":{\"animus:combat\":{\"ignores\":{\"chat\":[\"hi\"]}}}}");
        fields.put("shop without id", "{\"modules\":{\"animus:shop\":{\"openOn\":\"right\"}}}");
        fields.put("gui empty", "{\"modules\":{\"animus:gui\":{}}}");
        fields.put("hire duration", "{\"modules\":{\"animus:hire\":{\"durationMinutes\":0}}}");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            JsonObject specimen = JsonDocuments.object(base);
            for (Map.Entry<String, JsonElement> entry : JsonDocuments.object(field.getValue()).entrySet()) specimen.add(entry.getKey(), entry.getValue());
            invalid.put(field.getKey(), specimen);
        }
        for (Map.Entry<String, JsonObject> specimen : invalid.entrySet()) {
            assertFalse(npcs.validate(specimen.getValue()).isEmpty(), "schema accepted: " + specimen.getKey());
            assertFalse(NpcDefinition.validate(specimen.getValue()).isEmpty(), "definition accepted: " + specimen.getKey());
        }
        JsonObject played = JsonDocuments.object(base);
        played.add("modules", JsonDocuments.object("{\"animus:navigation\":{\"enabled\":true,\"recording\":\"market-walk\",\"playback\":\"pingpong\"}}"));
        assertEquals(List.of(), npcs.validate(played));
        assertEquals(List.of(), NpcDefinition.validate(played));
        JsonObject preserved = JsonDocuments.object(base);
        preserved.add("modules", JsonDocuments.object("{\"someone:else\":{\"anything\":true}}"));
        preserved.addProperty("futureField", 1);
        preserved.add("tags", JsonDocuments.object("{\"t\":[\"guard\",\"north_gate\"]}").get("t"));
        assertEquals(List.of(), npcs.validate(preserved));
        assertEquals(List.of(), NpcDefinition.validate(preserved));
    }

    @Test void everyDeclaredNpcFieldValidatesUnderBoth() throws Exception {
        JsonObject full = JsonDocuments.object(Files.readString(RepositoryFiles.packs().resolve("greeter/npcs/examples/greeter.json")));
        JsonObject extra = JsonDocuments.object("""
                {"type": "minecraft:armor_stand", "owner": "0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a", "pose": "standing",
                 "equipment": {"head": {"id": "minecraft:carved_pumpkin"}, "main_hand": {"id": "minecraft:iron_sword", "count": 1, "components": "[enchantment_glint_override=true]"}},
                 "hologram": {"lines": ["&aHello", {"text": "Second", "marginTop": 0.1}], "lineHeight": 0.3, "viewRange": 32, "billboard": "vertical",
                              "shadowed": true, "seeThrough": false, "interpolationDelay": 0, "interpolationDuration": 5},
                 "nameplate": {"visible": false},
                 "armorStand": {"small": true, "arms": true, "basePlate": false, "invisible": false, "head": [10, 0, 0], "rightArm": [-15, 0, 10]},
                 "inventory": {"size": 18, "slots": {"0": {"id": "minecraft:bread", "count": 3}, "17": {"id": "minecraft:stone"}}},
                 "glowing": true, "teamColor": "light_purple", "gravity": false, "listed": false,
                 "poses": {"rest": {"yaw": 90, "pitch": 0}},
                 "movement": {"speed": 2, "avoidWater": false, "openDoors": true, "stepHeight": 1, "maximumFall": 3, "stuck": {"afterTicks": 100, "teleport": true}},
                 "rig": {"id": "examples:guard"},
                 "modules": {
                   "animus:combat": {"enabled": true, "targets": {"categories": ["monsters"], "all": [{"entityTypes": ["minecraft:zombie"]}],
                                     "events": {"kinds": ["player_hurts_npc"]}}, "ignores": {"players": ["Steve"]},
                                     "targeting": {"scanRange": 20, "lineOfSight": "field_of_view"}, "guard": {"followDistance": 5},
                                     "melee": {"damage": 4, "weaponDamage": {"minecraft:iron_sword": 6}},
                                     "ranged": {"enabled": true, "weapon": "potion", "effect": {"id": "minecraft:poison", "amplifier": 1}},
                                     "defense": {"maximumHealth": 40, "armor": 0.5}, "death": {"drops": [{"item": "minecraft:bone", "amount": 2, "chance": 0.5}]},
                                     "respawn": {"delayTicks": -1, "deleteOnDeath": true, "locations": [{"world": "world", "x": 0, "y": 64, "z": 0}]},
                                     "greetings": {"friendly": "Hello", "range": 8}, "squad": {"name": "north"}, "targetableByMobs": true},
                   "animus:shop": {"shop": "town:baker", "openOn": "both"},
                   "animus:gui": {"open": {"kind": "gloss", "id": "lobby/main"}, "hologram": {"id": "town:sign", "offset": [0, 2.5, 0]}},
                   "animus:hire": {"price": 50, "items": [{"id": "minecraft:emerald", "count": 2}], "durationMinutes": 120, "permission": "animus.hire.guard"}
                 }}
                """);
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) full.add(entry.getKey(), entry.getValue());
        assertEquals(List.of(), NpcDefinition.validate(full));
        assertEquals(List.of(), npcs.validate(full));
    }
}
