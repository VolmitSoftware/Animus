package art.arcane.animus.core.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Per-definition opt-in for a real player to ride this NPC. Imported definitions also need literal review acceptance. */
public record RideSettings(String permission, double speed, double leash, int lifetimeSeconds) {
    public static final String MODULE = "animus:riding";
    public static final String DEFAULT_PERMISSION = "animus.ride";
    public static final double DEFAULT_SPEED = 4, MINIMUM_SPEED = 0.5, MAXIMUM_SPEED = 8;
    public static final double DEFAULT_LEASH = 64, MINIMUM_LEASH = 4, MAXIMUM_LEASH = 256;
    public static final int DEFAULT_LIFETIME_SECONDS = 600, MINIMUM_LIFETIME_SECONDS = 30, MAXIMUM_LIFETIME_SECONDS = 3600;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "reviewAccepted", "permission", "speed", "leash", "lifetimeSeconds");

    public RideSettings {
        Objects.requireNonNull(permission, "permission");
        if (!permission.matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)*") || permission.length() > 128) throw new IllegalArgumentException(FIELD + ".permission: expected a lowercase permission node");
        if (!Double.isFinite(speed) || speed < MINIMUM_SPEED || speed > MAXIMUM_SPEED) throw new IllegalArgumentException(FIELD + ".speed: expected " + MINIMUM_SPEED + ".." + MAXIMUM_SPEED + " blocks/second");
        if (!Double.isFinite(leash) || leash < MINIMUM_LEASH || leash > MAXIMUM_LEASH) throw new IllegalArgumentException(FIELD + ".leash: expected " + MINIMUM_LEASH + ".." + MAXIMUM_LEASH + " blocks");
        if (lifetimeSeconds < MINIMUM_LIFETIME_SECONDS || lifetimeSeconds > MAXIMUM_LIFETIME_SECONDS) throw new IllegalArgumentException(FIELD + ".lifetimeSeconds: expected " + MINIMUM_LIFETIME_SECONDS + ".." + MAXIMUM_LIFETIME_SECONDS);
    }

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<RideSettings> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = flag(module, "enabled");
        boolean reviewed = flag(module, "reviewAccepted");
        if (!enabled) return Optional.empty();
        if (document.has("migration") && !reviewed) throw new IllegalArgumentException(FIELD + ".reviewAccepted: must be true for imported definitions");
        String permission = DEFAULT_PERMISSION;
        if (module.has("permission")) {
            JsonElement node = module.get("permission");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(FIELD + ".permission: expected string");
            permission = node.getAsString();
        }
        return Optional.of(new RideSettings(permission, number(module, "speed", DEFAULT_SPEED).doubleValue(), number(module, "leash", DEFAULT_LEASH).doubleValue(), whole(module, "lifetimeSeconds", DEFAULT_LIFETIME_SECONDS)));
    }
    private static boolean flag(JsonObject module, String key) {
        if (!module.has(key)) return false;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }
    private static BigDecimal number(JsonObject module, String key, double fallback) {
        if (!module.has(key)) return BigDecimal.valueOf(fallback);
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(FIELD + "." + key + ": expected number");
        return value.getAsBigDecimal();
    }
    private static int whole(JsonObject module, String key, int fallback) {
        try { return number(module, key, fallback).intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(FIELD + "." + key + ": expected whole number"); }
    }
}
