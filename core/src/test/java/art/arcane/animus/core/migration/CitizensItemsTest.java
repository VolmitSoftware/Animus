package art.arcane.animus.core.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensItemsTest {
    private static JsonObject item(String type, String meta) {
        JsonObject item = new JsonObject();
        item.addProperty("type", type);
        item.addProperty("amount", 1);
        item.addProperty("meta", meta);
        return item;
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    @Test void javaObjectStreamsDecodeToPlainJson() {
        String meta = MetaStreams.itemMeta(fields("meta-type", "UNSPECIFIC", "Unbreakable", true, "map-id", 7,
                "lore", MetaStreams.list(List.of("{\"text\":\"a\"}", "{\"text\":\"b\"}")), "enchants", MetaStreams.map(fields("minecraft:sharpness", 5))));
        assertEquals(JsonParser.parseString("{\"==\":\"ItemMeta\",\"meta-type\":\"UNSPECIFIC\",\"Unbreakable\":true,\"map-id\":7,"
                + "\"lore\":[\"{\\\"text\\\":\\\"a\\\"}\",\"{\\\"text\\\":\\\"b\\\"}\"],\"enchants\":{\"minecraft:sharpness\":5}}"), JavaSerialized.decode(meta).orElseThrow());
        assertTrue(JavaSerialized.decode("not base64!").isEmpty());
        assertTrue(JavaSerialized.decode("AAAA").isEmpty());
    }

    @Test void shieldPatternsNameAndUnbreakableBecomeComponents() {
        String meta = MetaStreams.itemMeta(fields("meta-type", "SHIELD", "display-name", "{\"text\":\"Guard Shield\",\"italic\":false}", "base-color", "BLACK",
                "patterns", MetaStreams.list(List.of(MetaStreams.serializable("Pattern", fields("color", "WHITE", "pattern", "minecraft:border")),
                        MetaStreams.serializable("Pattern", fields("color", "RED", "pattern", "minecraft:flower")))), "Unbreakable", true));
        CitizensItems.Result result = CitizensItems.read(item("shield", meta), 0);
        assertEquals("[custom_name={\"text\":\"Guard Shield\",\"italic\":false},unbreakable={},base_color=\"black\","
                + "banner_patterns=[{pattern:\"minecraft:border\",color:\"white\"},{pattern:\"minecraft:flower\",color:\"red\"}]]", result.components().orElseThrow());
        assertEquals(Set.of(), result.unmapped());
        assertTrue(result.readable());
    }

    @Test void enchantmentsLoreModelsPotionsMapsAndDamage() {
        String meta = MetaStreams.itemMeta(fields("lore", MetaStreams.list(List.of("Plain &6legacy")), "enchants", MetaStreams.map(fields("DAMAGE_ALL", 3, "minecraft:unbreaking", 2)),
                "custom-model-data", 1234, "item-model", "itemsadder:ruby_sword", "repair-cost", 4, "Damage", 9, "enchantment-glint-override", false,
                "PublicBukkitValues", MetaStreams.map(fields("itemsadder:id", "\"ruby_sword\""))));
        CitizensItems.Result result = CitizensItems.read(item("diamond_sword", meta), 0);
        assertEquals("[damage=9,lore=[\"Plain &6legacy\"],enchantments={\"minecraft:sharpness\":3,\"minecraft:unbreaking\":2},enchantment_glint_override=false,"
                + "repair_cost=4,custom_model_data={floats:[1234f]},item_model=\"itemsadder:ruby_sword\",custom_data={PublicBukkitValues:{\"itemsadder:id\":\"ruby_sword\"}}]",
                result.components().orElseThrow());
        String potion = MetaStreams.itemMeta(fields("meta-type", "POTION", "potion-type", "minecraft:healing", "custom-color",
                MetaStreams.serializable("Color", fields("RED", 255, "GREEN", 0, "BLUE", 16))));
        assertEquals("[potion_contents={potion:\"minecraft:healing\",custom_color:16711696}]", CitizensItems.read(item("potion", potion), 0).components().orElseThrow());
        String map = MetaStreams.itemMeta(fields("meta-type", "MAP", "map-id", 7));
        assertEquals("[damage=3,map_id=7]", CitizensItems.read(item("filled_map", map), 3).components().orElseThrow());
        String leather = MetaStreams.itemMeta(fields("meta-type", "LEATHER_ARMOR", "color", MetaStreams.serializable("Color", fields("ALPHA", 255, "RED", 1, "GREEN", 2, "BLUE", 3))));
        assertEquals("[dyed_color=66051]", CitizensItems.read(item("leather_boots", leather), 0).components().orElseThrow());
    }

    @Test void unknownMetaKeysAreReportedAndEverythingKnownStillShows() {
        String meta = MetaStreams.itemMeta(fields("display-name", "Old &cName", "internal", "H4sIAAAA", "ItemFlags", MetaStreams.list(List.of("HIDE_ENCHANTS"))));
        CitizensItems.Result result = CitizensItems.read(item("stick", meta), 0);
        assertEquals("[custom_name=\"Old &cName\"]", result.components().orElseThrow());
        assertEquals(Set.of("internal", "ItemFlags"), result.unmapped());
    }

    @Test void citizensEditedNamesOverrideTheMetaAndUnreadableMetaIsSaid() {
        JsonObject edited = item("stick", MetaStreams.itemMeta(fields("display-name", "{\"text\":\"Meta\"}")));
        JsonObject components = new JsonObject();
        components.addProperty("display_name", "Edited \"name\"");
        components.addProperty("lore", "one<br>two");
        components.addProperty("edited", true);
        edited.add("editable_components", components);
        assertEquals("[custom_name=\"Edited \\\"name\\\"\",lore=[\"one\",\"two\"]]", CitizensItems.read(edited, 0).components().orElseThrow());
        CitizensItems.Result broken = CitizensItems.read(item("stick", "rO0ABXQAAA=="), 0);
        assertFalse(broken.readable());
        assertTrue(broken.components().isEmpty());
        JsonObject nested = item("stick", "x");
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("", MetaStreams.itemMeta(fields("map-id", 2)));
        nested.add("meta", wrapper);
        assertEquals("[map_id=2]", CitizensItems.read(nested, 0).components().orElseThrow());
    }
}
