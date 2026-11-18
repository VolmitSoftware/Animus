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

    @Test void aProblemReadsAsAPlaceAPersonCanFindInTheMenu() {
        assertEquals("Slot 1 on page 1, book: a book shop's trades name the book they copy",
                ShopDocument.readable("$.pages[0].trade.0.book: a book shop's trades name the book they copy"));
        assertEquals("Slot 11 on page 2: entry id bread is used twice in this shop", ShopDocument.readable("$.pages[1].buy.10.id: entry id bread is used twice in this shop"));
        assertEquals("Page 3, name: at most 32 characters", ShopDocument.readable("$.pages[2].name: at most 32 characters"));
        assertEquals("kind: expected selling, buying, trading or book", ShopDocument.readable("$.kind: expected selling, buying, trading or book"));
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
                {"schemaVersion": 2, "revision": 1, "uuid": "1fde8840-5f7a-5a37-bc24-357183d2e276", "id": "baker", "group": "town", "name": "Baker",
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

    private static final String SPELLED = """
            {"schemaVersion": 1, "revision": 1, "id": "town:baker", "modes": ["MODE"], "defaultMode": "MODE",
             "pages": [{"name": "One", "sell": {"0": {"id": "w", "type": "TYPE", "item": {"id": "minecraft:wheat"}, "price": 2}}}]}
            """;

    @Test void typesAndModesFoldOnlyAsciiLetters() {
        for (String spelling : List.of("sell", "Sell", "SELL")) {
            ShopDocument.Result result = ShopDocument.parse("town:baker", SPELLED.replace("TYPE", spelling).replace("MODE", spelling));
            assertEquals(List.of(), result.problems(), spelling);
            assertEquals(ShopDocument.Type.SELL, result.shop().orElseThrow().entry("w").orElseThrow().type(), spelling);
            assertEquals(List.of(ShopDocument.Mode.SELL), result.shop().orElseThrow().modes(), spelling);
        }
        for (String spelling : List.of("ſell", "statıc", "STATİC", "ｓｅｌｌ")) {
            assertTrue(problems(SPELLED.replace("TYPE", spelling).replace("MODE", "sell")).contains("$.pages[0].sell.0.type: expected static, buy, sell, trade or command"), spelling);
            assertTrue(problems(SPELLED.replace("TYPE", "sell").replace("MODE", spelling)).contains("$.modes: expected buy, sell or trade"), spelling);
            assertTrue(problems(SPELLED.replace("TYPE", "sell").replace("\"defaultMode\": \"MODE\"", "\"defaultMode\": \"" + spelling + "\"").replace("MODE", "sell"))
                    .contains("$.defaultMode: expected buy, sell or trade"), spelling);
            assertEquals(Optional.empty(), ShopDocument.Type.of(spelling), spelling);
            assertEquals(Optional.empty(), ShopDocument.Mode.of(spelling), spelling);
        }
    }

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

    private static final String BOOKS = """
            {"schemaVersion": 1, "revision": 1, "id": "town:baker", "kind": "book", "owner": {"uuid": "00000000-0000-0000-0000-00000000000a"},
             "containers": [{"world": "world", "x": 10, "y": 64, "z": -3}],
             "pages": [{"name": "Books", "trade": {"0": {"id": "b", "type": "trade", "item": {"id": "minecraft:written_book"}, "book": "Tales",
               "needs": [{"item": {"id": "minecraft:emerald"}, "amount": 5}]}}}]}
            """;

    @Test void playerShopsNameTheirKind() {
        assertEquals(List.of(), problems(OWNED.replace("\"containers\"", "\"kind\": \"selling\", \"containers\"")));
        for (ShopDocument.Kind kind : ShopDocument.Kind.values())
            assertEquals(Optional.of(kind), ShopDocument.Kind.of(kind.key()), kind.key());
        assertEquals(Optional.of(ShopDocument.Kind.BUYING), ShopDocument.parse("town:baker", OWNED.replace("\"containers\"", "\"kind\": \"buying\", \"containers\"")).shop().orElseThrow().kind());
        assertTrue(problems(OWNED.replace("\"containers\"", "\"kind\": \"renting\", \"containers\"")).contains("$.kind: expected selling, buying, trading or book"));
        assertTrue(problems(ShopScreenTest.BAKERY.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"kind\": \"selling\"")).contains("$.kind: a shop kind needs containers, which hold its stock"));
    }

    @Test void aClosedShopKeepsItsReason() {
        ShopDocument shop = ShopDocument.parse("town:baker", OWNED.replace("\"containers\"", "\"closed\": \"The chest is gone\", \"containers\"")).shop().orElseThrow();
        assertEquals(Optional.of("The chest is gone"), shop.closed());
        assertEquals(Optional.empty(), ShopDocument.parse("town:baker", OWNED).shop().orElseThrow().closed());
        assertTrue(problems(OWNED.replace("\"containers\"", "\"closed\": \" \", \"containers\"")).contains("$.closed: expected nonblank string"));
        assertTrue(problems(OWNED.replace("\"containers\"", "\"closed\": \"" + "x".repeat(257) + "\", \"containers\"")).contains("$.closed: at most 256 characters"));
    }

    @Test void bookShopsCopyBooksByTitle() {
        assertEquals(List.of(), problems(BOOKS));
        ShopDocument.Entry entry = ShopDocument.parse("town:baker", BOOKS).shop().orElseThrow().entry("b").orElseThrow();
        assertEquals(Optional.of("Tales"), entry.book());
        assertEquals(List.of(), entry.gives());
        assertTrue(problems(BOOKS.replace("\"book\": \"Tales\",", "\"book\": \"Tales\", \"gives\": [{\"item\": {\"id\": \"minecraft:diamond\"}}],"))
                .contains("$.pages[0].trade.0.gives: a book entry gives a copy of its book; remove gives"));
        assertTrue(problems(BOOKS.replace("\"book\": \"Tales\",", "")).contains("$.pages[0].trade.0.book: a book shop's trades name the book they copy"));
        assertTrue(problems(BOOKS.replace("\"kind\": \"book\"", "\"kind\": \"selling\"")).contains("$.pages[0].trade.0.book: book entries belong in a book shop, kind book"));
        assertTrue(problems(OWNED.replace("\"needs\"", "\"book\": \"Tales\", \"needs\"")).contains("$.pages[0].trade.0.book: book entries belong in a book shop, kind book"));
    }

    @Test void containerShopsRunNoCommands() {
        assertTrue(problems(OWNED.replace("\"needs\"", "\"commands\": [{\"command\": \"give {player} diamond\"}], \"needs\""))
                .contains("$.pages[0].trade.0.commands: a container shop trades items for items; remove the commands"));
    }

    @Test void onlyItsOwnerOwnsAShop() {
        ShopDocument shop = ShopDocument.parse("town:baker", OWNED).shop().orElseThrow();
        assertTrue(shop.owns(java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a")));
        assertFalse(shop.owns(java.util.UUID.fromString("00000000-0000-0000-0000-00000000000b")));
        assertFalse(ShopDocument.parse("town:baker", ShopScreenTest.BAKERY).shop().orElseThrow().owns(java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a")));
    }
}
