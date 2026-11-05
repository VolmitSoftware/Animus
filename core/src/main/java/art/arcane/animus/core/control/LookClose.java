package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.*;

/**
 * Per-definition setting that turns a spawned NPC toward the nearest real player in range. It never edits the definition,
 * so imported definitions need no review acceptance.
 */
public record LookClose(double range, boolean lineOfSight, boolean whileNavigating) {
    public static final String MODULE = "animus:look-close";
    public static final double DEFAULT_RANGE = 10, MINIMUM_RANGE = 1, MAXIMUM_RANGE = 64;
    /** Each NPC looks again at most this often. */
    public static final int INTERVAL_TICKS = 5;
    /** Line-of-sight checks per NPC per look. */
    public static final int SIGHT_CHECKS = 3;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "range", "lineOfSight", "whileNavigating");

    public LookClose {
        if (!Double.isFinite(range) || range < MINIMUM_RANGE || range > MAXIMUM_RANGE) throw new IllegalArgumentException(FIELD + ".range: expected 1..64 blocks");
    }
    /** A yaw and pitch in degrees. */
    public record Facing(float yaw, float pitch) {}
    /** One player the NPC could look at. */
    public record Candidate<P>(P player, Vec3 eye, boolean viewing) {}

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<LookClose> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = flag(module, "enabled", true);
        boolean lineOfSight = flag(module, "lineOfSight", false), whileNavigating = flag(module, "whileNavigating", false);
        double range = DEFAULT_RANGE;
        if (module.has("range")) {
            JsonElement node = module.get("range");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(FIELD + ".range: expected number");
            BigDecimal exact = node.getAsBigDecimal();
            if (exact.compareTo(BigDecimal.valueOf(MINIMUM_RANGE)) < 0 || exact.compareTo(BigDecimal.valueOf(MAXIMUM_RANGE)) > 0)
                throw new IllegalArgumentException(FIELD + ".range: expected 1..64 blocks");
            range = exact.doubleValue();
        }
        LookClose settings = new LookClose(range, lineOfSight, whileNavigating);
        return enabled ? Optional.of(settings) : Optional.empty();
    }
    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }

    /** The facing from an NPC's eye toward a point, as {@code /animus look} turns it; empty when the point is the eye. */
    public static Optional<Facing> facing(Vec3 eye, Vec3 target) {
        Vec3 delta = target.subtract(eye);
        if (delta.distance(Vec3.ZERO) < .000001) return Optional.empty();
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y(), Math.hypot(delta.x(), delta.z())));
        return Optional.of(new Facing(yaw, pitch));
    }
    /** The NPC's eye: four fifths of its body height above its feet. */
    public static Vec3 eye(Vec3 feet, double bodyHeight) { return feet.add(new Vec3(0, bodyHeight * .8, 0)); }

    /**
     * Players in range, best first: players who already see the NPC before others, then nearer before farther. At most
     * {@link #SIGHT_CHECKS} are returned, so line-of-sight work stays bounded.
     */
    public <P> List<Candidate<P>> ranked(Vec3 eye, Iterable<Candidate<P>> candidates) {
        List<Candidate<P>> inRange = new ArrayList<>();
        for (Candidate<P> candidate : candidates) if (candidate.eye().distance(eye) <= range) inRange.add(candidate);
        inRange.sort(Comparator.comparing((Candidate<P> candidate) -> !candidate.viewing()).thenComparingDouble((Candidate<P> candidate) -> candidate.eye().distance(eye)));
        return List.copyOf(inRange.subList(0, Math.min(SIGHT_CHECKS, inRange.size())));
    }
}
