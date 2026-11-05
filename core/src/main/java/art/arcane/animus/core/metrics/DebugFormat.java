package art.arcane.animus.core.metrics;

import java.util.Locale;

/** Byte formatting shared by the debug command and the watch line. */
public final class DebugFormat {
    private DebugFormat() {}

    /** {@code 1.2 KB} style with binary units; callers mark the value as an estimate. */
    public static String bytes(double value) {
        double magnitude = Math.max(0, value);
        if (magnitude < 1024) return String.format(Locale.ROOT, "%.0f B", magnitude);
        if (magnitude < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", magnitude / 1024);
        if (magnitude < 1024.0 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", magnitude / (1024 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", magnitude / (1024.0 * 1024 * 1024));
    }
}
