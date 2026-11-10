package art.arcane.animus.core.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where an NPC stands, walks or goes, as the editor's terrain reads see it: its position, its anchors, its schedule places
 * and the legs between them in time order, and its {@code animus:navigation} points (inline or a reusable route) with the
 * legs it walks between them. A point that teleports carries the route on from the teleport target, in its world.
 */
final class TerrainReaches {
    /** A stretch of one world: a point when both ends match, else the straight leg between them. */
    record Reach(String group, String world, double ax, double az, double bx, double bz) {
        static Reach point(String group, String world, double x, double z) { return new Reach(group, world, x, z, x, z); }

        /** The squared distance from this stretch to the block rectangle. */
        double distanceSquared(double minX, double minZ, double maxX, double maxZ) {
            if (crosses(minX, minZ, maxX, maxZ)) return 0;
            double best = Math.min(toRectangle(ax, az, minX, minZ, maxX, maxZ), toRectangle(bx, bz, minX, minZ, maxX, maxZ));
            if (ax == bx && az == bz) return best;
            best = Math.min(best, toLeg(minX, minZ));
            best = Math.min(best, toLeg(maxX, minZ));
            best = Math.min(best, toLeg(minX, maxZ));
            return Math.min(best, toLeg(maxX, maxZ));
        }

        private boolean crosses(double minX, double minZ, double maxX, double maxZ) {
            double t0 = 0, t1 = 1, dx = bx - ax, dz = bz - az;
            double[] p = {-dx, dx, -dz, dz}, q = {ax - minX, maxX - ax, az - minZ, maxZ - az};
            for (int i = 0; i < 4; i++) {
                if (p[i] == 0) { if (q[i] < 0) return false; continue; }
                double t = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, t); else t1 = Math.min(t1, t);
                if (t0 > t1) return false;
            }
            return true;
        }

        private double toLeg(double x, double z) {
            double dx = bx - ax, dz = bz - az, t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)));
            double ex = ax + t * dx - x, ez = az + t * dz - z;
            return ex * ex + ez * ez;
        }

        private static double toRectangle(double x, double z, double minX, double minZ, double maxX, double maxZ) {
            double dx = Math.max(Math.max(minX - x, 0), x - maxX), dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
            return dx * dx + dz * dz;
        }
    }

    private TerrainReaches() {}

    /**
     * The reaches of one definition. {@code route} returns a reusable route by id, or empty when it cannot be read.
     * Malformed parts are skipped; the definition was validated when it loaded.
     */
    static List<Reach> of(String group, JsonObject definition, Function<String, Optional<JsonObject>> route) {
        List<Reach> out = new ArrayList<>();
        JsonObject position = object(definition, "position");
        String home = text(position, "world");
        Double homeX = number(position, "x"), homeZ = number(position, "z");
        if (home == null || homeX == null || homeZ == null) return out;
        out.add(Reach.point(group, home, homeX, homeZ));
        JsonObject anchors = object(definition, "anchors");
        if (anchors != null) for (Map.Entry<String, JsonElement> entry : anchors.entrySet()) {
            JsonObject anchor = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
            Double x = number(anchor, "x"), z = number(anchor, "z");
            String world = text(anchor, "world");
            if (x != null && z != null) out.add(Reach.point(group, world == null ? home : world, x, z));
        }
        JsonObject modules = object(definition, "modules");
        schedule(group, home, object(modules, "animus:schedule"), out);
        JsonObject navigation = object(modules, "animus:navigation");
        if (navigation != null) {
            String id = text(navigation, "route");
            JsonObject source = id == null ? navigation : route.apply(id).orElse(null);
            if (source != null) route(group, home, homeX, homeZ, source, out);
        }
        return out;
    }

    /** Each place, and the leg from each to the next in time order, the last back to the first. */
    private static void schedule(String group, String home, JsonObject schedule, List<Reach> out) {
        JsonArray locations = array(schedule, "locations");
        if (locations == null) return;
        List<double[]> places = new ArrayList<>();
        for (JsonElement element : locations) {
            JsonObject location = element.isJsonObject() ? element.getAsJsonObject() : null;
            Double time = number(location, "time"), x = number(location, "x"), z = number(location, "z");
            if (time != null && x != null && z != null) places.add(new double[]{time, x, z});
        }
        places.sort(Comparator.comparingDouble((double[] place) -> place[0]));
        for (int i = 0; i < places.size(); i++) {
            double[] from = places.get(i), to = places.get((i + 1) % places.size());
            out.add(new Reach(group, home, from[1], from[2], to[1], to[2]));
        }
    }

    /** One numbered point of a route: where the NPC arrives and where it leaves from, each in its world. */
    private record Stop(String world, double x, double z, String leaveWorld, double leaveX, double leaveZ) {}

    private static void route(String group, String home, double homeX, double homeZ, JsonObject route, List<Reach> out) {
        String order = text(route, "order");
        boolean walked = order == null || order.equals("sequence") || order.equals("pingpong");
        boolean sequence = order == null || order.equals("sequence");
        JsonArray points = array(route, "points");
        List<Stop> stops = new ArrayList<>();
        Map<Integer, JsonArray> branches = new HashMap<>();
        String world = home;
        Stop previous = new Stop(home, homeX, homeZ, home, homeX, homeZ);
        if (points != null) for (int index = 0; index < points.size(); index++) {
            JsonObject point = points.get(index).isJsonObject() ? points.get(index).getAsJsonObject() : null;
            Double x = number(point, "x"), z = number(point, "z");
            if (x == null || z == null) {
                // A recording step: where it ends is not known here, so no leg runs on from it.
                stops.add(null);
                previous = null;
                continue;
            }
            Stop stop = new Stop(world, x, z, world, x, z);
            JsonObject teleport = object(point, "teleport");
            Double tx = number(teleport, "x"), tz = number(teleport, "z");
            if (tx != null && tz != null) {
                String target = text(teleport, "world");
                world = target == null ? world : target;
                stop = new Stop(stop.world(), x, z, world, tx, tz);
                out.add(Reach.point(group, world, tx, tz));
            }
            out.add(Reach.point(group, stop.world(), x, z));
            if (walked && previous != null && !branches.containsKey(index - 1)) leg(group, previous, stop, out);
            JsonArray next = array(point, "next");
            if (sequence && next != null) branches.put(index, next);
            stops.add(stop);
            previous = stop;
        }
        JsonArray guides = array(route, "guides");
        if (guides != null) for (JsonElement element : guides) {
            JsonObject guide = element.isJsonObject() ? element.getAsJsonObject() : null;
            Double x = number(guide, "x"), z = number(guide, "z");
            if (x != null && z != null) out.add(Reach.point(group, home, x, z));
        }
        boolean loop = route.has("loop") && route.get("loop").isJsonPrimitive() && route.get("loop").getAsBoolean();
        if (sequence && loop && stops.size() > 2 && previous != null && stops.getFirst() != null && !branches.containsKey(stops.size() - 1))
            leg(group, previous, stops.getFirst(), out);
        for (Map.Entry<Integer, JsonArray> branch : branches.entrySet()) {
            for (JsonElement junction : branch.getValue()) {
                Double to = junction.isJsonObject() ? number(junction.getAsJsonObject(), "to") : null;
                if (to == null || to < 0 || to >= stops.size() || stops.get(to.intValue()) == null) continue;
                leg(group, stops.get(branch.getKey()), stops.get(to.intValue()), out);
            }
        }
    }

    /** The leg from where {@code from} is left to where {@code to} is reached, when both are in one world. */
    private static void leg(String group, Stop from, Stop to, List<Reach> out) {
        if (from.leaveWorld().equals(to.world())) out.add(new Reach(group, to.world(), from.leaveX(), from.leaveZ(), to.x(), to.z()));
    }

    private static JsonObject object(JsonObject parent, String field) {
        if (parent == null) return null;
        JsonElement value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String field) {
        if (parent == null) return null;
        JsonElement value = parent.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String text(JsonObject parent, String field) {
        if (parent == null) return null;
        JsonElement value = parent.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static Double number(JsonObject parent, String field) {
        if (parent == null) return null;
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : null;
    }
}
