package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.*;

/**
 * Per-definition setting that turns a spawned NPC toward the nearest real player in range, or with {@code npcs} also
 * toward other NPCs. With {@code randomLook} enabled an NPC that has nobody to look at turns to a random facing inside
 * its ranges every {@code delayTicks}. It never edits the definition, so imported definitions need no review acceptance.
 */
public record LookClose(double range, boolean lineOfSight, boolean whileNavigating, boolean npcs, Optional<RandomLook> randomLook) {
    public static final String MODULE = "animus:look-close";
    public static final double DEFAULT_RANGE = 10, MINIMUM_RANGE = 1, MAXIMUM_RANGE = 64;
    /** Each NPC looks again at most this often. */
    public static final int INTERVAL_TICKS = 5;
    /** Line-of-sight checks per NPC per look. */
    public static final int SIGHT_CHECKS = 3;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "range", "lineOfSight", "whileNavigating", "npcs", "randomLook");
    private static final Set<String> RANDOM_KEYS = Set.of("enabled", "delayTicks", "yaw", "pitch");
    public static final int MAXIMUM_RANDOM_DELAY = 12_000;

    public LookClose {
        if (!Double.isFinite(range) || range < MINIMUM_RANGE || range > MAXIMUM_RANGE) throw new IllegalArgumentException(FIELD + ".range: expected 1..64 blocks");
        Objects.requireNonNull(randomLook);
    }
    /** Looks at players only, without random looking. */
    public LookClose(double range, boolean lineOfSight, boolean whileNavigating) { this(range, lineOfSight, whileNavigating, false, Optional.empty()); }

    /** Idle looking: every {@code delayTicks} a facing with yaw and pitch drawn evenly from their inclusive ranges, in degrees. */
    public record RandomLook(boolean enabled, int delayTicks, float yawMin, float yawMax, float pitchMin, float pitchMax) {
        public RandomLook {
            if (delayTicks < 1 || delayTicks > MAXIMUM_RANDOM_DELAY || !(yawMin <= yawMax) || !(pitchMin <= pitchMax) || yawMin < -360 || yawMax > 360
                    || pitchMin < -90 || pitchMax > 90) throw new IllegalArgumentException(FIELD + ".randomLook: values outside their ranges");
        }
        public boolean active() { return enabled; }
        public Facing pick(java.util.random.RandomGenerator random) {
            float yaw = yawMin == yawMax ? yawMin : yawMin + random.nextFloat() * (yawMax - yawMin);
            float pitch = pitchMin == pitchMax ? pitchMin : pitchMin + random.nextFloat() * (pitchMax - pitchMin);
            return new Facing(yaw, pitch);
        }
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
        boolean npcs = flag(module, "npcs", false);
        Optional<RandomLook> random = module.has("randomLook") ? Optional.of(random(module.get("randomLook"))) : Optional.empty();
        LookClose settings = new LookClose(range, lineOfSight, whileNavigating, npcs, random);
        return enabled ? Optional.of(settings) : Optional.empty();
    }
    private static RandomLook random(JsonElement value) {
        String field = FIELD + ".randomLook";
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + ": expected object");
        JsonObject random = value.getAsJsonObject();
        for (String key : random.keySet()) if (!RANDOM_KEYS.contains(key)) throw new IllegalArgumentException(field + "." + key + ": unknown field");
        boolean enabled = true;
        if (random.has("enabled")) {
            JsonElement flag = random.get("enabled");
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(field + ".enabled: expected boolean");
            enabled = flag.getAsBoolean();
        }
        int delay = 60;
        if (random.has("delayTicks")) {
            JsonElement ticks = random.get("delayTicks");
            if (!ticks.isJsonPrimitive() || !ticks.getAsJsonPrimitive().isNumber() || ticks.getAsDouble() != Math.rint(ticks.getAsDouble())
                    || ticks.getAsDouble() < 1 || ticks.getAsDouble() > MAXIMUM_RANDOM_DELAY)
                throw new IllegalArgumentException(field + ".delayTicks: expected 1.." + MAXIMUM_RANDOM_DELAY + " ticks");
            delay = ticks.getAsInt();
        }
        float[] yaw = range(random, "yaw", -360, 360, new float[]{0, 360});
        float[] pitch = range(random, "pitch", -90, 90, new float[]{0, 0});
        return new RandomLook(enabled, delay, yaw[0], yaw[1], pitch[0], pitch[1]);
    }
    private static float[] range(JsonObject random, String key, float minimum, float maximum, float[] fallback) {
        if (!random.has(key)) return fallback;
        String field = FIELD + ".randomLook." + key;
        JsonElement value = random.get(key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 2) throw new IllegalArgumentException(field + ": expected [minimum, maximum] in degrees");
        float[] result = new float[2];
        for (int i = 0; i < 2; i++) {
            JsonElement bound = value.getAsJsonArray().get(i);
            if (!bound.isJsonPrimitive() || !bound.getAsJsonPrimitive().isNumber() || !Double.isFinite(bound.getAsDouble())
                    || bound.getAsDouble() < minimum || bound.getAsDouble() > maximum)
                throw new IllegalArgumentException(field + ": expected degrees within " + (int) minimum + ".." + (int) maximum);
            result[i] = bound.getAsFloat();
        }
        if (result[0] > result[1]) throw new IllegalArgumentException(field + ": the minimum is above the maximum");
        return result;
    }
    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }

    /** The facing from an NPC's eye toward a point, as {@code /animus navigation look} turns it; empty when the point is the eye. */
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
