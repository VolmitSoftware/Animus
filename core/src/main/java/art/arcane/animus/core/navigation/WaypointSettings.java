package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native, explicitly enabled route data. Preserved importer traits are never read as executable waypoints. */
public record WaypointSettings(MovementProfile.Mode mode, double speed, Order order, boolean loop, int waitTicks, List<Waypoint> points,
                               List<Vec3> guides, double linkDistance, Wander wander) {
    public static final int MAXIMUM_WAIT = 12_000, DEFAULT_WAIT = 20;
    public static final int MAXIMUM_POINTS = 256, MAXIMUM_WANDER_CENTRES = 64, MAXIMUM_GUIDES = 1024, MAXIMUM_NODES = 1280;
    public static final double DEFAULT_LINK_DISTANCE = 32, MINIMUM_LINK_DISTANCE = 1, MAXIMUM_LINK_DISTANCE = 256;
    /** How the route chooses its next point. */
    public enum Order {
        SEQUENCE, PINGPONG, RANDOM, WANDER;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /** One route point; a negative wait means the route's own waitTicks applies after arriving here. */
    public record Waypoint(Vec3 position, int waitTicks) {
        public Waypoint {
            Objects.requireNonNull(position);
            if (waitTicks < -1 || waitTicks > MAXIMUM_WAIT) throw new IllegalArgumentException("Point waitTicks exceed native execution bounds");
        }
        public Waypoint(Vec3 position) { this(position, -1); }
    }
    /** Random walking around a centre: block x and z within radius, feet height within height of the centre. */
    public record Wander(int radius, int height) {
        public static final int DEFAULT_RADIUS = 25, MAXIMUM_RADIUS = 32, DEFAULT_HEIGHT = 3, MAXIMUM_HEIGHT = 8;
        public static final Wander DEFAULT = new Wander(DEFAULT_RADIUS, DEFAULT_HEIGHT);
        public Wander {
            if (radius < 1 || radius > MAXIMUM_RADIUS || height < 0 || height > MAXIMUM_HEIGHT) throw new IllegalArgumentException("Wander settings exceed native execution bounds");
        }
    }
    /** Inline route settings; a reusable route or a recording refuses each of them. */
    public static final List<String> ROUTE_FIELDS = List.of("points", "mode", "speed", "order", "loop", "waitTicks", "guides", "linkDistance", "wander");

    public WaypointSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(order); Objects.requireNonNull(points); Objects.requireNonNull(guides);
        int maximum = order == Order.WANDER ? MAXIMUM_WANDER_CENTRES : MAXIMUM_POINTS;
        if (!Double.isFinite(speed) || speed < 1 || speed > 5 || waitTicks < 0 || waitTicks > MAXIMUM_WAIT
                || (points.isEmpty() && order != Order.WANDER) || points.size() > maximum || guides.size() > MAXIMUM_GUIDES
                || points.size() + guides.size() > MAXIMUM_NODES || (loop && order != Order.SEQUENCE)
                || (!guides.isEmpty() && order != Order.RANDOM) || (wander != null) != (order == Order.WANDER)
                || !Double.isFinite(linkDistance) || linkDistance < MINIMUM_LINK_DISTANCE || linkDistance > MAXIMUM_LINK_DISTANCE)
            throw new IllegalArgumentException("Waypoint settings exceed native execution bounds");
        points = List.copyOf(points); guides = List.copyOf(guides);
    }
    /** The wait after arriving at a point: its own, or the route's. */
    public int waitAfter(int point) {
        int own = points.get(point).waitTicks();
        return own >= 0 ? own : waitTicks;
    }
    /** Every order but a once sequence, and a pingpong over more than one point, keeps walking forever. */
    public boolean endless() {
        return switch (order) {
            case SEQUENCE -> loop;
            case PINGPONG -> points.size() > 1;
            case RANDOM, WANDER -> true;
        };
    }
    public static Optional<WaypointSettings> parse(NpcDefinition definition) { return parse(definition.json()); }
    /** Validate a raw definition without recursively invoking NpcDefinition.parse. */
    public static Optional<WaypointSettings> parse(JsonObject document) {
        String field = "$.modules.animus:navigation";
        try {
            if (!document.has("modules") || !document.getAsJsonObject("modules").has("animus:navigation")) return Optional.empty();
            JsonObject data = document.getAsJsonObject("modules").getAsJsonObject("animus:navigation");
            if (!bool(data, "enabled", false)) return Optional.empty();
            if (document.has("migration") && !bool(data, "reviewAccepted", false))
                throw new IllegalArgumentException("reviewAccepted must be true for imported definitions");
            if (data.has("recording")) return Optional.empty();
            if (data.has("route")) {
                JsonElement reference = data.get("route");
                if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()
                        || !reference.getAsString().matches("[a-z0-9][a-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("route must be a local reusable route identifier");
                for (String conflicting : ROUTE_FIELDS)
                    if (data.has(conflicting)) throw new IllegalArgumentException("Reusable route settings belong in its document, not " + conflicting);
                return Optional.empty();
            }
            return Optional.of(decode(data));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field + ": " + (invalid.getMessage() == null ? "invalid native waypoint document" : invalid.getMessage()), invalid);
        }
    }
    public static String reference(NpcDefinition definition) { return reference(definition.json()); }
    public static String reference(JsonObject document) {
        parse(document);
        if (!document.has("modules") || !document.getAsJsonObject("modules").has("animus:navigation")) return null;
        JsonObject data = document.getAsJsonObject("modules").getAsJsonObject("animus:navigation");
        return bool(data, "enabled", false) && data.has("route") && !data.has("recording") ? data.get("route").getAsString() : null;
    }
    public static WaypointSettings document(String text) throws java.io.IOException {
        if (text == null) throw new java.io.IOException("Reusable route is missing");
        if (text.length() > 64 * 1024 || text.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) throw new java.io.IOException("Reusable route exceeds 64 KiB");
        JsonObject data = JsonDocuments.object(text);
        try {
            JsonElement schema = data.get("schemaVersion");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                    || schema.getAsBigDecimal().intValueExact() != 1) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("Reusable route schemaVersion must be 1"); }
        if (data.has("route")) throw new IllegalArgumentException("Recursive route references are unavailable");
        return decode(data);
    }
    private static WaypointSettings decode(JsonObject data) {
        MovementProfile.Mode mode = MovementProfile.Mode.WALK;
        if (data.has("mode")) {
            JsonElement value = data.get("mode");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("mode must be a string: walk, swim or fly");
            try { mode = MovementProfile.Mode.valueOf(value.getAsString().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("mode must be walk, swim or fly"); }
        }
        Order order = Order.SEQUENCE;
        if (data.has("order")) {
            JsonElement value = data.get("order");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[a-z]+"))
                throw new IllegalArgumentException("order must be sequence, pingpong, random or wander");
            try { order = Order.valueOf(value.getAsString().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("order must be sequence, pingpong, random or wander"); }
        }
        double speed = data.has("speed") ? number(data.get("speed"), "speed") : 2;
        if (speed < 1 || speed > 5) throw new IllegalArgumentException("speed must be 1..5 blocks/second");
        int wait = data.has("waitTicks") ? wait(data.get("waitTicks"), "waitTicks") : DEFAULT_WAIT;
        if (data.has("loop") && order != Order.SEQUENCE) throw new IllegalArgumentException("loop is only allowed with order sequence");
        boolean loop = bool(data, "loop", false);
        int minimum = order == Order.WANDER ? 0 : 1, maximum = order == Order.WANDER ? MAXIMUM_WANDER_CENTRES : MAXIMUM_POINTS;
        JsonElement pointArray = data.get("points");
        if (pointArray == null && order == Order.WANDER) pointArray = new JsonArray();
        if (pointArray == null || !pointArray.isJsonArray() || pointArray.getAsJsonArray().size() < minimum || pointArray.getAsJsonArray().size() > maximum)
            throw new IllegalArgumentException("points requires " + minimum + ".." + maximum + " local-world coordinates for order " + order.key());
        List<Waypoint> points = new ArrayList<>();
        JsonArray array = pointArray.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            String field = "points[" + index + "]";
            JsonObject point = object(array.get(index), field);
            for (String key : point.keySet())
                if (!Set.of("x", "y", "z", "waitTicks").contains(key)) throw new IllegalArgumentException(field + "." + key + ": unknown field");
            Vec3 position = coordinate(point, field, mode);
            points.add(new Waypoint(position, point.has("waitTicks") ? wait(point.get("waitTicks"), field + ".waitTicks") : -1));
        }
        List<Vec3> guides = new ArrayList<>();
        if (data.has("guides")) {
            if (order != Order.RANDOM) throw new IllegalArgumentException("guides is only allowed with order random");
            JsonElement guideArray = data.get("guides");
            if (!guideArray.isJsonArray() || guideArray.getAsJsonArray().size() > MAXIMUM_GUIDES)
                throw new IllegalArgumentException("guides requires 0.." + MAXIMUM_GUIDES + " local-world coordinates");
            JsonArray entries = guideArray.getAsJsonArray();
            for (int index = 0; index < entries.size(); index++) {
                String field = "guides[" + index + "]";
                JsonObject guide = object(entries.get(index), field);
                for (String key : guide.keySet())
                    if (!Set.of("x", "y", "z").contains(key)) throw new IllegalArgumentException(field + "." + key + ": unknown field");
                guides.add(coordinate(guide, field, mode));
            }
        }
        if (points.size() + guides.size() > MAXIMUM_NODES) throw new IllegalArgumentException("points and guides together allow at most " + MAXIMUM_NODES + " coordinates");
        double link = DEFAULT_LINK_DISTANCE;
        if (data.has("linkDistance")) {
            if (order != Order.RANDOM) throw new IllegalArgumentException("linkDistance is only allowed with order random");
            link = number(data.get("linkDistance"), "linkDistance");
            if (link < MINIMUM_LINK_DISTANCE || link > MAXIMUM_LINK_DISTANCE) throw new IllegalArgumentException("linkDistance must be 1..256 blocks");
        }
        Wander wander = null;
        if (data.has("wander") && order != Order.WANDER) throw new IllegalArgumentException("wander is only allowed with order wander");
        if (order == Order.WANDER) {
            JsonObject settings = data.has("wander") ? object(data.get("wander"), "wander") : new JsonObject();
            for (String key : settings.keySet())
                if (!Set.of("radius", "height").contains(key)) throw new IllegalArgumentException("wander." + key + ": unknown field");
            int radius = settings.has("radius") ? whole(settings.get("radius"), "wander.radius") : Wander.DEFAULT_RADIUS;
            if (radius < 1 || radius > Wander.MAXIMUM_RADIUS) throw new IllegalArgumentException("wander.radius must be 1..32 blocks");
            int height = settings.has("height") ? whole(settings.get("height"), "wander.height") : Wander.DEFAULT_HEIGHT;
            if (height < 0 || height > Wander.MAXIMUM_HEIGHT) throw new IllegalArgumentException("wander.height must be 0..8 blocks");
            wander = new Wander(radius, height);
        }
        return new WaypointSettings(mode, speed, order, loop, wait, points, guides, link, wander);
    }
    private static Vec3 coordinate(JsonObject point, String field, MovementProfile.Mode mode) {
        Vec3 p = new Vec3(number(point.get("x"), field + ".x"), number(point.get("y"), field + ".y"), number(point.get("z"), field + ".z"));
        if (Math.abs(p.x()) > 29_999_984 || Math.abs(p.z()) > 29_999_984 || Math.abs(p.y()) > 29_999_984)
            throw new IllegalArgumentException(field + ": point lies outside supported world bounds");
        if (mode == MovementProfile.Mode.WALK && p.y() != Math.rint(p.y()))
            throw new IllegalArgumentException(field + ".y: walking waypoints require integer feet height; partial-block support is unavailable");
        return p;
    }
    private static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(field + ": expected object");
        return value.getAsJsonObject();
    }
    private static int wait(JsonElement value, String field) {
        int wait = whole(value, field);
        if (wait < 0 || wait > MAXIMUM_WAIT) throw new IllegalArgumentException(field + " must be 0..12000");
        return wait;
    }
    private static int whole(JsonElement value, String field) {
        number(value, field);
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(field + ": expected whole number"); }
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }
    private static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Coordinate/speed must be numeric");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Coordinate/speed must be finite");
        return number;
    }
    private static double number(JsonElement value, String field) {
        try { return number(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + ": " + invalid.getMessage(), invalid); }
    }
}
