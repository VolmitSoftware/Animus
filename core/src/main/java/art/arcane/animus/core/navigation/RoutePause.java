package art.arcane.animus.core.navigation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code pause} of an NPC's {@code animus:navigation} module: its route stands still for {@code ticks} when a player
 * comes within {@code playerRange}, or, with {@code onClick}, when a player right-clicks it. After a pause starts, no
 * new one starts for {@code lockoutTicks}.
 *
 * @param playerRange blocks, or 0 when players nearby never pause the route
 */
public record RoutePause(double playerRange, boolean onClick, int ticks, int lockoutTicks) {
    public static final int DEFAULT_TICKS = 20, MAXIMUM_TICKS = 12_000, MAXIMUM_LOCKOUT = 72_000;
    public static final double MAXIMUM_RANGE = 64;
    /** How often a route checks for players near it, in ticks. */
    public static final int CHECK_TICKS = 5;
    private static final String FIELD = "$.modules.animus:navigation.pause";

    public RoutePause {
        if (!Double.isFinite(playerRange) || playerRange < 0 || playerRange > MAXIMUM_RANGE || ticks < 1 || ticks > MAXIMUM_TICKS || lockoutTicks < 0 || lockoutTicks > MAXIMUM_LOCKOUT)
            throw new IllegalArgumentException(FIELD + ": values outside their ranges");
    }

    /** The pause the definition's navigation module declares, enabled or not. */
    public static Optional<RoutePause> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject()) return Optional.empty();
        JsonElement navigation = modules.getAsJsonObject().get("animus:navigation");
        if (navigation == null || !navigation.isJsonObject() || !navigation.getAsJsonObject().has("pause")) return Optional.empty();
        JsonElement value = navigation.getAsJsonObject().get("pause");
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject pause = value.getAsJsonObject();
        for (String key : pause.keySet())
            if (!Set.of("playerRange", "onClick", "ticks", "lockoutTicks").contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        double range = 0;
        if (pause.has("playerRange")) {
            JsonElement node = pause.get("playerRange");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber() || !Double.isFinite(node.getAsDouble()) || node.getAsDouble() < 0 || node.getAsDouble() > MAXIMUM_RANGE)
                throw new IllegalArgumentException(FIELD + ".playerRange: expected 0..64 blocks");
            range = node.getAsDouble();
        }
        boolean click = false;
        if (pause.has("onClick")) {
            JsonElement node = pause.get("onClick");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + ".onClick: expected boolean");
            click = node.getAsBoolean();
        }
        return Optional.of(new RoutePause(range, click, ticks(pause, "ticks", DEFAULT_TICKS, 1, MAXIMUM_TICKS), ticks(pause, "lockoutTicks", 0, 0, MAXIMUM_LOCKOUT)));
    }
    private static int ticks(JsonObject pause, String key, int fallback, int minimum, int maximum) {
        if (!pause.has(key)) return fallback;
        JsonElement node = pause.get(key);
        if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber() || node.getAsDouble() != Math.rint(node.getAsDouble())
                || node.getAsDouble() < minimum || node.getAsDouble() > maximum)
            throw new IllegalArgumentException(FIELD + "." + key + ": expected " + minimum + ".." + maximum + " ticks");
        return node.getAsInt();
    }

    /** Pause timing for one NPC's route. Owner thread only. */
    public static final class Clock {
        private long until = Long.MIN_VALUE, lockedUntil = Long.MIN_VALUE, nextCheck = Long.MIN_VALUE;

        /** Whether the route stands still at {@code tick}. */
        public boolean paused(long tick) { return tick < until; }
        /** Starts a pause unless one started within the lockout; true when it started. */
        public boolean pause(RoutePause settings, long tick) {
            if (tick < lockedUntil) return false;
            until = tick + settings.ticks();
            lockedUntil = tick + settings.lockoutTicks();
            return true;
        }
        /** Whether a player check is due; it is then not due again for {@link #CHECK_TICKS}. */
        public boolean checkDue(long tick) {
            if (tick < nextCheck) return false;
            nextCheck = tick + CHECK_TICKS;
            return true;
        }
    }
}
