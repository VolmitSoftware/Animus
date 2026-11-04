package art.arcane.animus.core.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounds directory work before collecting or sorting documents, including unrelated entries. */
public final class JsonDocumentTree {
    private JsonDocumentTree() {}
    public static List<Path> files(Path directory) throws IOException { return files(directory, 250_000, 100_000, 8); }

    static List<Path> files(Path directory, int entryLimit, int documentLimit, int depthLimit) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory)) throw new IOException("Document symlinks are not allowed: " + directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Document root is not a directory: " + directory);
        List<Path> documents = new ArrayList<>();
        int entries = 0;
        try (var paths = Files.walk(directory, depthLimit + 1)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++entries > entryLimit) throw new IOException("Document directory entry limit exceeded: " + entryLimit);
                if (Files.isSymbolicLink(path)) throw new IOException("Document symlinks are not allowed: " + path);
                if (!path.equals(directory) && directory.relativize(path).getNameCount() > depthLimit)
                    throw new IOException("Document directory depth limit exceeded: " + depthLimit);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toString().endsWith(".json")) continue;
                if (documents.size() >= documentLimit) throw new IOException("JSON document limit exceeded: " + documentLimit);
                documents.add(path);
            }
        } catch (UncheckedIOException error) { throw error.getCause(); }
        documents.sort(Comparator.naturalOrder());
        return List.copyOf(documents);
    }
}
