package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog summaries: the plain facts a list shows before the document is read. */
class EditorSummariesTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static List<String> strings(JsonArray array) { return array.asList().stream().map((com.google.gson.JsonElement element) -> element.getAsString()).toList(); }

    @Test
    void npc_carriesPlainNamePositionFolderTagsSkinAndOnlyItsFeatures() {
        String skin = "a".repeat(64);
        JsonObject summary = EditorSummaries.of(DocumentKind.NPC, "town/guard", json("""
                {"schemaVersion":2,"revision":3,"id":"guard","name":"&f&lTown <gradient:#ff0000:#00ff00>guard</gradient>  &#ff00ffone","type":"minecraft:player",
                 "spawned":true,"position":{"world":"world","x":-7.5,"y":64,"z":0.5,"yaw":0,"pitch":0},"skin":"%s","tags":["gate","night"],
                 "behaviors":["town:greet"],"rig":{"id":"town:golem"},"hologram":{"lines":["&6Hi"]},
                 "modules":{"animus:navigation":{"route":"patrol"},"animus:schedule":{"locations":[]},"animus:combat":{"enabled":true},
                            "animus:shop":{"shop":"town:bakery"},"animus:mount":{"vehicle":"horse"}}}
                """.formatted(skin)));
        assertEquals("Town guard one", summary.get("name").getAsString());
        assertEquals("minecraft:player", summary.get("type").getAsString());
        assertEquals("world", summary.get("world").getAsString());
        assertEquals(-7.5, summary.get("x").getAsDouble());
        assertEquals(64, summary.get("y").getAsDouble());
        assertEquals(0.5, summary.get("z").getAsDouble());
        assertTrue(summary.get("spawned").getAsBoolean());
        assertEquals("town", summary.get("folder").getAsString());
        assertEquals(List.of("gate", "night"), strings(summary.getAsJsonArray("tags")));
        assertEquals(List.of("route", "schedule", "combat", "shop", "behavior", "rig", "hologram", "mount"), strings(summary.getAsJsonArray("features")));
        assertEquals(skin, summary.get("skin").getAsString());
    }

    @Test
    void npc_featuresComeFromWhatTheDocumentActuallyHas() {
        JsonObject recording = EditorSummaries.of(DocumentKind.NPC, "walker", json("""
                {"name":"Walker","modules":{"animus:navigation":{"recording":"stroll"},"animus:combat":{"enabled":false},"animus:mount":{"enabled":false},
                 "animus:gui":{"open":{"kind":"shop","id":"town:bakery"},"hologram":{"id":"town:sign"}}},"behaviors":[],"hologram":{"lines":[]}}
                """));
        assertEquals(List.of("recording", "shop", "hologram"), strings(recording.getAsJsonArray("features")));
        assertEquals("", recording.get("folder").getAsString());
        JsonObject inline = EditorSummaries.of(DocumentKind.NPC, "a/b/c", json("{\"modules\":{\"animus:navigation\":{\"points\":[{\"x\":1,\"y\":2,\"z\":3}]}}}"));
        assertEquals(List.of("route"), strings(inline.getAsJsonArray("features")));
        assertEquals("a/b", inline.get("folder").getAsString());
        assertEquals(1, inline.get("points").getAsInt());
        JsonObject bare = EditorSummaries.of(DocumentKind.NPC, "town/blank", json("{\"name\":\"&f\",\"position\":\"nowhere\",\"tags\":7}"));
        assertFalse(bare.has("name"), "a name that is only colour codes leaves the id to show");
        assertFalse(bare.has("world"));
        assertFalse(bare.has("tags"));
        assertEquals(0, bare.getAsJsonArray("features").size());
    }

    @Test
    void npc_pointsCountOnlyItsOwnRoute() {
        String points = "[{\"x\":1,\"y\":2,\"z\":3},{\"x\":4,\"y\":2,\"z\":3},{\"x\":4,\"y\":2,\"z\":6},{\"x\":1,\"y\":2,\"z\":6}]";
        JsonObject own = EditorSummaries.of(DocumentKind.NPC, "town/porter", json("{\"modules\":{\"animus:navigation\":{\"points\":" + points + "}}}"));
        assertEquals(4, own.get("points").getAsInt());
        JsonObject named = EditorSummaries.of(DocumentKind.NPC, "town/guard", json("{\"modules\":{\"animus:navigation\":{\"route\":\"patrol\",\"points\":" + points + "}}}"));
        assertFalse(named.has("points"), "a named route counts in the route document");
        JsonObject recorded = EditorSummaries.of(DocumentKind.NPC, "town/walker", json("{\"modules\":{\"animus:navigation\":{\"recording\":\"stroll\",\"points\":" + points + "}}}"));
        assertFalse(recorded.has("points"));
        assertFalse(EditorSummaries.of(DocumentKind.NPC, "town/idle", json("{\"modules\":{\"animus:navigation\":{\"points\":[]}}}")).has("points"));
    }

    @Test
    void npc_longNamesAndManyTagsAreBounded() {
        JsonArray tags = new JsonArray();
        for (int index = 0; index < 32; index++) tags.add("t" + index);
        JsonObject source = new JsonObject();
        source.addProperty("name", "x".repeat(300));
        source.add("tags", tags);
        JsonObject summary = EditorSummaries.of(DocumentKind.NPC, "n", source);
        assertEquals(64, summary.get("name").getAsString().length());
        assertEquals(16, summary.getAsJsonArray("tags").size());
    }

    @Test
    void skin_decodesTheTextureUrlAndArmModel() {
        String textures = "{\"profileName\":\"Steve\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\",\"metadata\":{\"model\":\"slim\"}}}}";
        JsonObject source = new JsonObject();
        source.addProperty("textureRaw", Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)));
        JsonObject summary = EditorSummaries.of(DocumentKind.SKIN, JsonDocuments.hash("x"), source);
        assertEquals("https://textures.minecraft.net/texture/abc123", summary.get("texture").getAsString());
        assertTrue(summary.get("slim").getAsBoolean());
        JsonObject foreign = new JsonObject();
        foreign.addProperty("textureRaw", Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/x\"}}}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(EditorSummaries.of(DocumentKind.SKIN, "s", foreign).has("texture"), "only Mojang texture URLs pass");
        JsonObject broken = new JsonObject();
        broken.addProperty("textureRaw", "%%%");
        assertEquals(new JsonObject(), EditorSummaries.of(DocumentKind.SKIN, "s", broken));
    }

    @Test
    void otherKinds_carryANameAndOneCheapFigure() {
        JsonObject shop = EditorSummaries.of(DocumentKind.SHOP, "town:bakery", json("""
                {"title":"&6Bakery","pages":[{"trade":{"11":{"id":"a"},"13":{"id":"b"}},"buy":{"1":{"id":"c"}}},{"sell":{"4":{"id":"d"}}}]}
                """));
        assertEquals("Bakery", shop.get("name").getAsString());
        assertEquals(4, shop.get("offers").getAsInt());
        assertEquals(2, shop.get("pages").getAsInt());
        assertEquals(3, EditorSummaries.of(DocumentKind.INVENTORY, "town:counter", json("{\"title\":\"&8Counter\",\"resolution\":\"9x3\"}")).get("rows").getAsInt());
        assertEquals(2, EditorSummaries.of(DocumentKind.INVENTORY, "town:masked", json("{\"mask\":[\"#########\",\"#   #   #\"]}")).get("rows").getAsInt());
        JsonObject hologram = EditorSummaries.of(DocumentKind.HOLOGRAM, "shop:sign", json("""
                {"anchor":{"world":"world","position":[4.5,65,2.5]},"lines":["|animation.sparkle|&6Bakery",{"text":"&7Fresh"},{"item":"minecraft:bread"}]}
                """));
        assertEquals("Bakery", hologram.get("name").getAsString());
        assertEquals(3, hologram.get("lines").getAsInt());
        assertEquals("world", hologram.get("world").getAsString());
        assertEquals(65, hologram.get("y").getAsDouble());
        JsonObject behavior = EditorSummaries.of(DocumentKind.BEHAVIOR, "town:greet", json("""
                {"triggers":[{"id":"a","event":"interaction"},{"id":"b","event":"proximity"},{"id":"c","event":"interaction"}]}
                """));
        assertEquals(List.of("interaction", "proximity"), strings(behavior.getAsJsonArray("triggers")));
        assertFalse(behavior.has("name"));
        assertEquals(3, EditorSummaries.of(DocumentKind.ROUTE, "patrol", json("{\"points\":[{},{},{}]}")).get("points").getAsInt());
        JsonObject recording = EditorSummaries.of(DocumentKind.RECORDING, "stroll", json("{\"description\":\"Morning walk\",\"durationTicks\":610,\"world\":\"world\"}"));
        assertEquals("Morning walk", recording.get("name").getAsString());
        assertEquals(30.5, recording.get("seconds").getAsDouble());
        assertEquals("world", recording.get("world").getAsString());
        JsonObject quest = EditorSummaries.of(DocumentKind.QUEST, "town:bread", json("{\"title\":\"<gold>Bread run\",\"stages\":[{},{}]}"));
        assertEquals("Bread run", quest.get("name").getAsString());
        assertEquals(2, quest.get("stages").getAsInt());
        assertEquals(4, EditorSummaries.of(DocumentKind.RIG, "town:golem", json("{\"bones\":[{},{},{},{}]}")).get("bones").getAsInt());
        assertEquals(2.0, EditorSummaries.of(DocumentKind.ANIMATION, "town:wave", json("{\"durationTicks\":40}")).get("seconds").getAsDouble());
        assertEquals(2, EditorSummaries.of(DocumentKind.GROUP, "guards", json("{\"values\":{\"skin\":\"x\",\"tags\":[]}}")).get("fields").getAsInt());
        JsonObject pack = EditorSummaries.of(DocumentKind.PACK, "shop", json("{\"name\":\"Bakery shop\",\"version\":\"2.0.0\"}"));
        assertEquals("Bakery shop", pack.get("name").getAsString());
        assertEquals("2.0.0", pack.get("version").getAsString());
        assertEquals(2, EditorSummaries.of(DocumentKind.SERVER_BEHAVIORS, "server-behaviors", json("{\"behaviors\":[\"a:b\",\"c:d\"]}")).get("behaviors").getAsInt());
        assertEquals(3, EditorSummaries.of(DocumentKind.TEXT_ANIMATION, "shop:sparkle", json("{\"frames\":[\"a\",\"b\",\"c\"]}")).get("frames").getAsInt());
        assertEquals(new JsonObject(), EditorSummaries.of(DocumentKind.PRESETS, "presets", json("{\"presets\":{}}")));
        assertEquals(new JsonObject(), EditorSummaries.of(DocumentKind.MENU, "shop:menu", json("{\"components\":[]}")));
    }
}
