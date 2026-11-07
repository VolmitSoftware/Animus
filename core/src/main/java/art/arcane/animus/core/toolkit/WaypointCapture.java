package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Route points a player drops where they stand, in one world. Points snap to the block centre, which is where a
 * whole-number route point leads anyway. A new capture saves as a looping walk through the points; a capture loaded
 * from {@code routes/<id>.json} keeps that file's route settings and other point fields and saves back to the same id.
 * One point is current: inserts go after it and the outline marks it.
 */
public final class WaypointCapture {
    private final Set<String> worlds;
    private final List<JsonObject> points = new ArrayList<>();
    private final JsonObject settings;
    private final Optional<String> source, hash;
    private int current = -1;

    public WaypointCapture(Set<String> worlds) {
        this(worlds, defaults(), Optional.empty(), Optional.empty());
    }

    private WaypointCapture(Set<String> worlds, JsonObject settings, Optional<String> source, Optional<String> hash) {
        this.worlds = Set.copyOf(worlds); this.settings = settings; this.source = source; this.hash = hash;
    }

    /**
     * A saved route opened for editing in the world the player stands in. Routes with recording steps stay in the
     * editor, since a step has no place to stand on.
     *
     * @param hash the file's hash when read, so saving refuses a file changed meanwhile
     */
    public static WaypointCapture load(String id, String hash, String text, Set<String> worlds) throws IOException {
        WaypointSettings.document(text);
        JsonObject document = JsonDocuments.object(text);
        WaypointCapture capture = new WaypointCapture(worlds, document, Optional.of(id), Optional.of(hash));
        JsonArray listed = document.has("points") ? document.getAsJsonArray("points") : new JsonArray();
        for (JsonElement point : listed) {
            if (point.getAsJsonObject().has("recording")) throw new IOException("routes/" + id + ".json has a recording step; edit it in the web editor");
            capture.points.add(point.getAsJsonObject().deepCopy());
        }
        document.remove("points");
        capture.current = capture.points.size() - 1;
        return capture;
    }

    private static JsonObject defaults() {
        JsonObject route = new JsonObject();
        route.addProperty("schemaVersion", 1);
        route.addProperty("loop", true);
        return route;
    }

    /** The route id this capture saves back to; empty for a new capture. */
    public Optional<String> source() { return source; }

    /** The hash of the file this capture was loaded from. */
    public Optional<String> hash() { return hash; }

    /** The player stands in the world the capture started in. */
    public boolean in(ScreenHost.Pose pose) { return !Collections.disjoint(worlds, pose.worlds()); }

    /** The worlds the capture's points are in, for a teleport back to one. */
    public Set<String> worlds() { return worlds; }

    /** Adds the point under the player at the end and makes it current; returns the point count. */
    public synchronized int add(ScreenHost.Pose pose) { return insert(points.size() - 1, pose) + 1; }

    /** Adds the point under the player after {@code after} ({@code -1} for first) and makes it current; returns its index. */
    public synchronized int insert(int after, ScreenHost.Pose pose) {
        if (after < -1 || after >= points.size()) throw new IndexOutOfBoundsException("No point " + (after + 1));
        if (points.size() >= WaypointSettings.MAXIMUM_POINTS) throw new IllegalStateException("A route holds at most " + WaypointSettings.MAXIMUM_POINTS + " points");
        JsonObject point = new JsonObject();
        place(point, pose);
        int index = after + 1;
        renumber(index, 1);
        points.add(index, point);
        current = index;
        return index;
    }

    /** Moves a point to where the player stands, keeping its wait and other fields. */
    public synchronized void move(int index, ScreenHost.Pose pose) {
        Objects.checkIndex(index, points.size());
        place(points.get(index), pose);
    }

    /** Deletes a point; junctions to it go and those past it follow. The current point stays where it was. */
    public synchronized void delete(int index) {
        Objects.checkIndex(index, points.size());
        points.remove(index);
        renumber(index, -1);
        if (current > index || current == points.size()) current--;
    }

    public synchronized boolean removeLast() {
        if (points.isEmpty()) return false;
        delete(points.size() - 1);
        return true;
    }

    /** Sets how long the NPC waits at a point, in ticks; empty uses the route's wait. */
    public synchronized void waitTicks(int index, Optional<Integer> ticks) {
        Objects.checkIndex(index, points.size());
        if (ticks.isPresent() && (ticks.get() < 0 || ticks.get() > WaypointSettings.MAXIMUM_WAIT))
            throw new IllegalArgumentException("A wait is 0.." + WaypointSettings.MAXIMUM_WAIT + " ticks");
        if (ticks.isPresent()) points.get(index).addProperty("waitTicks", ticks.get());
        else points.get(index).remove("waitTicks");
    }

    /** Each point's own wait as written, {@code 40} or {@code 20-60}; empty uses the route's wait. */
    public synchronized List<Optional<String>> waits() {
        List<Optional<String>> waits = new ArrayList<>(points.size());
        for (JsonObject point : points) {
            JsonElement wait = point.get("waitTicks");
            if (wait == null) waits.add(Optional.empty());
            else if (wait.isJsonArray()) waits.add(Optional.of(wait.getAsJsonArray().get(0).getAsInt() + "-" + wait.getAsJsonArray().get(1).getAsInt()));
            else waits.add(Optional.of(String.valueOf(wait.getAsInt())));
        }
        return waits;
    }

    /** The current point's index, or {@code -1} when there are no points. */
    public synchronized int current() { return current; }

    public synchronized void select(int index) {
        Objects.checkIndex(index, points.size());
        current = index;
    }

    public synchronized List<Vec3> points() {
        List<Vec3> list = new ArrayList<>(points.size());
        for (JsonObject point : points) list.add(new Vec3(point.get("x").getAsDouble(), point.get("y").getAsDouble(), point.get("z").getAsDouble()));
        return list;
    }

    /** The route document, checked as the runtime reads it. */
    public synchronized String document() throws IOException {
        if (points.isEmpty()) throw new IllegalStateException("No points yet; hold the wand and tap sneak where the NPC should walk");
        JsonObject route = settings.deepCopy();
        JsonArray list = new JsonArray();
        for (JsonObject point : points) list.add(point.deepCopy());
        route.add("points", list);
        String text = JsonDocuments.write(route);
        WaypointSettings.document(text);
        return text;
    }

    private void place(JsonObject point, ScreenHost.Pose pose) {
        if (!in(pose)) throw new IllegalStateException("Route points must stay in the world the capture started in");
        point.addProperty("x", Math.floor(pose.x()) + 0.5);
        point.addProperty("y", Math.round(pose.y() * 100) / 100.0);
        point.addProperty("z", Math.floor(pose.z()) + 0.5);
    }

    /** Junction targets at or past {@code from} move by {@code by}; on a delete, junctions to the deleted point go. */
    private void renumber(int from, int by) {
        for (JsonObject point : points) {
            if (!point.has("next")) continue;
            JsonArray next = new JsonArray();
            for (JsonElement junction : point.getAsJsonArray("next")) {
                JsonObject entry = junction.getAsJsonObject().deepCopy();
                int to = entry.get("to").getAsInt();
                if (by < 0 && to == from) continue;
                if (to >= from) entry.addProperty("to", to + by);
                next.add(entry);
            }
            if (next.isEmpty()) point.remove("next"); else point.add("next", next);
        }
    }
}
