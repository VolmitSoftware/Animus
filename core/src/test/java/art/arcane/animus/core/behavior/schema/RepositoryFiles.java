package art.arcane.animus.core.behavior.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Locates files shipped in the repository, wherever the build runs the tests from. */
final class RepositoryFiles {
    private RepositoryFiles() {}
    static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
    /** A page of the Animus section in the VolmitSoftware/docs checkout beside this repository. */
    static Path wikiPage(String page) { return root().resolveSibling("docs").resolve("animus").resolve(page); }
    static Path packs() { return root().resolve("examples/packs"); }
    static List<Path> packDirectories() throws IOException {
        try (Stream<Path> children = Files.list(packs())) { return children.filter(Files::isDirectory).sorted().toList(); }
    }
    static List<Path> jsonUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.walk(directory)) { return files.filter(file -> file.toString().endsWith(".json")).sorted().toList(); }
    }
}
