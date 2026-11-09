package art.arcane.animus.core.behavior.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Locates files shipped in the repository, wherever the build runs the tests from. */
public final class RepositoryFiles {
    private RepositoryFiles() {}
    static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
    /**
     * A page of the Animus section in the VolmitSoftware/docs checkout. The {@code animus.docs} property or {@code ANIMUS_DOCS}
     * variable names the checkout; otherwise it is the {@code docs} folder beside this repository or, from a worktree, beside the main checkout.
     */
    public static Path wikiPage(String page) { return docs().resolve("animus").resolve(page); }
    private static Path docs() {
        String named = System.getProperty("animus.docs", System.getenv("ANIMUS_DOCS"));
        if (named != null && !named.isBlank()) return Path.of(named);
        Path beside = root().resolveSibling("docs");
        if (Files.isDirectory(beside)) return beside;
        Path main = mainCheckout();
        return main == null ? beside : main.resolveSibling("docs");
    }
    /** The main checkout when this one is a linked worktree, found through git's common directory; null when git cannot say. */
    private static Path mainCheckout() {
        try {
            Process git = new ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir").directory(root().toFile()).redirectErrorStream(true).start();
            String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!git.waitFor(10, TimeUnit.SECONDS) || git.exitValue() != 0) return null;
            Path common = Path.of(output);
            return common.getFileName() != null && common.getFileName().toString().equals(".git") ? common.getParent() : null;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
    static Path packs() { return root().resolve("examples/packs"); }
    static List<Path> packDirectories() throws IOException {
        try (Stream<Path> children = Files.list(packs())) { return children.filter(Files::isDirectory).sorted().toList(); }
    }
    static List<Path> jsonUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.walk(directory)) { return files.filter(file -> file.toString().endsWith(".json")).sorted().toList(); }
    }
}
