package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import java.util.*;

/** Last valid route data outlives its execution; an unchanged reload cannot restart a completed once route. */
public final class WaypointProgress {
    private WaypointSettings settings;
    private int next;
    private boolean complete;
    public WaypointProgress(WaypointSettings settings) { this.settings = Objects.requireNonNull(settings); }
    public WaypointSettings settings() { return settings; }
    public boolean complete() { return complete; }
    public boolean reload(WaypointSettings replacement) {
        Objects.requireNonNull(replacement);
        if (settings.equals(replacement)) return false;
        settings = replacement; next = 0; complete = false; return true;
    }
    public OptionalInt arrived(int waypoint) {
        if (complete || waypoint != next) throw new IllegalStateException("Waypoint arrival is no longer current");
        next++;
        if (next == settings.points().size()) {
            if (settings.loop()) next = 0;
            else { complete = true; return OptionalInt.empty(); }
        }
        return OptionalInt.of(next);
    }
}
