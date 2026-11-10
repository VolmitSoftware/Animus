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

    @Test void bareOfferItemsAreMinecraftItems() {
        ShopDocument shop = ShopDocument.parse("town:baker", ShopScreenTest.BAKERY.replace("\"minecraft:bread\"", "\"bread\"")).shop().orElseThrow();
        assertEquals("minecraft:bread", shop.entry("bread").orElseThrow().item().id());
        assertEquals(List.of(), problems(ShopScreenTest.BAKERY.replace("\"minecraft:cake\"", "\"cake\"")));
    }

    @Test void problemsNameTheirField() {
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"price\": 12,", "\"price\": -1,")).contains("$.pages[0].buy.10.price: expected a price from 0 to 1000000000000 with at most 4 decimal places"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"id\": \"cake\"", "\"id\": \"bread\"")).contains("$.pages[1].buy.0.id: entry id bread is used twice in this shop"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"26\"", "\"27\"")).contains("$.pages[0].buy.27: slot 27 is outside a 3-row page (0..26)"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"type\": \"sell\"", "\"type\": \"trade\"")).contains("$.pages[0].sell.10.type: trade entries belong in a trade grid"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"town:baker\"", "\"town:cakes\"")).contains("$.id: id town:cakes does not match its file, which names town:baker"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).contains("$.schemaVersion: shop schemaVersion 2 is not supported; Animus reads version 1"));
    }

    @Test void levelsAreAWholeCostOnBuyCommandAndTradeEntries() {
        String buy = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"pages\":[{\"name\":\"One\",\"buy\":{\"0\":{\"id\":\"b\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":0,\"levels\":LEVELS}}}]}";
        assertEquals(List.of(), problems(buy.replace("LEVELS", "5")));
        assertEquals(5, ShopDocument.parse("town:baker", buy.replace("LEVELS", "5")).shop().orElseThrow().entry("b").orElseThrow().levels());
        assertTrue(problems(buy.replace("LEVELS", "-1")).contains("$.pages[0].buy.0.levels: expected whole levels from 0 to 100000"));
        assertTrue(problems(buy.replace("LEVELS", "1.5")).contains("$.pages[0].buy.0.levels: expected whole levels from 0 to 100000"));
        String sell = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"pages\":[{\"sell\":{\"0\":{\"id\":\"s\",\"type\":\"sell\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":1,\"levels\":2}}}]}";
        assertTrue(problems(sell).contains("$.pages[0].sell.0.levels: levels are a cost of buy, command and trade entries"));
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

    private static final String OWNED = """
            {"schemaVersion": 1, "revision": 1, "id": "town:baker", "owner": {"uuid": "00000000-0000-0000-0000-00000000000a", "name": "Alex"},
             "containers": [{"world": "world", "x": 10, "y": 64, "z": -3}],
             "pages": [{"name": "Stock", "trade": {"0": {"id": "t", "type": "trade", "item": {"id": "minecraft:diamond"},
               "needs": [{"item": {"id": "minecraft:emerald"}, "amount": 3}], "gives": [{"item": {"id": "minecraft:diamond"}, "amount": 1}]}}}]}
            """;

    @Test void ownedShopsReadTheirOwnerAndContainers() {
        assertEquals(List.of(), problems(OWNED));
        ShopDocument shop = ShopDocument.parse("town:baker", OWNED).shop().orElseThrow();
        assertEquals("00000000-0000-0000-0000-00000000000a", shop.owner().orElseThrow().uuid().toString());
        assertEquals(Optional.of("Alex"), shop.owner().orElseThrow().name());
        assertEquals(List.of(new ShopDocument.Container("world", 10, 64, -3)), shop.containers());
    }

    @Test void containerShopsTradeItemsForItemsAndNeedAnOwner() {
        assertTrue(problems(OWNED.replace("\"owner\": {\"uuid\": \"00000000-0000-0000-0000-00000000000a\", \"name\": \"Alex\"},", "")).contains("$.owner: a shop with containers needs an owner"));
        assertTrue(problems(OWNED.replace("\"needs\"", "\"price\": 5, \"needs\"")).contains("$.pages[0].trade.0.price: a container shop trades items for items; remove the price"));
        assertTrue(problems(OWNED.replace("\"needs\"", "\"stock\": {\"maximum\": 4}, \"needs\"")).contains("$.pages[0].trade.0.stock: a container shop's stock is its containers"));
        String buying = OWNED.replace("\"trade\": {\"0\": {\"id\": \"t\", \"type\": \"trade\",", "\"buy\": {\"0\": {\"id\": \"t\", \"type\": \"buy\", \"price\": 1,")
                .replace(",\n   \"needs\": [{\"item\": {\"id\": \"minecraft:emerald\"}, \"amount\": 3}], \"gives\": [{\"item\": {\"id\": \"minecraft:diamond\"}, \"amount\": 1}]", "");
        assertTrue(problems(buying).contains("$.pages[0].buy.0.type: a container shop has only trade and static entries"), problems(buying).toString());
        assertTrue(problems(OWNED.replace("\"y\": 64", "\"y\": 99999")).contains("$.containers[0].y: expected integer -2048..2048"));
        assertTrue(problems(OWNED.replace("\"uuid\": \"00000000-0000-0000-0000-00000000000a\"", "\"uuid\": \"alex\"")).contains("$.owner.uuid: expected canonical player UUID"));
    }
}
