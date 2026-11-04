package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native, explicitly enabled route data. Preserved importer traits are never read as executable waypoints. */
public record WaypointSettings(MovementProfile.Mode mode, double speed, boolean loop, int waitTicks, List<Vec3> points) {
    public WaypointSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(points);
        if (!Double.isFinite(speed) || speed < 1 || speed > 5 || waitTicks < 0 || waitTicks > 12000
                || points.isEmpty() || points.size() > 64) throw new IllegalArgumentException("Waypoint settings exceed native execution bounds");
        points = List.copyOf(points);
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
            if (data.has("route")) {
                JsonElement reference = data.get("route");
                if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()
                        || !reference.getAsString().matches("[a-z0-9][a-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("route must be a local reusable route identifier");
                for (String conflicting : List.of("points", "mode", "speed", "loop", "waitTicks"))
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
        return bool(data, "enabled", false) && data.has("route") ? data.get("route").getAsString() : null;
    }
    public static WaypointSettings document(String text) throws java.io.IOException {
        if (text == null) throw new java.io.IOException("Reusable route is missing");
        if (text.length() > 64 * 1024 || text.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) throw new java.io.IOException("Reusable route exceeds64 KiB");
        JsonObject data = JsonDocuments.object(text);
        try {
            JsonElement schema = data.get("schemaVersion");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                    || schema.getAsBigDecimal().intValueExact() != 1) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("Reusable route schemaVersion must be1"); }
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
            double speed = data.has("speed") ? number(data.get("speed"), "speed") : 2;
            if (speed < 1 || speed > 5) throw new IllegalArgumentException("speed must be1..5 blocks/second");
            if (data.has("waitTicks")) number(data.get("waitTicks"), "waitTicks");
            int wait = data.has("waitTicks") ? data.get("waitTicks").getAsBigDecimal().intValueExact() : 20;
            if (wait < 0 || wait > 12000) throw new IllegalArgumentException("waitTicks must be0..12000");
            JsonArray array = data.getAsJsonArray("points");
            if (array == null || array.isEmpty() || array.size() > 64) throw new IllegalArgumentException("points requires1..64 local-world coordinates");
            List<Vec3> points = new ArrayList<>();
            for (int index = 0; index < array.size(); index++) {
                JsonObject point = array.get(index).getAsJsonObject();
                String field = "points[" + index + "]";
                Vec3 p = new Vec3(number(point.get("x"), field + ".x"), number(point.get("y"), field + ".y"), number(point.get("z"), field + ".z"));
                if (Math.abs(p.x()) > 29_999_984 || Math.abs(p.z()) > 29_999_984 || Math.abs(p.y()) > 29_999_984)
                    throw new IllegalArgumentException("Point lies outside supported world bounds");
                if (mode == MovementProfile.Mode.WALK && p.y() != Math.rint(p.y()))
                    throw new IllegalArgumentException("Walking waypoints require integer feet height; partial-block support is unavailable");
                points.add(p);
            }
            return new WaypointSettings(mode, speed, bool(data, "loop", false), wait, points);
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
