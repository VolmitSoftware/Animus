package art.arcane.animus.core.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code animus:mirror} module: each viewer sees a player-bodied NPC wearing their own skin ({@code skin}, on by
 * default), with {@code name} carrying their own name and with {@code equipment} holding and wearing what they did when the
 * NPC came into view. Other viewers see theirs, and nobody sees the NPC's own.
 */
public record MirrorSettings(boolean skin, boolean name, boolean equipment) {
    public static final String MODULE = "animus:mirror";
    private static final String FIELD = "$.modules." + MODULE;

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<MirrorSettings> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!Set.of("enabled", "skin", "name", "equipment").contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = flag(module, "enabled", true), skin = flag(module, "skin", true), name = flag(module, "name", false), equipment = flag(module, "equipment", false);
        return enabled && (skin || name || equipment) ? Optional.of(new MirrorSettings(skin, name, equipment)) : Optional.empty();
    }
    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }
}
