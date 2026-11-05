package art.arcane.animus.core.shop;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopDocumentTest {
    private static List<String> problems(String json) { return ShopDocument.parse("town:baker", json).problems(); }

    @Test void theBakeryParses() {
        ShopDocument shop = ShopDocument.parse("town:baker", ShopScreenTest.BAKERY).shop().orElseThrow();
        assertEquals(3, shop.pages().size());
        assertEquals(ShopDocument.Mode.BUY, shop.defaultMode());
        assertTrue(shop.hasCommands());
        assertEquals(Optional.of(ShopDocument.Type.SELL), shop.entry("wheat").map(ShopDocument.Entry::type));
    }

    @Test void problemsNameTheirField() {
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"price\": 12,", "\"price\": -1,")).contains("$.pages[0].buy.10.price: expected a price from 0 to 1000000000000 with at most 4 decimal places"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"id\": \"cake\"", "\"id\": \"bread\"")).contains("$.pages[1].buy.0.id: entry id bread is used twice in this shop"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"26\"", "\"27\"")).contains("$.pages[0].buy.27: slot 27 is outside a 3-row page (0..26)"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"type\": \"sell\"", "\"type\": \"trade\"")).contains("$.pages[0].sell.10.type: trade entries belong in a trade grid"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"town:baker\"", "\"town:cakes\"")).contains("$.id: id town:cakes does not match its file, which names town:baker"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).contains("$.schemaVersion: shop schemaVersion 2 is not supported; Animus reads version 1"));
    }

    @Test void tradesNeedBothLists() {
        String trade = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"pages\":[{\"trade\":{\"0\":{\"id\":\"t\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"}}}}]}";
        assertTrue(problems(trade).contains("$.pages[0].trade.0.needs: expected a non-empty array of items"));
        assertTrue(problems(trade).contains("$.pages[0].trade.0.gives: expected a non-empty array of items"));
    }

    @Test void discountsAndLimitsAreChecked() {
        String entry = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"pages\":[{\"buy\":{\"0\":{\"id\":\"b\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":1,"
                + "\"discount\":{\"price\":0.5,\"start\":\"2026-12-02T00:00:00Z\",\"end\":\"2026-12-01T00:00:00Z\"},\"limit\":{}}}}]}";
        assertTrue(problems(entry).contains("$.pages[0].buy.0.discount.end: must be after start"));
        assertTrue(problems(entry).contains("$.pages[0].buy.0.limit: set perPlayer, global or both"));
    }

    @Test void npcDefinitionsBindShopsThroughTheirModule() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"schemaVersion": 1, "revision": 1, "uuid": "1fde8840-5f7a-5a37-bc24-357183d2e276", "id": "baker", "group": "town", "name": "Baker",
                 "type": "minecraft:player", "spawned": true, "position": {"world": "world", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                 "modules": {"animus:shop": {"shop": "town:baker", "openOn": "both"}}}
                """);
        ShopBinding binding = ShopBinding.of(NpcDefinition.parse(definition.toString())).orElseThrow();
        assertEquals("town:baker", binding.shop());
        assertTrue(binding.opensOn(true) && binding.opensOn(false));
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").addProperty("openOn", "middle");
        assertEquals(List.of("$.modules[\"animus:shop\"].openOn: expected right, left or both"), NpcDefinition.validate(definition));
    }
}
