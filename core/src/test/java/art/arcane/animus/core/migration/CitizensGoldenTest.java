package art.arcane.animus.core.migration;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The synthetic Citizens saves under schema/fixtures/citizens convert to exactly the committed staged trees under
 * expected/, which animus-web's browser converter must also reproduce byte for byte.
 *
 * <p>After an intended converter change, regenerate the expected trees from the worktree root with
 * {@code ./gradlew :core:test --tests 'art.arcane.animus.core.migration.CitizensGoldenTest' -Danimus.regenerateGolden=true},
 * review the diff, then run animus-web's {@code dart test test/citizens_converter_test.dart}.
 */
class CitizensGoldenTest {
    @TempDir Path temporary;

    private static Path fixtures() { return SharedFixtures.fixture("citizens"); }

    @TestFactory List<DynamicTest> casesMatchTheCommittedTrees() throws IOException {
        JsonObject index = JsonDocuments.object(Files.readString(fixtures().resolve("index.json")));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement entry : index.getAsJsonArray("cases")) {
            JsonObject definition = entry.getAsJsonObject();
            String name = definition.get("name").getAsString();
            tests.add(DynamicTest.dynamicTest(name, () -> check(name, definition)));
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    private void check(String name, JsonObject definition) throws IOException {
        Map<String, String> worlds = new TreeMap<>();
        definition.getAsJsonObject("worldMap").entrySet().forEach(e -> worlds.put(e.getKey(), e.getValue().getAsString()));
        Path data = Files.createDirectories(temporary.resolve(name));
        CitizensImporter.Preview preview = new CitizensImporter().preview(fixtures().resolve(definition.get("source").getAsString()), data, worlds);
        Path expected = fixtures().resolve("expected/" + name);
        if (Boolean.getBoolean("animus.regenerateGolden")) {
            delete(expected);
            for (Map.Entry<String, byte[]> file : tree(preview.directory()).entrySet()) {
                Path target = expected.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
        }
        Map<String, byte[]> actual = tree(preview.directory());
        Map<String, byte[]> wanted = tree(expected);
        assertEquals(wanted.keySet(), actual.keySet(), name + ": staged file list");
        for (String path : wanted.keySet())
            assertEquals(new String(wanted.get(path), java.nio.charset.StandardCharsets.UTF_8), new String(actual.get(path), java.nio.charset.StandardCharsets.UTF_8), name + ": " + path);
        for (String path : wanted.keySet()) assertArrayEquals(wanted.get(path), actual.get(path), name + ": " + path + " bytes");
        assertEquals(ImportDigest.outputHash(expected), ImportDigest.outputHash(preview.directory()));
    }

    @TestFactory List<DynamicTest> invalidSavesAreRejected() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(fixtures().resolve("invalid"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                tests.add(DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
                    Path data = Files.createTempDirectory(temporary, "invalid-");
                    assertThrows(IOException.class, () -> new CitizensImporter().preview(file, data, Map.of()));
                    try (Stream<Path> staged = Files.list(data.resolve("imports"))) { assertEquals(0, staged.count(), "nothing staged"); }
                }));
            }
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    private static Map<String, byte[]> tree(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(root)) return files;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return files;
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
