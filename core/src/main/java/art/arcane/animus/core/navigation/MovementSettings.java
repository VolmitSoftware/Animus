package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * How one NPC walks wherever it walks: the definition's {@code movement} object. A route's own speed wins over
 * {@code speed}. {@code avoidWater} false lets a walking route cross water at the surface; {@code fly} makes routes fly.
 * {@code openDoors} lets walking routes pass closed wooden doors, fence gates and trapdoors, which the NPC opens and
 * closes behind it. {@code stepHeight} and {@code maximumFall} replace the body's own climb and drop. Each plan reaches at most {@code pathRange};
 * a longer leg is walked in {@link LongLeg} segments of that reach. A point within {@code arrivalMargin} counts as reached, and {@code stuck} decides what an NPC
 * that has not moved closer for {@code afterTicks} does: teleport to the point it walks to, or stop.
 * <p>
 * The definition's {@code attributes} fill what this object leaves out: {@code movement_speed} the speed,
 * {@code step_height} the climb and {@code safe_fall_distance} the drop. {@code jump_strength} and {@code gravity} give
 * {@code jumpHeight}, and {@code gravity} is the fall acceleration of an idle body.
 */
public record MovementSettings(OptionalDouble speed, boolean avoidWater, boolean fly, boolean openDoors, OptionalDouble stepHeight, OptionalInt maximumFall,
                               OptionalDouble pathRange, OptionalDouble arrivalMargin, Optional<Stuck> stuck, OptionalDouble jumpHeight, double gravity) {
    public static final MovementSettings DEFAULT = new MovementSettings(OptionalDouble.empty(), true, false, false, OptionalDouble.empty(), OptionalInt.empty(),
            OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty(), OptionalDouble.empty(), IdleFall.GRAVITY);
    /** Blocks per second a player walks per unit of {@code movement_speed}, and a mob per unit of its square. */
    public static final double PLAYER_WALK = 43.17, MOB_WALK = 44.05;
    /** The navigation speed range an attribute speed is kept within, in blocks per second. */
    public static final double MINIMUM_SPEED = 1, MAXIMUM_SPEED = 10;
    public static final double MAX_ARRIVAL_MARGIN = 4;
    public static final int MAX_STUCK_TICKS = 12_000;
    private static final Set<String> FIELDS = Set.of("speed", "avoidWater", "fly", "openDoors", "stepHeight", "maximumFall", "pathRange", "arrivalMargin", "stuck");

    /** What an NPC does after {@code afterTicks} without getting closer to the point it walks to. */
    public record Stuck(int afterTicks, boolean teleport) {
        public Stuck {
            if (afterTicks < 1 || afterTicks > MAX_STUCK_TICKS) throw new IllegalArgumentException("$.movement.stuck.afterTicks: expected whole 1.." + MAX_STUCK_TICKS + " ticks");
        }
    }

    public MovementSettings {
        Objects.requireNonNull(speed); Objects.requireNonNull(stepHeight); Objects.requireNonNull(maximumFall);
        Objects.requireNonNull(pathRange); Objects.requireNonNull(arrivalMargin); Objects.requireNonNull(stuck); Objects.requireNonNull(jumpHeight);
    }

    /** @throws IllegalArgumentException naming every malformed field */
    public static MovementSettings parse(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        MovementSettings settings = read(definition, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return settings;
    }

    /** Reads {@code movement}, appending each malformed field to {@code errors}. */
    public static MovementSettings read(JsonObject definition, List<String> errors) {
        JsonElement value = definition.get("movement");
        if (value != null && !value.isJsonObject()) errors.add("$.movement: expected object");
        JsonObject movement = value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        OptionalDouble speed = decimal(movement, "speed", 1, 5, "blocks/second", errors);
        boolean avoidWater = flag(movement, "avoidWater", true, errors), fly = flag(movement, "fly", false, errors);
        boolean openDoors = flag(movement, "openDoors", false, errors);
        OptionalDouble step = decimal(movement, "stepHeight", 0, MovementProfile.MAX_STEP, "blocks", errors);
        OptionalInt fall = OptionalInt.empty();
        if (movement.has("maximumFall")) {
            JsonElement number = movement.get("maximumFall");
            if (!whole(number, 0, 16)) errors.add("$.movement.maximumFall: expected whole 0..16 blocks");
            else fall = OptionalInt.of(number.getAsInt());
        }
        OptionalDouble range = decimal(movement, "pathRange", WaypointSettings.MINIMUM_LINK_DISTANCE, WaypointSettings.MAXIMUM_LINK_DISTANCE, "blocks", errors);
        OptionalDouble margin = decimal(movement, "arrivalMargin", 0, MAX_ARRIVAL_MARGIN, "blocks", errors);
        Optional<Stuck> stuck = Optional.empty();
        if (movement.has("stuck")) {
            JsonElement node = movement.get("stuck");
            if (!node.isJsonObject()) errors.add("$.movement.stuck: expected object");
            else {
                JsonObject object = node.getAsJsonObject();
                for (String key : object.keySet()) if (!Set.of("afterTicks", "teleport").contains(key)) errors.add("$.movement.stuck." + key + ": unknown field");
                int ticks = 100;
                if (object.has("afterTicks")) {
                    if (!whole(object.get("afterTicks"), 1, MAX_STUCK_TICKS)) { errors.add("$.movement.stuck.afterTicks: expected whole 1.." + MAX_STUCK_TICKS + " ticks"); ticks = -1; }
                    else ticks = object.get("afterTicks").getAsInt();
                }
                boolean teleport = true;
                if (object.has("teleport")) {
                    JsonElement flag = object.get("teleport");
                    if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) errors.add("$.movement.stuck.teleport: expected boolean");
                    else teleport = flag.getAsBoolean();
                }
                if (ticks > 0) stuck = Optional.of(new Stuck(ticks, teleport));
            }
        }
        for (String key : movement.keySet()) if (!FIELDS.contains(key)) errors.add("$.movement." + key + ": unknown field");
        NpcAttributes attributes = NpcAttributes.of(definition);
        JsonElement type = definition.get("type");
        boolean player = type != null && type.isJsonPrimitive() && type.getAsString().equals(BodyRules.PLAYER);
        if (speed.isEmpty() && attributes.get("movement_speed").isPresent()) speed = OptionalDouble.of(walkSpeed(attributes.value("movement_speed"), player));
        if (step.isEmpty() && attributes.get("step_height").isPresent()) step = OptionalDouble.of(Math.min(MovementProfile.MAX_STEP, attributes.value("step_height")));
        if (fall.isEmpty() && attributes.get("safe_fall_distance").isPresent())
            fall = OptionalInt.of((int) Math.max(0, Math.min(16, Math.floor(attributes.value("safe_fall_distance")))));
        double gravity = attributes.value("gravity");
        OptionalDouble jump = attributes.get("jump_strength").isPresent() || attributes.get("gravity").isPresent()
                ? OptionalDouble.of(jumpHeight(attributes.value("jump_strength"), gravity)) : OptionalDouble.empty();
        return new MovementSettings(speed, avoidWater, fly, openDoors, step, fall, range, margin, stuck, jump, gravity);
    }

    /** Blocks per second for a {@code movement_speed}: a player walks linearly with it, a mob with its square, as vanilla moves them. */
    public static double walkSpeed(double attribute, boolean player) {
        double blocks = player ? PLAYER_WALK * attribute : MOB_WALK * attribute * attribute;
        return Math.max(MINIMUM_SPEED, Math.min(MAXIMUM_SPEED, blocks));
    }

    /** The peak of a vanilla jump: rising by the jump speed, losing {@code gravity} and two percent drag each tick; at most {@link MovementProfile#MAX_JUMP}. */
    public static double jumpHeight(double strength, double gravity) {
        double height = 0, speed = strength;
        for (int tick = 0; tick < 200 && speed > 0 && height < MovementProfile.MAX_JUMP; tick++) {
            height += speed;
            speed = (speed - gravity) * IdleFall.DRAG;
        }
        return Math.min(MovementProfile.MAX_JUMP, height);
    }

    /** The route profile with this NPC's climb, drop, water, flying and door opening applied. Only walking bodies open doors. */
    public MovementProfile apply(MovementProfile profile, String type) {
        Set<MovementProfile.Mode> modes = profile.modes();
        if (fly) modes = Set.of(MovementProfile.Mode.FLY);
        else if (!avoidWater && modes.equals(Set.of(MovementProfile.Mode.WALK)) && !BodyMobility.sinks(type))
            modes = Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM);
        double step = stepHeight.orElse(profile.stepHeight());
        int fall = maximumFall.orElse(profile.maximumFall());
        double jump = jumpHeight.orElse(profile.jumpHeight());
        boolean doors = openDoors && modes.contains(MovementProfile.Mode.WALK) || profile.opensDoors();
        boolean climbs = profile.climbs() && modes.contains(MovementProfile.Mode.WALK);
        if (modes.equals(profile.modes()) && step == profile.stepHeight() && fall == profile.maximumFall() && doors == profile.opensDoors()
                && climbs == profile.climbs() && jump == profile.jumpHeight()) return profile;
        return new MovementProfile(modes, profile.width(), profile.height(), step, jump, fall, profile.diagonals(), climbs, doors);
    }

    /** Whether the NPC at {@code at} counts as having reached {@code goal}: within the arrival margin, when one is set. */
    public boolean arrived(art.arcane.animus.core.virtual.Vec3 at, art.arcane.animus.core.virtual.Vec3 goal) {
        return arrivalMargin.isPresent() && at.distance(goal) <= arrivalMargin.getAsDouble();
    }

    /** Whether a leg that has made no progress for {@code idleTicks} counts as stuck. */
    public boolean stuck(long idleTicks) { return stuck.isPresent() && idleTicks > stuck.get().afterTicks(); }

    /** Whether a stuck or unreachable leg ends by teleporting to its point instead of failing. */
    public boolean teleportsWhenStuck() { return stuck.isPresent() && stuck.get().teleport(); }

    /** The route speed: the route's own when it declares one, otherwise this NPC's, otherwise the route default. */
    public double routeSpeed(boolean routeDeclaresSpeed, double routeSpeed) { return routeDeclaresSpeed ? routeSpeed : speed.orElse(routeSpeed); }

    /** Whether a definition's own route declares a speed; reusable routes always use theirs. */
    public static boolean routeDeclaresSpeed(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject()) return true;
        JsonElement navigation = modules.getAsJsonObject().get("animus:navigation");
        return navigation == null || !navigation.isJsonObject() || navigation.getAsJsonObject().has("speed") || navigation.getAsJsonObject().has("route");
    }

    /** Whether a body of this type falls when nothing holds it: not with {@code gravity} false or a {@code gravity} attribute of 0 or less. */
    public static boolean falls(String type, JsonObject definition) {
        JsonElement gravity = definition.get("gravity");
        boolean off = gravity != null && gravity.isJsonPrimitive() && gravity.getAsJsonPrimitive().isBoolean() && !gravity.getAsBoolean();
        return !off && NpcAttributes.of(definition).value("gravity") > 0 && BodyMobility.falls(type);
    }

    private static boolean whole(JsonElement value, double minimum, double maximum) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsDouble() == Math.rint(value.getAsDouble())
                && value.getAsDouble() >= minimum && value.getAsDouble() <= maximum;
    }

    private static boolean flag(JsonObject object, String key, boolean fallback, List<String> errors) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { errors.add("$.movement." + key + ": expected boolean"); return fallback; }
        return value.getAsBoolean();
    }

    private static OptionalDouble decimal(JsonObject object, String key, double minimum, double maximum, String unit, List<String> errors) {
        JsonElement value = object.get(key);
        if (value == null) return OptionalDouble.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < minimum || value.getAsDouble() > maximum) {
            errors.add("$.movement." + key + ": expected " + format(minimum) + ".." + format(maximum) + " " + unit);
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value.getAsDouble());
    }

    private static String format(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
}
