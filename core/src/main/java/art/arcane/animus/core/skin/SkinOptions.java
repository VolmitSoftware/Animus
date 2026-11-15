package art.arcane.animus.core.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code animus:skin} module of a player body. {@code refresh}: the skin came from a player name, and the server
 * looks that name up again on {@code [skins] refreshMinutes} and puts the new skin on when the player changed theirs.
 * {@code fromName}: with no skin set, the NPC wears the skin of the Minecraft account named like it.
 */
public record SkinOptions(boolean refresh, boolean fromName) {
    public static final String MODULE = "animus:skin";
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "refresh", "fromName");

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<SkinOptions> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = flag(module, "enabled", true), refresh = flag(module, "refresh", false), fromName = flag(module, "fromName", false);
        return enabled && (refresh || fromName) ? Optional.of(new SkinOptions(refresh, fromName)) : Optional.empty();
    }

    /** Sets or clears one option of the module in place; the module goes when no option is left. */
    public static void set(JsonObject document, String key, boolean on) {
        if (!KEYS.contains(key) || key.equals("enabled")) throw new IllegalArgumentException("not an animus:skin option: " + key);
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        JsonObject module = modules.has(MODULE) && modules.get(MODULE).isJsonObject() ? modules.getAsJsonObject(MODULE) : new JsonObject();
        if (on) module.addProperty(key, true); else module.remove(key);
        module.remove("enabled");
        if (module.isEmpty()) modules.remove(MODULE); else modules.add(MODULE, module);
        if (!modules.isEmpty() || document.has("modules")) document.add("modules", modules);
    }

    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }
}
