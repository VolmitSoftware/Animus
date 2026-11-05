package art.arcane.animus.bukkit.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.metrics.ReactMetricCatalog;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.react.api.metric.ReactMetric;
import art.arcane.react.api.metric.ReactMetricKind;
import art.arcane.react.api.metric.ReactMetricSource;
import art.arcane.react.api.metric.ReactMetrics;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReactBridgeTest {
    /** React's MetricKeys rule for source IDs and metric keys. */
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");

    /** Plays React's part: one registration slot, discovery by the interface class, then declaration. */
    private static final class FakeReact implements ReactBridge.Registrar {
        Class<?> service;
        Object provider;
        @Override public void register(Class<?> type, Object registered) { service = type; provider = registered; }
        @Override public void unregister(Object registered) { if (provider == registered) { provider = null; service = null; } }

        /** What ThirdPartyMetricRegistry.reconcile does with a registration. */
        List<ReactMetric> reconcile() {
            assertSame(ReactMetricSource.class, service);
            ReactMetricSource source = (ReactMetricSource) provider;
            String id = source.sourceId();
            assertTrue(SOURCE.matcher(id).matches(), id);
            List<ReactMetric> declared = source.metrics();
            assertTrue(declared.size() <= 24);
            for (ReactMetric metric : declared) {
                assertTrue(metric.key().startsWith(id + ".") && metric.key().length() <= 64, metric.key());
                ReactMetrics.DECLARED.add(metric.key());
            }
            ReactMetrics.accepting = true;
            return declared;
        }
    }

    @AfterEach void reset() { ReactMetrics.reset(); }

    @Test void registersAProxyOfReactsInterfaceAndPublishesAfterDeclaration() throws Exception {
        FakeReact react = new FakeReact();
        ReactBridge bridge = ReactBridge.bind(getClass().getClassLoader(), react);
        assertNotNull(react.provider);

        AtomicLong nanos = new AtomicLong();
        AnimusMetrics metrics = new AnimusMetrics(nanos::get, () -> 1L);
        metrics.gauges(() -> new RuntimeGauges("test", 12, 7, 0, 0, 1, 7, 0, 0, 0, false, false, 0, 0, 0, List.of()));
        metrics.sent(null, null, PacketKind.MOVE, 40);
        for (int i = 0; i < 20; i++) { nanos.addAndGet(50_000_000L); metrics.serverTick(); }

        bridge.publish(metrics.snapshot());
        assertTrue(ReactMetrics.PUBLISHED.isEmpty(), "nothing is published before React declares the source");

        List<ReactMetric> declared = react.reconcile();
        assertEquals(ReactMetricCatalog.SPECS.size(), declared.size());
        ReactMetric bytes = declared.stream().filter((ReactMetric metric) -> metric.key().equals("animus.net.bytes_per_second")).findFirst().orElseThrow();
        assertEquals(ReactMetricKind.BYTES, bytes.kind());
        assertEquals(Material.REPEATER, bytes.icon());

        bridge.publish(metrics.snapshot());
        assertEquals(ReactMetricCatalog.SPECS.size(), ReactMetrics.PUBLISHED.size());
        assertEquals(7.0, ReactMetrics.PUBLISHED.get("animus.npcs.active"));
        assertEquals(40.0, ReactMetrics.PUBLISHED.get("animus.net.packets_per_second"));

        ReactMetricSource source = (ReactMetricSource) react.provider;
        assertEquals(source, source);
        assertFalse(source.equals(new Object()));

        bridge.close();
        assertNull(react.provider);
        assertTrue(bridge.closed());
        ReactMetrics.PUBLISHED.clear();
        bridge.publish(metrics.snapshot());
        assertTrue(ReactMetrics.PUBLISHED.isEmpty());
    }

    @Test void bindingFailsCleanlyWithoutReact() {
        ClassLoader empty = new ClassLoader(null) {};
        FakeReact react = new FakeReact();
        assertThrows(ClassNotFoundException.class, () -> ReactBridge.bind(empty, react));
        assertNull(react.provider);
    }
}
