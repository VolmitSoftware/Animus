package art.arcane.animus.bukkit.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bStats refuses to start from its own package, so the jar must carry it relocated; and only {@link AnimusBStats}
 * may name its types, so AnimusPlugin and AnimusBukkit link even if the library is ever loaded another way.
 */
class BStatsShadingTest {
    private static final String RELOCATED = "art/arcane/animus/internal/bstats/";

    @Test
    void theJarCarriesBStatsOnlyUnderTheInternalPackage() throws IOException {
        try (ZipFile jar = new ZipFile(pluginJar().toFile())) {
            List<String> names = Collections.list(jar.entries()).stream().map(ZipEntry::getName).toList();
            assertFalse(names.stream().anyMatch((String name) -> name.startsWith("org/bstats/")), "unrelocated bStats classes in the jar");
            assertNotNull(jar.getEntry(RELOCATED + "bukkit/Metrics.class"), "relocated bStats Metrics is missing");
            String metrics = constants(jar, "art/arcane/animus/bukkit/metrics/AnimusBStats.class");
            assertTrue(metrics.contains(RELOCATED + "bukkit/Metrics"), "AnimusBStats must use the relocated Metrics");
        }
    }

    @Test
    void thePluginClassNamesNoBStatsType() throws IOException {
        try (ZipFile jar = new ZipFile(pluginJar().toFile())) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                boolean pluginClass = name.startsWith("art/arcane/animus/bukkit/AnimusPlugin") || name.startsWith("art/arcane/animus/bukkit/AnimusBukkit");
                if (!pluginClass || !name.endsWith(".class")) continue;
                String constants = constants(jar, name);
                assertFalse(constants.contains("/bstats/"), name + " references a bStats type");
            }
        }
    }

    private static String constants(ZipFile jar, String name) throws IOException {
        ZipEntry entry = Objects.requireNonNull(jar.getEntry(name), name + " is missing");
        try (InputStream stream = jar.getInputStream(entry)) { return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1); }
    }

    private static Path pluginJar() {
        return Path.of(Objects.requireNonNull(System.getProperty("animus.pluginJar"), "animus.pluginJar is not set; run through Gradle"));
    }
}
