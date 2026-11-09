package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * Reads a YAML save another plugin may be writing. Citizens rewrites saves.yml in place, so a preview taken during an
 * autosave can see half a file. A read counts only when the file stayed the same while it was read, ends with a line
 * break and parses; otherwise it waits once and reads again.
 */
final class StableSource {
    /** The text read and its hash, the same hash a preview records as {@code sourceSha256}. */
    record Read(String text, String sha256) {}

    @FunctionalInterface interface Pause { void await() throws IOException; }

    /** How long a save in progress gets before the second read. */
    static final Pause WAIT = () -> {
        try { Thread.sleep(500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while waiting for a save to finish", interrupted); }
    };

    private StableSource() {}

    static Read read(Path file, String label) throws IOException { return read(file, label, WAIT); }

    static Read read(Path file, String label, Pause pause) throws IOException {
        Optional<String> first = attempt(file);
        if (first.isPresent()) return new Read(first.get(), JsonDocuments.hash(first.get()));
        pause.await();
        Optional<String> second = attempt(file);
        if (second.isPresent()) return new Read(second.get(), JsonDocuments.hash(second.get()));
        throw new IOException(label + " looks incomplete (it changed while being read, does not parse or does not end with a line break); if a plugin is saving it, wait and run preview again");
    }

    private static Optional<String> attempt(Path file) throws IOException {
        FileTime modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
        long size = Files.size(file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (Files.size(file) != size || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).equals(modified)) return Optional.empty();
        if (!text.endsWith("\n")) return Optional.empty();
        try { CitizensImporter.parseYaml(text); }
        catch (IOException | RuntimeException unparsed) { return Optional.empty(); }
        return Optional.of(text);
    }
}
