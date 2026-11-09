package art.arcane.animus.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShopOffersTest {
    static final NpcItem BREAD = new NpcItem("minecraft:bread", 4, Optional.empty(), Optional.empty());
    static final NpcItem EMERALDS = new NpcItem("minecraft:emerald", 3, Optional.empty(), Optional.empty());

    @Test void aHeldItemBecomesABuyOfferInTheFirstFreeSlot() throws IOException {
        ShopOffers.Added first = ShopOffers.add(ShopOffers.empty("town:baker"), ShopDocument.Mode.BUY, BREAD, Optional.empty(), Optional.of(new BigDecimal("12.5")), 1);
        ShopOffers.Added second = ShopOffers.add(first.document(), ShopDocument.Mode.BUY, BREAD, Optional.empty(), Optional.of(BigDecimal.TEN), 1);
        assertEquals(0, first.slot());
        assertEquals(1, second.slot());
        assertEquals("bread", first.entry());
        assertEquals("bread-2", second.entry(), "entry ids stay unique in the shop");
        JsonObject offer = second.document().getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("buy").getAsJsonObject("0");
        assertEquals(JsonParser.parseString("{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\",\"count\":4},\"price\":12.5}"), offer);
        assertEquals(3, second.document().get("revision").getAsLong());
        ShopDocument parsed = ShopDocument.parse("town:baker", second.document()).shop().orElseThrow();
        assertEquals(2, parsed.pages().getFirst().grids().get(ShopDocument.Mode.BUY).size());
    }

    @Test void aTradeTakesTheOffHandAsItsCost() throws IOException {
        JsonObject shop = ShopOffers.empty("town:baker");
        shop.add("modes", JsonParser.parseString("[\"buy\"]"));
        ShopOffers.Added added = ShopOffers.add(shop, ShopDocument.Mode.TRADE, BREAD, Optional.of(EMERALDS), Optional.empty(), 1);
        JsonObject offer = added.document().getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("trade").getAsJsonObject("0");
        assertEquals(JsonParser.parseString("[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":3}]"), offer.get("needs"));
        assertEquals(JsonParser.parseString("[{\"item\":{\"id\":\"minecraft:bread\"},\"amount\":4}]"), offer.get("gives"));
        assertEquals(JsonParser.parseString("[\"buy\",\"trade\"]"), added.document().get("modes"), "the trade mode is enabled");
        assertTrue(ShopDocument.parse("town:baker", added.document()).problems().isEmpty());
    }

    @Test void refusalsNameWhatToChange() {
        JsonObject shop = ShopOffers.empty("town:baker");
        assertEquals("A buy offer needs price=, such as price=10",
                assertThrows(IOException.class, () -> ShopOffers.add(shop, ShopDocument.Mode.BUY, BREAD, Optional.empty(), Optional.empty(), 1)).getMessage());
        assertEquals("A trade takes what you hold in your off hand; hold the cost there",
                assertThrows(IOException.class, () -> ShopOffers.add(shop, ShopDocument.Mode.TRADE, BREAD, Optional.empty(), Optional.empty(), 1)).getMessage());
        assertEquals("town:baker has 1 page; give page=1 to 1",
                assertThrows(IOException.class, () -> ShopOffers.add(shop, ShopDocument.Mode.SELL, BREAD, Optional.empty(), Optional.of(BigDecimal.ONE), 2)).getMessage());
        JsonObject full = ShopOffers.empty("town:baker");
        full.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("rows", 1);
        assertThrows(IOException.class, () -> {
            JsonObject next = full;
            for (int slot = 0; slot < 10; slot++) next = ShopOffers.add(next, ShopDocument.Mode.SELL, BREAD, Optional.empty(), Optional.of(BigDecimal.ONE), 1).document();
        });
    }
}
