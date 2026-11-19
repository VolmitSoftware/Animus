package art.arcane.animus.bukkit;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spigot and Paper put Gson and SnakeYAML on every plugin's classpath, so the jar carries neither and its classes,
 * bundled VolmLib and Adventure included, name the server's copies. ServerApiLinkageTest checks each target has them.
 */
class ServerLibrariesTest {
    private static final List<String> NOT_BUNDLED = List.of("com/google/gson/", "org/yaml/snakeyaml/",
            "art/arcane/animus/internal/gson/", "art/arcane/animus/internal/snakeyaml/");

    @Test
    void theJarBundlesNeitherGsonNorSnakeYaml() throws IOException {
        try (ZipFile jar = new ZipFile(pluginJar().toFile())) {
            List<String> bundled = Collections.list(jar.entries()).stream().map(ZipEntry::getName)
                    .filter((String name) -> NOT_BUNDLED.stream().anyMatch(name::startsWith)).toList();
            assertEquals(List.of(), bundled.stream().limit(5).toList(), bundled.size() + " Gson or SnakeYAML entries are bundled");
        }
    }

    @Test
    void animusVolmLibAndAdventureNameTheServersGsonAndSnakeYaml() throws IOException {
        try (ZipFile jar = new ZipFile(pluginJar().toFile())) {
            assertNames(jar, "art/arcane/animus/core/storage/JsonDocuments.class", "com/google/gson/stream/JsonReader");
            assertNames(jar, "art/arcane/animus/core/migration/CitizensImporter.class", "org/yaml/snakeyaml/Yaml");
            assertNames(jar, "art/arcane/animus/internal/volmlib/util/config/ConfigJson.class", "com/google/gson/GsonBuilder");
            assertNames(jar, "art/arcane/animus/internal/kyori/adventure/text/serializer/gson/GsonComponentSerializerImpl.class", "com/google/gson/Gson");
        }
    }

    private static void assertNames(ZipFile jar, String name, String library) throws IOException {
        ZipEntry entry = Objects.requireNonNull(jar.getEntry(name), name + " is missing");
        String constants;
        try (InputStream stream = jar.getInputStream(entry)) { constants = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1); }
        assertTrue(constants.contains(library), name + " does not name " + library);
    }

    private static Path pluginJar() {
        return Path.of(Objects.requireNonNull(System.getProperty("animus.pluginJar"), "animus.pluginJar is not set; run through Gradle"));
    }
}
