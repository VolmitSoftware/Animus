package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CitizensPresentationTest {
    @TempDir Path root;
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static CitizensPresentation.Result map(String type, String traits) { return map(type, traits, "{}"); }
    static CitizensPresentation.Result map(String type, String traits, String record) {
        return CitizensPresentation.map("minecraft:" + type, json(traits), json(record));
    }

    @Test void equipmentSlotsMapToDefinitionSlotsAndCosmeticsShowInstead() {
        CitizensPresentation.Result result = map("player", """
                {"equipment":{"hand":{"type":"diamond_sword","amount":1},"helmet":{"type":"minecraft:iron_helmet","amount":1,"durability":12},
                 "chestplate":{"type":"IRON_CHESTPLATE","amount":1},"leggings":{"type":"iron_leggings","amount":1},"boots":{"type":"iron_boots","amount":1},
                 "off_hand":{"type":"shield","amount":1},"body":{"type":"diamond_horse_armor","amount":1},"saddle":{"type":"saddle","amount":1},
                 "cosmetic_boots":{"type":"golden_boots","amount":1}}}
                """);
        JsonObject equipment = result.fields().getAsJsonObject("equipment");
        assertEquals("{\"id\":\"minecraft:diamond_sword\",\"count\":1}", equipment.get("main_hand").toString());
        assertEquals("{\"id\":\"minecraft:iron_helmet\",\"count\":1,\"components\":\"[damage=12]\"}", equipment.get("head").toString());
        assertEquals("minecraft:iron_chestplate", equipment.getAsJsonObject("chest").get("id").getAsString());
        assertEquals("minecraft:iron_leggings", equipment.getAsJsonObject("legs").get("id").getAsString());
        assertEquals("minecraft:golden_boots", equipment.getAsJsonObject("feet").get("id").getAsString());
        assertEquals("minecraft:shield", equipment.getAsJsonObject("off_hand").get("id").getAsString());
        assertEquals("minecraft:diamond_horse_armor", equipment.getAsJsonObject("body").get("id").getAsString());
        assertEquals("minecraft:saddle", equipment.getAsJsonObject("saddle").get("id").getAsString());
        assertEquals(List.of("/traits/equipment/cosmetic_boots: cosmetic item shown in feet instead of /traits/equipment/boots"), result.warnings());
    }

    @Test void unreadableItemMetadataIsKeptWithAnActionableWarning() {
        String sword = "{\"type\":\"diamond_sword\",\"amount\":1,\"meta\":{\"encoded-meta\":{\"\":\"rO0AB\"}},\"editable_components\":{\"display_name\":\"Excalibur\",\"edited\":false}}";
        CitizensPresentation.Result result = map("player", "{\"equipment\":{\"hand\":" + sword + "}}");
        JsonObject hand = result.fields().getAsJsonObject("equipment").getAsJsonObject("main_hand");
        assertEquals("minecraft:diamond_sword", hand.get("id").getAsString());
        assertEquals(JsonParser.parseString(sword), hand.get("imported"));
        assertEquals(List.of("/traits/equipment/hand: the item's name, enchantments and other data could not be read, so it shows as a plain minecraft:diamond_sword;"
                + " set the item again in the editor if it matters"), result.warnings());
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/equipment/hand/meta").status());
    }

    @Test void unusableItemsStayOnlyInTheReviewCopy() {
        CitizensPresentation.Result result = map("player", "{\"equipment\":{\"hand\":{\"type\":\"AIR\",\"amount\":0},\"helmet\":{\"amount\":1},\"boots\":{\"type\":\"Bad Name\"},\"legs\":7}}");
        assertFalse(result.fields().has("equipment"));
        assertEquals(List.of("/traits/equipment/boots: item type is not a namespaced id; kept for review",
                "/traits/equipment/helmet: item has no type; kept for review",
                "/traits/equipment/legs: unknown Citizens equipment slot; kept for review"), result.warnings());
    }

    @Test void inventoryContentsAreStoredSlotForSlot() {
        CitizensPresentation.Result result = map("player", "{\"inventory\":{\"0\":{\"type\":\"bread\",\"amount\":16},\"40\":{\"type\":\"compass\",\"amount\":1},\"3\":{\"type\":\"air\",\"amount\":1}}}");
        JsonObject inventory = result.fields().getAsJsonObject("inventory");
        assertEquals(45, inventory.get("size").getAsInt());
        assertEquals("{\"0\":{\"id\":\"minecraft:bread\",\"count\":16},\"40\":{\"id\":\"minecraft:compass\",\"count\":1}}", inventory.get("slots").toString());
        assertEquals(List.of(), result.warnings());
        CitizensPresentation.Result outside = map("player", "{\"inventory\":{\"72\":{\"type\":\"bread\",\"amount\":1}}}");
        assertFalse(outside.fields().has("inventory"));
        assertEquals(List.of("/traits/inventory/72: slot outside 0..71; kept for review"), outside.warnings());
    }

    @Test void hologramLinesKeepCitizensOrderAndBounds() {
        CitizensPresentation.Result result = map("player", """
                {"hologramtrait":{"lines":{"1":{"text":"second","margin":{"top":0.5}},"0":"&6first","10":"third"},"lineHeight":0.4,"viewRange":32}}
                """);
        assertEquals("{\"lines\":[\"third\",{\"text\":\"second\",\"marginTop\":0.5},\"&6first\"],\"lineHeight\":0.4,\"viewRange\":32.0}", result.fields().get("hologram").toString());
        assertEquals(List.of(), result.warnings());
        CitizensPresentation.Result tooMany = map("player", "{\"hologramtrait\":{\"lines\":{" + String.join(",", java.util.stream.IntStream.range(0, 18).mapToObj(i -> "\"" + i + "\":\"l" + i + "\"").toList()) + "}}}");
        assertEquals(16, tooMany.fields().getAsJsonObject("hologram").getAsJsonArray("lines").size());
        assertEquals("l0", tooMany.fields().getAsJsonObject("hologram").getAsJsonArray("lines").get(15).getAsString());
        assertEquals(List.of("/traits/hologramtrait/lines: only the bottom 16 lines are shown; move the others into fewer lines"), tooMany.warnings());
        CitizensPresentation.Result longLine = map("player", "{\"hologramtrait\":{\"lines\":{\"0\":\"" + "a".repeat(300) + "\"}}}");
        assertEquals(256, longLine.fields().getAsJsonObject("hologram").getAsJsonArray("lines").get(0).getAsString().length());
        assertEquals(List.of("/traits/hologramtrait/lines/0: shortened to 256 characters; shorten the line in the editor"), longLine.warnings());
    }

    @Test void posesSneakSitAndArmorStandMap() {
        assertEquals("sneaking", map("player", "{\"sneak\":{\"sneaking\":true}}").fields().get("pose").getAsString());
        assertFalse(map("player", "{\"sneak\":{\"sneaking\":false}}").fields().has("pose"));
        assertEquals("sneaking", map("player", "{\"sneaking\":{\"sneaking\":true}}").fields().get("pose").getAsString());
        assertEquals("sitting", map("player", "{\"sittrait\":{\"sittingAt\":{\"world\":\"w\",\"x\":1,\"y\":2,\"z\":3}}}").fields().get("pose").getAsString());
        CitizensPresentation.Result mounted = map("player", "{\"sittrait\":{\"sittingAt\":{\"x\":1}},\"mounttrait\":{\"mountedon\":\"00000000-0000-0000-0000-000000000002\"}}");
        assertFalse(mounted.fields().has("pose"));
        assertEquals(List.of("/traits/sittrait: a mounted NPC rides instead of sitting; kept for review"), mounted.warnings());
        CitizensPresentation.Result horse = map("horse", "{\"sneak\":{\"sneaking\":true}}");
        assertFalse(horse.fields().has("pose"));
        assertEquals(List.of(), horse.warnings());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, horse.notes().get("/traits/sneak/sneaking").status());
        CitizensPresentation.Result stand = map("armor_stand", """
                {"armorstandtrait":{"head":{"x":0.5,"y":0,"z":-0.25},"hasarms":false,"small":true,"visible":false,"marker":true,"gravity":false}}
                """);
        JsonObject look = stand.fields().getAsJsonObject("armorStand");
        assertEquals("{\"small\":true,\"arms\":false,\"basePlate\":true,\"invisible\":true,\"head\":[28.648,0.0,-14.324]}", look.toString());
        assertEquals(List.of("/traits/armorstandtrait: marker is kept for review"), stand.warnings());
        assertFalse(stand.fields().get("gravity").getAsBoolean(), "a stand without gravity hovers");
        assertTrue(stand.converted().contains("/traits/armorstandtrait/gravity"));
        CitizensPresentation.Result defaults = map("armor_stand", "{\"armorstandtrait\":{}}");
        assertEquals("{\"small\":false,\"arms\":true,\"basePlate\":true,\"invisible\":false}", defaults.fields().get("armorStand").toString());
    }

    @Test void defaultNamedPoseSetsTheFacing() {
        CitizensPresentation.Result result = map("player", "{\"poses\":{\"defaultPose\":\"North\",\"list\":{\"0\":\"north;180.0;-10.5\",\"1\":\"east;-90;0\"}}}");
        assertEquals(180.0, result.facing().orElseThrow().yaw());
        assertEquals(-10.5, result.facing().orElseThrow().pitch());
        assertEquals(List.of(), result.warnings(), "the named poses convert, so nothing is left to review");
        assertTrue(map("player", "{\"poses\":{\"list\":{\"0\":\"north;180;0\"}}}").facing().isEmpty());
        assertEquals(List.of("/traits/poses: default pose missing is not in the list; kept for review"),
                map("player", "{\"poses\":{\"defaultPose\":\"missing\",\"list\":{\"0\":\"north;180;0\"}}}").warnings());
    }

    @Test void ownerAndNameplateVisibilityMap() {
        assertEquals("00000000-0000-0000-0000-00000000000a", map("player", "{\"owner\":{\"uuid\":\"00000000-0000-0000-0000-00000000000a\"}}").fields().get("owner").getAsString());
        assertFalse(map("player", "{\"owner\":{\"owner\":\"SERVER\"}}").fields().has("owner"));
        assertEquals(List.of("/traits/owner: no player UUID; kept for review"), map("player", "{\"owner\":{\"uuid\":\"nope\"}}").warnings());
        assertEquals("{\"visible\":false}", map("player", "{}", "{\"metadata\":{\"nameplate-visible\":false}}").fields().get("nameplate").toString());
        assertEquals("{\"visible\":false}", map("player", "{}", "{\"metadata\":{\"nameplate-visible\":\"false\"}}").fields().get("nameplate").toString());
        assertEquals(List.of("/metadata/nameplate-visible: hover-only nameplates show always"), map("player", "{}", "{\"metadata\":{\"nameplate-visible\":\"hover\"}}").warnings());
    }

    @Test void importedDefinitionsCarryTheMappedFieldsAndStayValid() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), """
                npc:
                  '1':
                    uuid: 00000000-0000-0000-0000-000000000001
                    name: Captain of the Northern Watch
                    traits:
                      type: PLAYER
                      location: {world: world, x: 1, y: 64, z: 2, yaw: 0, pitch: 0}
                      equipment: {hand: {type: iron_sword, amount: 1}}
                      inventory: {'0': {type: bread, amount: 3}}
                      hologramtrait: {lines: {'0': Hello}}
                      sneak: {sneaking: true}
                      owner: {uuid: 00000000-0000-0000-0000-00000000000a}
                      poses: {defaultPose: north, list: {'0': 'north;180;5'}}
                """);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("out"), Map.of());
        assertEquals(1, preview.valid(), preview.summary().toString());
        NpcDefinition definition = NpcDefinition.parse(Files.readString(preview.directory().resolve("npcs/world/citizens-1.json")));
        NpcAppearance appearance = NpcAppearance.of(definition);
        assertEquals("minecraft:iron_sword", appearance.equipment().get(EquipmentSlot.MAIN_HAND).id());
        assertEquals(List.of("Hello"), appearance.hologram());
        assertEquals(NpcPose.SNEAKING, appearance.pose());
        assertEquals("minecraft:bread", NpcInventory.of(definition).slots().get(0).id());
        assertEquals("00000000-0000-0000-0000-00000000000a", definition.owner().orElseThrow().toString());
        JsonObject position = definition.json().getAsJsonObject("position");
        assertEquals(180, position.get("yaw").getAsDouble());
        assertEquals(5, position.get("pitch").getAsDouble());
        JsonObject review = definition.json().getAsJsonObject("modules").getAsJsonObject("citizens:review");
        assertTrue(review.has("equipment") && review.has("inventory") && review.has("hologramtrait") && review.has("owner"));
        JsonObject fields = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/1.json"))).getAsJsonObject("fields");
        assertEquals("CONVERTED", fields.get("/traits/equipment/hand/type").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/owner/uuid").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/hologramtrait/lines/0").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/sneak/sneaking").getAsString());
        assertEquals(CitizensImporter.CONVERTER_VERSION, preview.converterVersion());
        assertEquals(13, CitizensImporter.CONVERTER_VERSION);
    }

    @Test void hologramRendererSettingsMarginsAndListInventoriesMap() {
        CitizensPresentation.Result result = map("player", """
                {"hologramtrait":{"lines":[{"text":"bottom","margin":{"top":0.0,"bottom":0.25},"renderer":{"billboard":"VERTICAL","seeThrough":true,"shadowed":false,
                 "interpolationDelay":0,"interpolationDuration":3}},{"text":"top","margin":{"top":0.0,"bottom":0.0},"renderer":{"billboard":"FIXED","seeThrough":true}}]},
                 "inventory":[{"type":"bread","amount":2},null,{"type":"stone","amount":1}]}
                """);
        assertEquals("{\"lines\":[\"top\",{\"text\":\"bottom\",\"marginBottom\":0.25}],\"billboard\":\"vertical\",\"seeThrough\":true,\"interpolationDuration\":3}",
                result.fields().get("hologram").toString());
        assertTrue(result.converted().contains("/traits/hologramtrait/lines/0/renderer/shadowed"));
        assertTrue(result.converted().contains("/traits/hologramtrait/lines/1/renderer/seeThrough"));
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/hologramtrait/lines/1/renderer/billboard").status());
        assertEquals("{\"0\":{\"id\":\"minecraft:bread\",\"count\":2},\"2\":{\"id\":\"minecraft:stone\",\"count\":1}}",
                result.fields().getAsJsonObject("inventory").get("slots").toString());
        assertTrue(result.converted().contains("/traits/inventory/1"));
    }

    @Test void horseSaddleAndArmourFillEmptySlots() {
        CitizensPresentation.Result result = map("horse", "{\"horsemodifiers\":{\"saddle\":{\"type\":\"saddle\",\"amount\":1},\"armor\":{\"type\":\"iron_horse_armor\",\"amount\":1},\"color\":\"WHITE\"}}");
        JsonObject equipment = result.fields().getAsJsonObject("equipment");
        assertEquals("minecraft:saddle", equipment.getAsJsonObject("saddle").get("id").getAsString());
        assertEquals("minecraft:iron_horse_armor", equipment.getAsJsonObject("body").get("id").getAsString());
        assertTrue(result.converted().contains("/traits/horsemodifiers/saddle/type"));
    }
}
