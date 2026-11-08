package art.arcane.animus.bukkit.metrics;

import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.metrics.UsageStats;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns every bStats type so AnimusPlugin never names one: a library type in the plugin class's code is resolved when
 * that class links, so chart code stays here. bStats also honours its own global opt-out in plugins/bStats/config.yml.
 */
public final class AnimusBStats {
    /**
     * What the charts read. bStats calls them on its own thread, Folia included, so each returns only volatile or
     * immutable state, and null when Animus is not running.
     */
    public interface Source {
        /** {@code Paper}, {@code Spigot} or {@code Folia}. */
        String platform();
        /** The last rolled metrics snapshot. */
        MetricsSnapshot snapshot();
        /** The published NPC registry; its identity changes only when a new registry is published. */
        Map<String, NpcDefinition> definitions();
        /** {@code Off}, {@code On} or {@code Paired}. */
        String editor();
    }

    private final Logger logger;
    private final Source source;
    private Metrics metrics;
    private Map<String, NpcDefinition> counted;
    private UsageStats usage;

    private AnimusBStats(Logger logger, Source source, Metrics metrics) {
        this.logger = logger;
        this.source = source;
        this.metrics = metrics;
    }

    public static AnimusBStats start(JavaPlugin plugin, int pluginId, Source source) {
        AnimusBStats runtime = new AnimusBStats(plugin.getLogger(), source, new Metrics(plugin, pluginId));
        runtime.registerCharts();
        return runtime;
    }

    // Every callable returns null to skip this submission while Animus is starting or stopping.
    private void registerCharts() {
        metrics.addCustomChart(new SimplePie("npc_count", () -> {
            UsageStats current = usage();
            return current == null ? null : UsageStats.bucket(current.npcs());
        }));
        metrics.addCustomChart(new SingleLineChart("active_npcs", () -> {
            MetricsSnapshot snapshot = source.snapshot();
            if (snapshot == null || snapshot.gauges() == RuntimeGauges.NONE) return null;
            return snapshot.gauges().active();
        }));
        metrics.addCustomChart(new SimplePie("platform", source::platform));
        metrics.addCustomChart(new SimplePie("web_editor", source::editor));
        metrics.addCustomChart(new AdvancedPie("import_sources", () -> {
            UsageStats current = usage();
            if (current == null) return null;
            Map<String, Integer> used = new LinkedHashMap<>();
            for (String name : current.importSources()) used.put(name, 1);
            if (used.isEmpty()) used.put("None", 1);
            return used;
        }));
        metrics.addCustomChart(new SimplePie("trait_groups", () -> {
            UsageStats current = usage();
            return current == null ? null : UsageStats.bucket(current.traitGroupsInUse());
        }));
        metrics.addCustomChart(new SimplePie("killable_npcs", () -> {
            UsageStats current = usage();
            return current == null ? null : String.valueOf(current.killable());
        }));
    }

    /** One pass over the definitions per published registry, shared by every chart in a submission. */
    private synchronized UsageStats usage() {
        Map<String, NpcDefinition> definitions = source.definitions();
        if (definitions == null) return null;
        if (definitions != counted) {
            usage = UsageStats.of(definitions.values());
            counted = definitions;
        }
        return usage;
    }

    public void shutdown() {
        Metrics activeMetrics = metrics;
        metrics = null;
        if (activeMetrics != null) {
            try {
                activeMetrics.shutdown();
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Error shutting down Animus metrics", failure);
            }
        }
    }
}
