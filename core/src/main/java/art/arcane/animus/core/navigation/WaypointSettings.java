package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Native, explicitly enabled route data. Preserved importer traits are never read as executable waypoints. A null
 * {@code style} walks each leg in the NPC's own {@link MovementStyle}; a point's own style applies to the leg that ends there.
 */
public record WaypointSettings(MovementProfile.Mode mode, double speed, Order order, boolean loop, Wait waitTicks, List<Waypoint> points,
                               List<Vec3> guides, double linkDistance, Wander wander, Seed seed, StartOffset startOffset, int jitter, MovementStyle style) {
    public static final int MAXIMUM_WAIT = 12_000, DEFAULT_WAIT = 20;
    public static final int MAXIMUM_JITTER = 8, MAXIMUM_WEIGHT = 1000, MAXIMUM_JUNCTIONS = 8;
    /** The highest fromTick or toTick a step may name; the recording itself is the real bound. */
    public static final int MAXIMUM_STEP_TICK = 72_000;
    public static final int MAXIMUM_POINTS = 256, MAXIMUM_WANDER_CENTRES = 64, MAXIMUM_GUIDES = 1024, MAXIMUM_NODES = 1280;
    public static final double DEFAULT_LINK_DISTANCE = 32, MINIMUM_LINK_DISTANCE = 1, MAXIMUM_LINK_DISTANCE = 256;
    /** How the route chooses its next point. */
    public enum Order {
        SEQUENCE, PINGPONG, RANDOM, WANDER;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /** Where the route's generator gets its seed: the NPC and route identity, or the clock. */
    public enum Seed {
        NPC, RANDOM;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /** Where the route starts: its first point, or a random point after a random part of the route wait. */
    public enum StartOffset {
        FIRST, RANDOM;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /** A wait in ticks, drawn from min..max inclusive on each use. */
    public record Wait(int min, int max) {
        public Wait {
            if (min < 0 || max > MAXIMUM_WAIT || min > max) throw new IllegalArgumentException("Wait exceeds native execution bounds");
        }
        public static Wait fixed(int ticks) { return new Wait(ticks, ticks); }
    }
    /** One way out of a junction point. */
    public record Junction(int to, int weight) {
        public Junction {
            if (to < 0 || weight < 1 || weight > MAXIMUM_WEIGHT) throw new IllegalArgumentException("Junction exceeds native execution bounds");
        }
    }
    /**
     * A route point that plays the ticks {@code fromTick..toTick} of {@code recordings/<recording>.json} once. A negative
     * toTick means the recording's durationTicks; the range is checked against the recording when it is read.
     */
    public record Step(String recording, int fromTick, int toTick) {
        public static final String ID = "[a-z0-9][a-z0-9_-]{0,63}";
        public Step {
            if (recording == null || !recording.matches(ID) || fromTick < 0 || fromTick > MAXIMUM_STEP_TICK || toTick > MAXIMUM_STEP_TICK
                    || (toTick >= 0 && fromTick >= toTick))
                throw new IllegalArgumentException("Recording step exceeds native execution bounds");
        }
        /** The tick the segment ends on in a recording of {@code durationTicks}. */
        public int end(int durationTicks) { return toTick < 0 ? durationTicks : toTick; }
        /** Why the segment does not fit a recording of {@code durationTicks}, naming point {@code index}; empty when it fits. */
        public Optional<String> problem(int index, int durationTicks) {
            String field = "$.points[" + index + "]";
            if (fromTick >= durationTicks)
                return Optional.of(field + ".fromTick: " + fromTick + " is not below the durationTicks " + durationTicks + " of recording " + recording);
            if (toTick > durationTicks)
                return Optional.of(field + ".toTick: " + toTick + " is past the durationTicks " + durationTicks + " of recording " + recording);
            return Optional.empty();
        }
    }
    /**
     * One route point: a position, or a recording step with no position of its own. A null wait or a negative jitter
     * means the route's own value applies here; a step has no jitter. Weight steers order random; next, when not empty,
     * replaces index + 1 in order sequence. A null style walks the leg to this point in the route's style; a step has none.
     */
    public record Waypoint(Vec3 position, Step step, Wait waitTicks, int jitter, int weight, List<Junction> next, MovementStyle style) {
        public Waypoint {
            Objects.requireNonNull(next);
            if ((position == null) == (step == null) || (step != null && (jitter != -1 || style != null))
                    || jitter < -1 || jitter > MAXIMUM_JITTER || weight < 1 || weight > MAXIMUM_WEIGHT || next.size() > MAXIMUM_JUNCTIONS)
                throw new IllegalArgumentException("Point settings exceed native execution bounds");
            next = List.copyOf(next);
        }
        public Waypoint(Vec3 position) { this(position, null, null, -1, 1, List.of(), null); }
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
    public static final List<String> ROUTE_FIELDS = List.of("points", "mode", "speed", "order", "loop", "waitTicks", "guides", "linkDistance", "wander",
            "seed", "startOffset", "jitter", "style");
    private static final Set<String> POINT_FIELDS = Set.of("x", "y", "z", "waitTicks", "jitter", "weight", "next", "style");
    private static final Set<String> STEP_FIELDS = Set.of("recording", "fromTick", "toTick", "playback", "waitTicks", "weight", "next");

    public WaypointSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(order); Objects.requireNonNull(points); Objects.requireNonNull(guides);
        Objects.requireNonNull(waitTicks); Objects.requireNonNull(seed); Objects.requireNonNull(startOffset);
        int maximum = order == Order.WANDER ? MAXIMUM_WANDER_CENTRES : MAXIMUM_POINTS;
        for (Waypoint point : points) {
            if ((point.weight() != 1 && order != Order.RANDOM) || (!point.next().isEmpty() && order != Order.SEQUENCE)
                    || (point.jitter() > 0 && order == Order.WANDER) || (point.step() != null && order == Order.WANDER))
                throw new IllegalArgumentException("Point settings do not apply to order " + order.key());
            for (Junction junction : point.next())
                if (junction.to() >= points.size()) throw new IllegalArgumentException("Junction leads past the last point");
        }
        if (!Double.isFinite(speed) || speed < 1 || speed > 5 || jitter < 0 || jitter > MAXIMUM_JITTER || (jitter > 0 && order == Order.WANDER)
                || (points.isEmpty() && order != Order.WANDER) || points.size() > maximum || guides.size() > MAXIMUM_GUIDES
                || points.size() + guides.size() > MAXIMUM_NODES || (loop && order != Order.SEQUENCE)
                || (!guides.isEmpty() && order != Order.RANDOM) || (wander != null) != (order == Order.WANDER)
                || !Double.isFinite(linkDistance) || linkDistance < MINIMUM_LINK_DISTANCE || linkDistance > MAXIMUM_LINK_DISTANCE)
            throw new IllegalArgumentException("Waypoint settings exceed native execution bounds");
        points = List.copyOf(points); guides = List.copyOf(guides);
    }
    /** The wait after arriving at a point: its own, or the route's. */
    public Wait waitAfter(int point) {
        Wait own = points.get(point).waitTicks();
        return own != null ? own : waitTicks;
    }
    /** The jitter radius of a point's leg goal: its own, or the route's. A recording step has none. */
    public int jitterAt(int point) {
        Waypoint at = points.get(point);
        if (at.step() != null) return 0;
        return at.jitter() >= 0 ? at.jitter() : jitter;
    }
    /** The style of the leg that ends at {@code arrival}: the point's own, else the route's; null leaves the NPC's own. Guides use the route's. */
    public MovementStyle styleTo(int arrival) {
        MovementStyle own = arrival >= 0 && arrival < points.size() ? points.get(arrival).style() : null;
        return own != null ? own : style;
    }
    /** The distinct recordings the route's steps play, in point order. */
    public Set<String> recordings() {
        Set<String> recordings = new LinkedHashSet<>();
        for (Waypoint point : points) if (point.step() != null) recordings.add(point.step().recording());
        return recordings;
    }
    /**
     * Every order but a once sequence, and a pingpong over more than one point, keeps walking forever. A once sequence
     * with junctions is endless when a point it can reach has no way to the end.
     */
    public boolean endless() {
        return switch (order) {
            case SEQUENCE -> loop || trapped();
            case PINGPONG -> points.size() > 1;
            case RANDOM, WANDER -> true;
        };
    }
    private boolean trapped() {
        int size = points.size();
        List<List<Integer>> into = new ArrayList<>(size);
        for (int i = 0; i < size; i++) into.add(new ArrayList<>());
        boolean[] finishing = new boolean[size];
        ArrayDeque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            List<Junction> next = points.get(i).next();
            if (next.isEmpty() && i + 1 == size) { finishing[i] = true; open.add(i); }
            else if (next.isEmpty()) into.get(i + 1).add(i);
            else for (Junction junction : next) into.get(junction.to()).add(i);
        }
        while (!open.isEmpty()) for (int from : into.get(open.removeFirst())) if (!finishing[from]) { finishing[from] = true; open.add(from); }
        boolean[] reached = new boolean[size];
        for (int i = 0; i < size; i++) if (startOffset == StartOffset.RANDOM || i == 0) { reached[i] = true; open.add(i); }
        while (!open.isEmpty()) {
            int at = open.removeFirst();
            if (!finishing[at]) return true;
            List<Junction> next = points.get(at).next();
            if (next.isEmpty()) { if (at + 1 < size && !reached[at + 1]) { reached[at + 1] = true; open.add(at + 1); } }
            else for (Junction junction : next) if (!reached[junction.to()]) { reached[junction.to()] = true; open.add(junction.to()); }
        }
        return false;
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
        try { return decode(data); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("$." + invalid.getMessage(), invalid); }
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
        Wait wait = data.has("waitTicks") ? wait(data.get("waitTicks"), "waitTicks") : Wait.fixed(DEFAULT_WAIT);
        Seed seed = data.has("seed") ? choice(data.get("seed"), "seed", Seed.class, "npc or random") : Seed.NPC;
        StartOffset start = data.has("startOffset") ? choice(data.get("startOffset"), "startOffset", StartOffset.class, "first or random") : StartOffset.FIRST;
        if (data.has("jitter") && order == Order.WANDER) throw new IllegalArgumentException("jitter is not allowed with order wander; wander.radius spreads its goals");
        int jitter = data.has("jitter") ? jitter(data.get("jitter"), "jitter") : 0;
        if (data.has("loop") && order != Order.SEQUENCE) throw new IllegalArgumentException("loop is only allowed with order sequence");
        boolean loop = bool(data, "loop", false);
        MovementStyle style = data.has("style") ? style(data.get("style"), "style") : null;
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
            boolean recording = point.has("recording");
            if (recording && order == Order.WANDER) throw new IllegalArgumentException(field + ".recording: a recording step is not allowed with order wander");
            for (String key : point.keySet()) {
                if (recording && (key.equals("x") || key.equals("y") || key.equals("z")))
                    throw new IllegalArgumentException(field + "." + key + ": a recording step has no position; it starts at the segment's first frame");
                if (recording && key.equals("jitter")) throw new IllegalArgumentException(field + ".jitter: a recording step has no jitter");
                if (recording && key.equals("style")) throw new IllegalArgumentException(field + ".style: a recording step plays its recorded movement; it has no style");
                if (!recording && (key.equals("fromTick") || key.equals("toTick") || key.equals("playback")))
                    throw new IllegalArgumentException(field + "." + key + ": requires recording");
                if (!(recording ? STEP_FIELDS : POINT_FIELDS).contains(key)) throw new IllegalArgumentException(field + "." + key + ": unknown field");
            }
            Vec3 position = recording ? null : coordinate(point, field, mode);
            Step step = recording ? step(point, field) : null;
            Wait own = point.has("waitTicks") ? wait(point.get("waitTicks"), field + ".waitTicks") : null;
            if (point.has("jitter") && order == Order.WANDER) throw new IllegalArgumentException(field + ".jitter is not allowed with order wander");
            int ownJitter = point.has("jitter") ? jitter(point.get("jitter"), field + ".jitter") : -1;
            if (point.has("weight") && order != Order.RANDOM) throw new IllegalArgumentException(field + ".weight is only allowed with order random");
            int weight = point.has("weight") ? weight(point.get("weight"), field + ".weight") : 1;
            if (point.has("next") && order != Order.SEQUENCE) throw new IllegalArgumentException(field + ".next is only allowed with order sequence");
            List<Junction> next = point.has("next") ? junctions(point.get("next"), field + ".next", array.size()) : List.of();
            MovementStyle ownStyle = !recording && point.has("style") ? style(point.get("style"), field + ".style") : null;
            points.add(new Waypoint(position, step, own, ownJitter, weight, next, ownStyle));
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
        return new WaypointSettings(mode, speed, order, loop, wait, points, guides, link, wander, seed, start, jitter, style);
    }
    private static MovementStyle style(JsonElement value, String field) {
        return MovementSettings.style(value).orElseThrow(() -> new IllegalArgumentException(field + " must be " + String.join(", ", MovementStyle.keys())));
    }
    private static List<Junction> junctions(JsonElement value, String field, int points) {
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAXIMUM_JUNCTIONS)
            throw new IllegalArgumentException(field + " requires 1.." + MAXIMUM_JUNCTIONS + " {\"to\": index, \"weight\": 1..1000} entries");
        List<Junction> junctions = new ArrayList<>();
        JsonArray entries = value.getAsJsonArray();
        for (int index = 0; index < entries.size(); index++) {
            String entry = field + "[" + index + "]";
            JsonObject junction = object(entries.get(index), entry);
            for (String key : junction.keySet())
                if (!Set.of("to", "weight").contains(key)) throw new IllegalArgumentException(entry + "." + key + ": unknown field");
            if (!junction.has("to")) throw new IllegalArgumentException(entry + ".to: required");
            int to = whole(junction.get("to"), entry + ".to");
            if (to < 0 || to >= points) throw new IllegalArgumentException(entry + ".to: out of range");
            junctions.add(new Junction(to, junction.has("weight") ? weight(junction.get("weight"), entry + ".weight") : 1));
        }
        return junctions;
    }
    private static Step step(JsonObject point, String field) {
        JsonElement reference = point.get("recording");
        if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString() || !reference.getAsString().matches(Step.ID))
            throw new IllegalArgumentException(field + ".recording: expected 1-64 lowercase letters, digits, _ or -");
        int from = point.has("fromTick") ? stepTick(point.get("fromTick"), field + ".fromTick") : 0;
        int to = point.has("toTick") ? stepTick(point.get("toTick"), field + ".toTick") : -1;
        if (to >= 0 && from >= to) throw new IllegalArgumentException(field + ".fromTick: must be below toTick");
        if (point.has("playback")) {
            JsonElement playback = point.get("playback");
            if (!playback.isJsonPrimitive() || !playback.getAsJsonPrimitive().isString() || !playback.getAsString().equals("once"))
                throw new IllegalArgumentException(field + ".playback: a recording step plays once; loop and pingpong never end, and a route must go on");
        }
        return new Step(reference.getAsString(), from, to);
    }
    private static int stepTick(JsonElement value, String field) {
        int tick = whole(value, field);
        if (tick < 0 || tick > MAXIMUM_STEP_TICK) throw new IllegalArgumentException(field + " must be 0.." + MAXIMUM_STEP_TICK);
        return tick;
    }
    private static <E extends Enum<E>> E choice(JsonElement value, String field, Class<E> type, String allowed) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[a-z]+"))
            throw new IllegalArgumentException(field + " must be " + allowed);
        try { return Enum.valueOf(type, value.getAsString().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + " must be " + allowed); }
    }
    private static int jitter(JsonElement value, String field) {
        int jitter = whole(value, field);
        if (jitter < 0 || jitter > MAXIMUM_JITTER) throw new IllegalArgumentException(field + " must be 0.." + MAXIMUM_JITTER + " blocks");
        return jitter;
    }
    private static int weight(JsonElement value, String field) {
        int weight = whole(value, field);
        if (weight < 1 || weight > MAXIMUM_WEIGHT) throw new IllegalArgumentException(field + " must be 1.." + MAXIMUM_WEIGHT);
        return weight;
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
    /** A whole number of ticks, or [min, max]. */
    private static Wait wait(JsonElement value, String field) {
        if (value != null && value.isJsonArray()) {
            JsonArray range = value.getAsJsonArray();
            if (range.size() != 2) throw new IllegalArgumentException(field + " must be 0..12000 or [min, max]");
            int min = ticks(range.get(0), field), max = ticks(range.get(1), field);
            if (min > max) throw new IllegalArgumentException(field + ": min above max");
            return new Wait(min, max);
        }
        return Wait.fixed(ticks(value, field));
    }
    private static int ticks(JsonElement value, String field) {
        int wait = whole(value, field);
        if (wait < 0 || wait > MAXIMUM_WAIT) throw new IllegalArgumentException(field + " must be 0..12000 or [min, max]");
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
