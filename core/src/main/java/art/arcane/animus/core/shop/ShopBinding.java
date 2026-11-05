package art.arcane.animus.core.shop;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code modules["animus:shop"]} in an NPC definition: the shop a click opens. A sneaking administrator's click keeps
 * its selection meaning and opens nothing. An imported section opens nothing until its {@code "reviewAccepted"} is
 * literal {@code true}.
 */
public record ShopBinding(String shop, OpenOn openOn) {
    public static final String MODULE = "animus:shop";
    public enum OpenOn { RIGHT, LEFT, BOTH }

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
            if (!Set.of("shop", "openOn", "imported", "reviewAccepted").contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
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
        boolean reviewed = false;
        if (module.has("reviewAccepted")) {
            JsonElement accepted = module.get("reviewAccepted");
            if (!accepted.isJsonPrimitive() || !accepted.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(path + ".reviewAccepted: expected boolean");
            reviewed = accepted.getAsBoolean();
        }
        if (definition.has("migration") && module.has("imported") && !reviewed) return Optional.empty();
        return Optional.of(new ShopBinding(shop.getAsString(), openOn));
    }
}
