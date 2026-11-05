package art.arcane.react.api.metric;

import java.util.Objects;
import org.bukkit.Material;

/** Test copy of React's metric declaration record, with React's normalization. */
public record ReactMetric(String key, ReactMetricKind kind, String unit, String displayName, Material icon, int decimals) {
    public ReactMetric {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        unit = unit == null ? "" : unit.strip();
        displayName = displayName == null || displayName.isBlank() ? key : displayName.strip();
        icon = icon == null ? Material.SLIME_BALL : icon;
        decimals = Math.max(0, Math.min(4, decimals));
    }
}
