package art.arcane.animus.core.migration;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The synthetic Shopkeepers saves under schema/fixtures/shopkeepers convert to exactly the committed staged trees under
 * expected/, which animus-web's browser converter must also reproduce byte for byte.
 *
 * <p>After an intended converter change, regenerate the expected trees from the worktree root with
 * {@code ./gradlew :core:test --tests 'art.arcane.animus.core.migration.ShopkeepersGoldenTest' -Danimus.regenerateGolden=true},
 * review the diff, then run animus-web's {@code dart test test/shopkeepers_converter_test.dart}.
 */
class ShopkeepersGoldenTest {
    @TempDir Path temporary;

    private static Path fixtures() { return SharedFixtures.fixture("shopkeepers"); }

    private static Optional<Path> optional(JsonObject definition, String key) {
        return definition.has(key) ? Optional.of(fixtures().resolve(definition.get(key).getAsString())) : Optional.empty();
    }

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
        Path data = Files.createDirectories(temporary.resolve(name));
        ShopkeepersImporter.Preview preview;
        try (DocumentStore store = new DocumentStore(data)) {
            preview = new ShopkeepersImporter().preview(fixtures().resolve(definition.get("source").getAsString()), optional(definition, "config"), optional(definition, "citizens"), store);
        }
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
            assertEquals(new String(wanted.get(path), StandardCharsets.UTF_8), new String(actual.get(path), StandardCharsets.UTF_8), name + ": " + path);
        assertEquals(ImportDigest.outputHash(expected), ImportDigest.outputHash(preview.directory()));
    }

    @TestFactory List<DynamicTest> invalidSavesAreRejected() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(fixtures().resolve("invalid"))) {
            for (Path file : files.filter((Path path) -> path.toString().endsWith(".yml")).sorted().toList()) {
                tests.add(DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
                    Path data = Files.createTempDirectory(temporary, "invalid-");
                    try (DocumentStore store = new DocumentStore(data)) {
                        assertThrows(IOException.class, () -> new ShopkeepersImporter().preview(file, Optional.empty(), Optional.empty(), store));
                    }
                    try (Stream<Path> staged = Files.list(data.resolve(ShopkeepersImporter.IMPORTS))) { assertEquals(0, staged.count(), "nothing staged"); }
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
            for (Path path : paths.filter(Files::isRegularFile).toList()) files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return files;
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
