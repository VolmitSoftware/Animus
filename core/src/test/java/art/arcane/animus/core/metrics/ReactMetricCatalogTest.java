package art.arcane.animus.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import art.arcane.animus.core.behavior.schema.RepositoryFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReactMetricCatalogTest {
    /** React's own key rule (MetricKeys): the source prefix, then lowercase runs split by single separators. */
    private static final Pattern REACT_KEY = Pattern.compile("animus\\.[a-z0-9]+(?:[._-][a-z0-9]+)*");
    private static final Set<String> KINDS = Set.of("GAUGE", "COUNTER", "RATE", "PERCENT", "MILLIS", "BYTES");

    @Test void catalogFitsReactLimits() {
        assertTrue(ReactMetricCatalog.SPECS.size() <= ReactMetricCatalog.LIMIT);
        Set<String> keys = new HashSet<>(), samplerIds = new HashSet<>();
        for (ReactMetricCatalog.Spec spec : ReactMetricCatalog.SPECS) {
            assertTrue(spec.key().length() <= 64, spec.key());
            assertTrue(REACT_KEY.matcher(spec.key()).matches(), spec.key());
            assertTrue(KINDS.contains(spec.kind()), spec.kind());
            assertTrue(spec.displayName().length() <= 48, spec.displayName());
            assertTrue(spec.unit().length() <= 16, spec.unit());
            assertTrue(keys.add(spec.key()), "duplicate " + spec.key());
            assertTrue(samplerIds.add(spec.key().replaceAll("[^a-z0-9]", "-")), "sampler id collision " + spec.key());
        }
    }

    @Test void catalogMatchesTheReactMapContract() {
        Set<String> keys = new HashSet<>();
        for (ReactMetricCatalog.Spec spec : ReactMetricCatalog.SPECS) keys.add(spec.key());
        for (String subsystem : List.of("presentation", "navigation", "combat", "behaviors", "rigs", "gui", "hotload", "editor"))
            assertTrue(keys.contains("animus.cost." + subsystem + "_ms"), subsystem);
        for (String pressure : List.of("navigation", "presentation", "combat")) assertTrue(keys.contains("animus.pressure." + pressure), pressure);
        assertTrue(keys.containsAll(List.of("animus.net.bytes_per_second", "animus.net.packets_per_second", "animus.net.player_peak.bytes_per_second",
                "animus.npcs.active", "animus.npcs.sleeping", "animus.npcs.viewers")));
        assertEquals(7, keys.stream().filter((String key) -> key.startsWith("animus.net.kind.") && key.endsWith(".bytes_per_second")).count());
        assertFalse(keys.contains("animus.cost.total_ms"));
        assertEquals(24, keys.size());
    }

    @Test void valuesReadTheSnapshot() {
        AtomicLong nanos = new AtomicLong();
        AnimusMetrics metrics = new AnimusMetrics(nanos::get, () -> 1L);
        UUID npc = UUID.randomUUID();
        metrics.gauges(() -> new RuntimeGauges("test", 9, 4, 0, 2, 1, 4, 0, 0, 0, false, true, 0, 35, 80, List.of(new RuntimeGauges.NpcPlace(npc, "world", 1, 2))));
        metrics.extra("combat.load", () -> 250);
        metrics.sent(metrics.viewer(UUID.randomUUID(), "Alex"), metrics.npc(npc), PacketKind.MOVE, 20);
        metrics.sent(null, null, PacketKind.EQUIPMENT, 1, 10);
        metrics.sent(null, null, PacketKind.METADATA, 1, 5);
        metrics.cost(Subsystem.NAVIGATION, 10_000_000L);
        metrics.cost(Subsystem.EDITOR, 3_000_000L);
        for (int i = 0; i < 20; i++) { nanos.addAndGet(50_000_000L); metrics.serverTick(); }
        Map<String, Double> values = ReactMetricCatalog.values(metrics.snapshot());
        assertEquals(ReactMetricCatalog.SPECS.size(), values.size());
        assertEquals(4.0, values.get("animus.npcs.active"));
        assertEquals(2.0, values.get("animus.npcs.sleeping"));
        assertEquals(1.0, values.get("animus.npcs.viewers"));
        assertEquals(22.0, values.get("animus.net.packets_per_second"));
        assertEquals(10.0, values.get("animus.cost.navigation_ms"), 1e-9, "ms of work per second");
        assertEquals(3.0, values.get("animus.cost.editor_ms"), 1e-9);
        assertEquals(35.0, values.get("animus.pressure.navigation"));
        assertEquals(80.0, values.get("animus.pressure.presentation"));
        assertEquals(100.0, values.get("animus.pressure.combat"), "clamped to 0..100");
        assertEquals(15.0, values.get("animus.net.kind.metadata.bytes_per_second"), "metadata carries equipment");
        assertEquals(20.0 * PacketKind.MOVE.typicalBytes(), values.get("animus.net.player_peak.bytes_per_second"));
    }

    @Test void docsListEveryKey() throws Exception {
        Path page = RepositoryFiles.wikiPage("17-debug-react-metrics.md");
        assumeTrue(Files.isRegularFile(page), "docs checkout not beside the repository");
        String text = Files.readString(page);
        for (ReactMetricCatalog.Spec spec : ReactMetricCatalog.SPECS) assertTrue(text.contains("`" + spec.key() + "`"), page + " lacks " + spec.key());
    }
}
