package art.arcane.animus.core.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * The server-wide numbers Animus publishes to React under source {@link #SOURCE_ID}, matching the contract React's
 * Animus maps read: 8 costs, 3 pressures, 3 network totals, 7 packet kinds and 3 NPC counts. React accepts at most
 * {@link #LIMIT} metrics per source and only scalars; per-chunk and per-player data stay in {@code /animus debug}.
 * The wiki page animus/17-debug-react-metrics lists every key.
 */
public final class ReactMetricCatalog {
    public static final String SOURCE_ID = "animus";
    public static final int LIMIT = 24;

    /** {@code kind} is a React {@code ReactMetricKind} constant name. */
    public record Spec(String key, String kind, String unit, String displayName, int decimals, ToDoubleFunction<MetricsSnapshot> value) {
        public Spec {
            Objects.requireNonNull(key); Objects.requireNonNull(kind); Objects.requireNonNull(unit); Objects.requireNonNull(displayName); Objects.requireNonNull(value);
        }
    }

    /** Published as {@code animus.cost.<key>_ms}: milliseconds of work per second, rolling over 10 seconds. */
    static final List<Subsystem> COSTS = List.of(Subsystem.PRESENTATION, Subsystem.NAVIGATION, Subsystem.COMBAT, Subsystem.BEHAVIORS,
            Subsystem.RIGS, Subsystem.GUI, Subsystem.HOTLOAD, Subsystem.EDITOR);
    /** Published as {@code animus.net.kind.<key>.bytes_per_second}; {@code metadata} also carries equipment and passenger packets. */
    static final List<PacketKind> KINDS = List.of(PacketKind.SPAWN, PacketKind.DESPAWN, PacketKind.MOVE, PacketKind.METADATA,
            PacketKind.HOLOGRAM, PacketKind.RIG_BONES, PacketKind.GUI_WINDOW);

    public static final List<Spec> SPECS = specs();

    private ReactMetricCatalog() {}

    private static List<Spec> specs() {
        List<Spec> specs = new ArrayList<>();
        for (Subsystem subsystem : COSTS)
            specs.add(new Spec("animus.cost." + subsystem.key() + "_ms", "MILLIS", "ms", "Animus " + subsystem.key() + " ms/s", 2,
                    (MetricsSnapshot s) -> s.tenSeconds().millisPerSecond(subsystem)));
        specs.add(percent("animus.pressure.navigation", "Animus navigation budget", (MetricsSnapshot s) -> s.gauges().navigationLoad()));
        specs.add(percent("animus.pressure.presentation", "Animus packet budget", (MetricsSnapshot s) -> s.gauges().presentationLoad()));
        specs.add(percent("animus.pressure.combat", "Animus combat slots", (MetricsSnapshot s) -> Math.clamp(s.extra("combat.load"), 0, 100)));
        specs.add(new Spec("animus.net.bytes_per_second", "BYTES", "B", "Animus est. bytes out/s", 0, (MetricsSnapshot s) -> s.tenSeconds().bytesPerSecond()));
        specs.add(new Spec("animus.net.packets_per_second", "RATE", "pkt", "Animus packets out", 1, (MetricsSnapshot s) -> s.tenSeconds().packetsPerSecond()));
        specs.add(new Spec("animus.net.player_peak.bytes_per_second", "BYTES", "B", "Animus busiest player est. bytes/s", 0,
                (MetricsSnapshot s) -> s.viewers().isEmpty() ? 0 : s.viewers().get(0).bytes()));
        for (PacketKind kind : KINDS)
            specs.add(new Spec("animus.net.kind." + kind.key() + ".bytes_per_second", "BYTES", "B", "Animus " + kind.key() + " est. bytes/s", 0,
                    (MetricsSnapshot s) -> kindBytes(s.tenSeconds(), kind)));
        specs.add(new Spec("animus.npcs.active", "GAUGE", "", "Animus active NPCs", 0, (MetricsSnapshot s) -> s.gauges().active()));
        specs.add(new Spec("animus.npcs.sleeping", "GAUGE", "", "Animus sleeping patrols", 0, (MetricsSnapshot s) -> s.gauges().sleepingRoutes()));
        specs.add(new Spec("animus.npcs.viewers", "GAUGE", "", "Animus viewers", 0, (MetricsSnapshot s) -> s.gauges().viewers()));
        return List.copyOf(specs);
    }

    private static Spec percent(String key, String name, ToDoubleFunction<MetricsSnapshot> value) { return new Spec(key, "PERCENT", "%", name, 0, value); }

    private static double kindBytes(MetricsSnapshot.Rates rates, PacketKind kind) {
        double bytes = rates.bytesPerSecond(kind);
        if (kind == PacketKind.METADATA) bytes += rates.bytesPerSecond(PacketKind.EQUIPMENT) + rates.bytesPerSecond(PacketKind.PASSENGERS);
        return bytes;
    }

    /** Every published key with its value at this snapshot. Non-finite values become 0. */
    public static Map<String, Double> values(MetricsSnapshot snapshot) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            double value = spec.value().applyAsDouble(snapshot);
            values.put(spec.key(), Double.isFinite(value) ? value : 0);
        }
        return values;
    }
}
