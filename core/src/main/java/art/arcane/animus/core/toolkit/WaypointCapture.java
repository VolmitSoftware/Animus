package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Route points a player drops where they stand, in one world. Points snap to the block centre, which is where a
 * whole-number route point leads anyway. The saved document is a looping walk through the points, as {@code routes/<id>.json}.
 */
public final class WaypointCapture {
    private final Set<String> worlds;
    private final List<Vec3> points = new ArrayList<>();

    public WaypointCapture(Set<String> worlds) { this.worlds = Set.copyOf(worlds); }

    /** The player stands in the world the capture started in. */
    public boolean in(ScreenHost.Pose pose) { return !Collections.disjoint(worlds, pose.worlds()); }

    /** Adds the point under the player; returns the point count. */
    public synchronized int add(ScreenHost.Pose pose) {
        if (!in(pose)) throw new IllegalStateException("Route points must stay in the world the capture started in");
        if (points.size() >= WaypointSettings.MAXIMUM_POINTS) throw new IllegalStateException("A route holds at most " + WaypointSettings.MAXIMUM_POINTS + " points");
        points.add(new Vec3(Math.floor(pose.x()) + 0.5, Math.round(pose.y() * 100) / 100.0, Math.floor(pose.z()) + 0.5));
        return points.size();
    }

    public synchronized boolean removeLast() {
        if (points.isEmpty()) return false;
        points.removeLast();
        return true;
    }

    public synchronized List<Vec3> points() { return List.copyOf(points); }

    /** The route document, checked as the runtime reads it. */
    public synchronized String document() throws IOException {
        if (points.isEmpty()) throw new IllegalStateException("No points yet; hold the wand and tap sneak where the NPC should walk");
        JsonObject route = new JsonObject();
        route.addProperty("schemaVersion", 1);
        route.addProperty("loop", true);
        JsonArray list = new JsonArray();
        for (Vec3 point : points) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", point.x()); entry.addProperty("y", point.y()); entry.addProperty("z", point.z());
            list.add(entry);
        }
        route.add("points", list);
        String text = JsonDocuments.write(route);
        WaypointSettings.document(text);
        return text;
    }
}
