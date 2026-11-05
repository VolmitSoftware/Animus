package art.arcane.react.api.metric;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test copy of React's static publishing gateway; records what it accepts like React's PublishedMetricStore. */
public final class ReactMetrics {
    public static final Map<String, Double> PUBLISHED = new ConcurrentHashMap<>();
    public static final Set<String> DECLARED = ConcurrentHashMap.newKeySet();
    public static volatile boolean accepting;

    private ReactMetrics() {}

    public static boolean accepting(String sourceId) { return accepting && "animus".equals(sourceId); }

    public static boolean publish(String sourceId, String key, double value) {
        if (!accepting(sourceId) || !DECLARED.contains(key) || !Double.isFinite(value)) return false;
        PUBLISHED.put(key, value);
        return true;
    }

    public static void reset() { PUBLISHED.clear(); DECLARED.clear(); accepting = false; }
}
