package art.arcane.animus.core.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;

/**
 * The dtlTraders {@code trader} trait of a Citizens NPC as an {@code animus:shop} module. Version 6 and 5 traits name
 * a shop in {@code guiName}; an old version 3 trait without {@code converted-to-v5} keeps its stock in the trait, which
 * the shop importer turns into the shop {@code converted-<npc name>}. Both importers name shops through this class.
 */
public final class TraderMapping {
    public static final String NAMESPACE = "dtltraders";

    private TraderMapping() {}

    public record Result(Optional<JsonObject> module, Optional<String> warning) {}

    /** A shop name as an Animus path segment: lowercase letters, digits, {@code _} and {@code -}. */
    public static String path(String name) {
        String lowered = name.toLowerCase(Locale.ROOT).replaceAll("&[0-9a-fk-or]", "").replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-");
        lowered = lowered.replaceAll("^-+|-+$", "");
        if (lowered.isEmpty()) lowered = "shop";
        return lowered.length() > 64 ? lowered.substring(0, 64) : lowered;
    }

    public static String shopId(String name) { return NAMESPACE + ":" + path(name); }

    /** The shop an old version 3 trader becomes, named after its NPC like dtlTraders 5 did. */
    public static String convertedName(String npcName) {
        return "converted-" + npcName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** True for a version 3 trait that still carries its own stock. */
    public static boolean legacy(JsonObject trait) {
        return trait.has("type") && !(trait.has("converted-to-v5") && trait.get("converted-to-v5").isJsonPrimitive() && trait.get("converted-to-v5").getAsBoolean());
    }

    public static Result map(JsonElement value, String npcName) {
        if (value == null || !value.isJsonObject()) return new Result(Optional.empty(), Optional.of("trader trait is not a mapping; kept for review"));
        JsonObject trait = value.getAsJsonObject();
        String name;
        if (legacy(trait)) name = convertedName(npcName);
        else {
            JsonElement gui = trait.get("guiName");
            if (gui == null || gui.isJsonNull() || !gui.isJsonPrimitive() || gui.getAsString().isBlank() || gui.getAsString().equals("null"))
                return new Result(Optional.empty(), Optional.of("trader trait names no shop; the NPC has no shop"));
            name = gui.getAsString();
        }
        JsonObject module = new JsonObject();
        module.addProperty("shop", shopId(name));
        module.addProperty("openOn", "right");
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "dtlTraders");
        imported.addProperty("shopName", name);
        module.add("imported", imported);
        return new Result(Optional.of(module), Optional.empty());
    }
}
