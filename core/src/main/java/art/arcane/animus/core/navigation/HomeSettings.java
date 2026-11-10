package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code animus:home} module: an NPC that stands idle away from its home, in its own world, for {@code delayTicks}
 * and at least {@code distance} blocks from it, teleports or walks back. An NPC that follows a route, plays a recording
 * or rides is not idle.
 *
 * @param distance blocks, or 0 to return from any distance
 */
public record HomeSettings(Vec3 position, Float yaw, Float pitch, int delayTicks, double distance, Return strategy) {
    public static final String MODULE = "animus:home";
    public static final int MAXIMUM_DELAY = 72_000;
    public static final double MAXIMUM_DISTANCE = 256;
    /** Closer than this counts as home. */
    public static final double AT_HOME = 0.1;
    /** How often an NPC checks whether it should go home, in ticks. */
    public static final int CHECK_TICKS = 20;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "x", "y", "z", "yaw", "pitch", "delayTicks", "distance", "return");

    public enum Return {
        TELEPORT, WALK;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    public HomeSettings {
        Objects.requireNonNull(position); Objects.requireNonNull(strategy);
        if (delayTicks < 0 || delayTicks > MAXIMUM_DELAY || !Double.isFinite(distance) || distance < 0 || distance > MAXIMUM_DISTANCE)
            throw new IllegalArgumentException(FIELD + ": values outside their ranges");
    }

    /** Whether an NPC at {@code at} that has been idle for {@code idleTicks} goes home now. */
    public boolean due(Vec3 at, long idleTicks) {
        double away = at.distance(position);
        return away >= AT_HOME && idleTicks >= delayTicks && away >= distance;
    }

    /** An NPC that fought within this many ticks is still fighting, not idle. */
    public static final int FIGHT_TICKS = 100;

    /** Whether the definition's running combat guards a player or NPC: a guard stands still between its steps but is never idle. */
    public static boolean guards(JsonObject document) {
        try {
            return art.arcane.animus.core.combat.CombatSettings.parse(document).map((art.arcane.animus.core.combat.CombatSettings combat) -> combat.enabled()
                    && (combat.reviewAccepted() || !document.has("migration")) && (combat.guard().player().isPresent() || combat.guard().npc().isPresent())).orElse(false);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<HomeSettings> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = true;
        if (module.has("enabled")) {
            JsonElement flag = module.get("enabled");
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + ".enabled: expected boolean");
            enabled = flag.getAsBoolean();
        }
        Vec3 position = new Vec3(number(module, "x", 29_999_984), number(module, "y", 29_999_984), number(module, "z", 29_999_984));
        Float yaw = module.has("yaw") ? (float) number(module, "yaw", 360) : null;
        Float pitch = module.has("pitch") ? (float) number(module, "pitch", 90) : null;
        int delay = 0;
        if (module.has("delayTicks")) {
            double ticks = number(module, "delayTicks", MAXIMUM_DELAY);
            if (ticks < 0 || ticks != Math.rint(ticks)) throw new IllegalArgumentException(FIELD + ".delayTicks: expected 0.." + MAXIMUM_DELAY + " ticks");
            delay = (int) ticks;
        }
        double distance = module.has("distance") ? number(module, "distance", MAXIMUM_DISTANCE) : 0;
        if (distance < 0) throw new IllegalArgumentException(FIELD + ".distance: expected 0.." + (int) MAXIMUM_DISTANCE + " blocks");
        Return strategy = Return.TELEPORT;
        if (module.has("return")) {
            JsonElement choice = module.get("return");
            if (!choice.isJsonPrimitive() || !choice.getAsJsonPrimitive().isString() || !Set.of("teleport", "walk").contains(choice.getAsString()))
                throw new IllegalArgumentException(FIELD + ".return: expected teleport or walk");
            strategy = Return.valueOf(choice.getAsString().toUpperCase(Locale.ROOT));
        }
        HomeSettings settings = new HomeSettings(position, yaw, pitch, delay, distance, strategy);
        return enabled ? Optional.of(settings) : Optional.empty();
    }
    private static double number(JsonObject module, String key, double bound) {
        JsonElement value = module.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()) || Math.abs(value.getAsDouble()) > bound)
            throw new IllegalArgumentException(FIELD + "." + key + ": expected a number within " + (long) -bound + ".." + (long) bound);
        return value.getAsDouble();
    }
}
