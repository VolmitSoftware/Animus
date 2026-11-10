package art.arcane.animus.core.shop;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code modules["animus:shop"]} in an NPC definition: the shop a click opens. A sneaking administrator's click keeps
 * its selection meaning and opens nothing. {@code style} overrides the shop's window style for this NPC, and {@code villager} gives a
 * villager body the profession and level its trading screen title shows; other bodies ignore it.
 */
public record ShopBinding(String shop, OpenOn openOn, Optional<ShopDocument.Style> style, Optional<Villager> villager) {
    public static final String MODULE = "animus:shop";
    public enum OpenOn { RIGHT, LEFT, BOTH }
    /** A villager body's profession, such as {@code minecraft:farmer}, and its level from 1 (novice) to 5 (master). */
    public record Villager(String profession, int level) {}

    public boolean opensOn(boolean attack) {
        return switch (openOn) { case RIGHT -> !attack; case LEFT -> attack; case BOTH -> true; };
    }

    public static Optional<ShopBinding> of(NpcDefinition definition) { return parse(definition.json()); }

    /** Throws with the exact field when the module is malformed. */
    public static Optional<ShopBinding> parse(JsonObject definition) {
        if (!definition.has("modules") || !definition.get("modules").isJsonObject()) return Optional.empty();
        JsonElement value = definition.getAsJsonObject("modules").get(MODULE);
        if (value == null) return Optional.empty();
        String path = "$.modules[\"" + MODULE + "\"]";
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet())
            if (!Set.of("shop", "openOn", "style", "villager", "imported").contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        JsonElement shop = module.get("shop");
        if (shop == null || !shop.isJsonPrimitive() || !shop.getAsJsonPrimitive().isString() || !shop.getAsString().matches(ShopDocument.ID))
            throw new IllegalArgumentException(path + ".shop: expected a shop id such as town:baker");
        OpenOn openOn = OpenOn.RIGHT;
        if (module.has("openOn")) {
            JsonElement text = module.get("openOn");
            if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString() || !Set.of("right", "left", "both").contains(text.getAsString()))
                throw new IllegalArgumentException(path + ".openOn: expected right, left or both");
            openOn = OpenOn.valueOf(text.getAsString().toUpperCase(Locale.ROOT));
        }
        Optional<ShopDocument.Style> style = Optional.empty();
        if (module.has("style")) {
            JsonElement text = module.get("style");
            style = text.isJsonPrimitive() && text.getAsJsonPrimitive().isString() ? ShopDocument.Style.of(text.getAsString()) : Optional.empty();
            if (style.isEmpty()) throw new IllegalArgumentException(path + ".style: expected native or chest");
        }
        Villager villager = villager(module.get("villager"), path + ".villager");
        boolean villagerBody = definition.has("type") && definition.get("type").isJsonPrimitive() && definition.get("type").getAsString().equals("minecraft:villager");
        return Optional.of(new ShopBinding(shop.getAsString(), openOn, style, villagerBody ? Optional.of(villager) : Optional.empty()));
    }

    private static Villager villager(JsonElement value, String path) {
        if (value == null) return new Villager("minecraft:none", 1);
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected {\"profession\": \"minecraft:farmer\", \"level\": 1}");
        JsonObject object = value.getAsJsonObject();
        for (String key : object.keySet()) if (!Set.of("profession", "level").contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        String profession = "minecraft:none";
        if (object.has("profession")) {
            JsonElement text = object.get("profession");
            if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString() || !MinecraftIds.valid(text.getAsString()))
                throw new IllegalArgumentException(path + ".profession: expected a profession id such as minecraft:farmer");
            profession = MinecraftIds.normalize(text.getAsString());
        }
        int level = 1;
        if (object.has("level")) {
            JsonElement number = object.get("level");
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !number.getAsString().matches("[1-5]"))
                throw new IllegalArgumentException(path + ".level: expected integer 1..5");
            level = number.getAsInt();
        }
        return new Villager(profession, level);
    }
}
