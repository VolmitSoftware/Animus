package art.arcane.animus.core.shop;

import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adds one offer to a shop file from items a player holds: what {@code /animus shop stock} writes. The offer goes in the
 * first free slot of a page's grid for its mode, and the file is checked as the catalog reads it before it is saved.
 */
public final class ShopOffers {
    private ShopOffers() {}

    /** An offer added: the changed shop document, the slot it took and the entry id it got. */
    public record Added(JsonObject document, int slot, String entry) {}

    /** A new shop file with one empty three-row page named Main. */
    public static JsonObject empty(String id) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", ShopDocument.SCHEMA_VERSION);
        document.addProperty("revision", 1);
        document.addProperty("id", id);
        JsonArray pages = new JsonArray();
        JsonObject page = new JsonObject();
        page.addProperty("name", "Main");
        page.addProperty("rows", 3);
        pages.add(page);
        document.add("pages", pages);
        return document;
    }

    /**
     * Adds {@code item} to page {@code page} (from 1) of {@code shop}. A buy offer sells the item for {@code price}, a sell
     * offer buys it for {@code price}, and a trade gives the item for {@code cost}. The revision goes up by one and the
     * mode joins {@code modes} when the shop lists them. Throws with the reply when the offer cannot be added.
     */
    public static Added add(JsonObject shop, ShopDocument.Mode mode, NpcItem item, Optional<NpcItem> cost, Optional<BigDecimal> price, int page) throws IOException {
        JsonObject document = shop.deepCopy();
        String id = document.has("id") && document.get("id").isJsonPrimitive() ? document.get("id").getAsString() : "";
        JsonArray pages = document.has("pages") && document.get("pages").isJsonArray() ? document.getAsJsonArray("pages") : new JsonArray();
        if (page < 1 || page > pages.size()) throw new IOException(id + " has " + pages.size() + (pages.size() == 1 ? " page" : " pages") + "; give page=1 to " + pages.size());
        if (mode == ShopDocument.Mode.TRADE && cost.isEmpty()) throw new IOException("A trade takes what you hold in your off hand; hold the cost there");
        if (mode != ShopDocument.Mode.TRADE && price.isEmpty()) throw new IOException("A " + mode.key() + " offer needs price=, such as price=10");
        if (price.isPresent() && price.get().signum() < 0) throw new IOException("price= is 0 or more");
        JsonObject target = pages.get(page - 1).getAsJsonObject();
        int rows = target.has("rows") && target.get("rows").isJsonPrimitive() ? target.get("rows").getAsInt() : 3;
        JsonObject grid = target.has(mode.key()) && target.get(mode.key()).isJsonObject() ? target.getAsJsonObject(mode.key()) : new JsonObject();
        int slot = 0;
        while (slot < rows * 9 && grid.has(String.valueOf(slot))) slot++;
        if (slot == rows * 9) throw new IOException("Page " + page + " of " + id + " has no free " + mode.key() + " slot; give another page= or add a page in the web editor");
        String entry = entryId(item, used(pages));
        JsonObject offer = new JsonObject();
        offer.addProperty("id", entry);
        offer.addProperty("type", mode.key());
        offer.add("item", item.toJson());
        if (mode == ShopDocument.Mode.TRADE) {
            offer.add("needs", goods(cost.get()));
            offer.add("gives", goods(item));
        } else offer.addProperty("price", price.get());
        grid.add(String.valueOf(slot), offer);
        target.add(mode.key(), grid);
        if (document.has("modes") && document.get("modes").isJsonArray()) {
            JsonArray modes = document.getAsJsonArray("modes");
            boolean listed = false;
            for (JsonElement listedMode : modes) listed |= listedMode.isJsonPrimitive() && listedMode.getAsString().equals(mode.key());
            if (!listed) modes.add(mode.key());
        }
        long revision = document.has("revision") && document.get("revision").isJsonPrimitive() ? document.get("revision").getAsLong() : 0;
        document.addProperty("revision", revision + 1);
        List<String> problems = ShopDocument.parse(id, document).problems();
        if (!problems.isEmpty()) throw new IOException("The shop would not load: " + problems.getFirst());
        return new Added(document, slot, entry);
    }

    /** One stack of goods for a trade: the item without its count, and the count as the amount. */
    private static JsonArray goods(NpcItem item) {
        JsonObject held = item.toJson();
        held.remove("count");
        JsonObject goods = new JsonObject();
        goods.add("item", held);
        goods.addProperty("amount", item.count());
        JsonArray list = new JsonArray();
        list.add(goods);
        return list;
    }

    /** Every entry id the shop already uses, on any page and in any mode. */
    private static Set<String> used(JsonArray pages) {
        Set<String> ids = new HashSet<>();
        for (JsonElement page : pages) {
            if (!page.isJsonObject()) continue;
            for (ShopDocument.Mode mode : ShopDocument.Mode.values()) {
                JsonElement grid = page.getAsJsonObject().get(mode.key());
                if (grid == null || !grid.isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> slot : grid.getAsJsonObject().entrySet())
                    if (slot.getValue().isJsonObject() && slot.getValue().getAsJsonObject().has("id")) ids.add(slot.getValue().getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    /** The item's path, such as {@code bread}, numbered {@code bread-2} and on when that id is taken. */
    static String entryId(NpcItem item, Set<String> used) {
        String path = item.id().substring(item.id().indexOf(':') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        String base = path.isEmpty() ? "offer" : path.substring(0, Math.min(path.length(), 56)), id = base;
        for (int n = 2; used.contains(id); n++) id = base + "-" + n;
        return id;
    }
}
