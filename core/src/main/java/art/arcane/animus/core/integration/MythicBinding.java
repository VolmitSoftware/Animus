package art.arcane.animus.core.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An NPC definition's {@code "modules": {"mythic:mob": {"mob": "SkeletonKing"}}}: the MythicMobs mob type the NPC was
 * made from. While {@code enabled} (the default) the mob's own skills run on the NPC's matching events, such as its
 * {@code ~onInteract} skills when a player right-clicks it. Servers without MythicMobs keep the module as written.
 */
public record MythicBinding(String mob, boolean enabled) {
    public static final String MODULE = "mythic:mob";
    /** A Mythic mob type name: the key of its section in a Mythic mob file. */
    public static final String MOB_NAME = "[A-Za-z0-9_.-]{1,64}";
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "mob");

    public MythicBinding {
        Objects.requireNonNull(mob);
        if (!mob.matches(MOB_NAME)) throw new IllegalArgumentException(FIELD + ".mob: expected a Mythic mob type of 1-64 letters, digits, _, . or -");
    }

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<MythicBinding> parse(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        JsonElement mob = module.get("mob");
        if (mob == null || !mob.isJsonPrimitive() || !mob.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(FIELD + ".mob: expected a Mythic mob type such as SkeletonKing");
        boolean enabled = true;
        JsonElement flag = module.get("enabled");
        if (flag != null) {
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + ".enabled: expected boolean");
            enabled = flag.getAsBoolean();
        }
        return Optional.of(new MythicBinding(mob.getAsString(), enabled));
    }
}
