package art.arcane.animus.bukkit.integration.typewriter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The installed extension names the running Typewriter version, carries every class extension.json names, and is rewritten only when it changes. */
class TypewriterExtensionTest {
    @TempDir Path typewriter;

    private static JarContent read(byte[] jar) throws IOException {
        Set<String> names = new HashSet<>();
        String descriptor = null;
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry entry = in.getNextJarEntry(); entry != null; entry = in.getNextJarEntry()) {
                names.add(entry.getName());
                if (entry.getName().equals("extension.json")) descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new JarContent(names, descriptor);
    }

    private record JarContent(Set<String> names, String descriptor) {}

    @Test void installedJarNamesTheEngineVersionAndCarriesEveryNamedClass() throws IOException {
        Optional<String> installed = TypewriterExtension.install(typewriter, "0.9.0-beta-177", "0.1.0", getClass().getClassLoader());
        assertEquals(Optional.of("Installed the Animus extension for Typewriter 0.9.0-beta-177"), installed);
        JarContent jar = read(Files.readAllBytes(typewriter.resolve("extensions/AnimusExtension.jar")));
        JsonObject descriptor = JsonParser.parseString(jar.descriptor()).getAsJsonObject();
        assertEquals("0.9.0-beta-177", descriptor.getAsJsonObject("extension").get("engineVersion").getAsString());
        assertEquals("0.1.0", descriptor.getAsJsonObject("extension").get("version").getAsString());
        Set<String> named = new HashSet<>();
        descriptor.getAsJsonArray("entries").forEach(entry -> named.add(entry.getAsJsonObject().get("className").getAsString()));
        descriptor.getAsJsonArray("entryListeners").forEach(listener -> {
            named.add(listener.getAsJsonObject().get("className").getAsString());
            named.add(listener.getAsJsonObject().get("entryClassName").getAsString());
        });
        for (String type : named) assertTrue(jar.names().contains(type.replace('.', '/') + ".class"), "missing " + type);
    }

    @Test void sameVersionsLeaveTheFileAlone_newVersionReplacesIt() throws IOException {
        TypewriterExtension.install(typewriter, "0.9.0-beta-177", "0.1.0", getClass().getClassLoader());
        assertEquals(Optional.empty(), TypewriterExtension.install(typewriter, "0.9.0-beta-177", "0.1.0", getClass().getClassLoader()));
        assertEquals(Optional.of("Updated the Animus extension for Typewriter 0.9.0-beta-178"),
                TypewriterExtension.install(typewriter, "0.9.0-beta-178", "0.1.0", getClass().getClassLoader()));
        assertTrue(read(Files.readAllBytes(typewriter.resolve("extensions/AnimusExtension.jar"))).descriptor().contains("0.9.0-beta-178"));
    }

    @Test void missingResourcesAreNamed() {
        ClassLoader empty = new ClassLoader(null) { @Override public InputStream getResourceAsStream(String name) { return null; } };
        IOException missing = assertThrows(IOException.class, () -> TypewriterExtension.install(typewriter, "1", "1", empty));
        assertTrue(missing.getMessage().contains("integrations/typewriter"), missing.getMessage());
    }
}
