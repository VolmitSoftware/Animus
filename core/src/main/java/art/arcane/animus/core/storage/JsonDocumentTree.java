package art.arcane.animus.core.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounds directory work before collecting or sorting documents, including unrelated entries. */
public final class JsonDocumentTree {
    private JsonDocumentTree() {}
    public static List<Path> files(Path directory) throws IOException { return files(directory, 250_000, 100_000, 8); }
    /** Generated trees mirror behavior identifiers, which nest deeper than definition directories. */
    public static List<Path> files(Path directory, int depthLimit) throws IOException { return files(directory, 250_000, 100_000, depthLimit); }

    /**
     * As {@link #files(Path, int)}, but a symlink or a too-deep entry refuses the whole tree. Imports use it: they install
     * documents next to the existing ones and must see all of them.
     */
    public static List<Path> strictFiles(Path directory, int depthLimit) throws IOException {
        try { return files(directory, 250_000, 100_000, depthLimit, (String skipped) -> { throw new UncheckedIOException(new IOException(skipped)); }); }
        catch (UncheckedIOException refused) { throw refused.getCause(); }
    }

    static List<Path> files(Path directory, int entryLimit, int documentLimit, int depthLimit) throws IOException {
        return files(directory, entryLimit, documentLimit, depthLimit,
                (String skipped) -> System.getLogger("Animus").log(System.Logger.Level.WARNING, skipped));
    }

    /**
     * The JSON files under {@code directory}. A symlink or anything nested deeper than {@code depthLimit} inside it is
     * skipped and named to {@code skipped}, so one odd folder never stops a start, a reload or a validate. The entry and
     * document limits still refuse the whole tree, and the root itself must be a real directory.
     */
    static List<Path> files(Path directory, int entryLimit, int documentLimit, int depthLimit, java.util.function.Consumer<String> skipped) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory)) throw new IOException("Document symlinks are not allowed: " + directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Document root is not a directory: " + directory);
        List<Path> documents = new ArrayList<>();
        int[] entries = {0};
        Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), depthLimit + 1, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                count();
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                count();
                if (attributes.isSymbolicLink()) { skipped.accept("Skipped " + path + ": symbolic links are not read; replace it with the real file or folder"); return FileVisitResult.CONTINUE; }
                if (directory.relativize(path).getNameCount() > depthLimit) {
                    skipped.accept("Skipped " + path + ": nested deeper than " + depthLimit + " folders; move it higher up");
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile() || !path.toString().endsWith(".json")) return FileVisitResult.CONTINUE;
                if (documents.size() >= documentLimit) throw new IOException("JSON document limit exceeded: " + documentLimit);
                documents.add(path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException { throw failure; }

            private void count() throws IOException {
                if (++entries[0] > entryLimit) throw new IOException("Document directory entry limit exceeded: " + entryLimit);
            }
        });
        documents.sort(Comparator.naturalOrder());
        return List.copyOf(documents);
    }
}
