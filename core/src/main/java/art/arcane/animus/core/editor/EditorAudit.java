package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only audit of editor pairing, revocation, validation, preview, apply, undo and import, one JSON object per line in
 * {@code state/editor/audit/<yyyy-mm-dd>.jsonl} (UTC). Files older than {@code auditDays} are removed at startup.
 */
public final class EditorAudit {
    public static final String DIRECTORY = "state/editor/audit";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final DocumentStore store;
    private final Clock clock;

    /** One audited event. {@code documents} lists {@code kind:id} names. */
    public record Entry(String event, Optional<String> tokenId, Optional<UUID> admin, String address, String path, List<String> documents, String result) {
        public Entry { documents = List.copyOf(documents); }
    }

    public EditorAudit(DocumentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void record(Entry entry) throws IOException {
        JsonObject line = new JsonObject();
        line.addProperty("at", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString());
        line.addProperty("event", entry.event());
        entry.tokenId().ifPresent((String id) -> line.addProperty("tokenId", id));
        entry.admin().ifPresent((UUID admin) -> line.addProperty("admin", admin.toString()));
        line.addProperty("address", entry.address());
        line.addProperty("path", entry.path());
        JsonArray documents = new JsonArray();
        entry.documents().stream().limit(512).forEach(documents::add);
        line.add("documents", documents);
        line.addProperty("result", entry.result());
        Path file = store.resolve(DIRECTORY + "/" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC) + ".jsonl");
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            channel.write(StandardCharsets.UTF_8.encode(GSON.toJson(line) + "\n"));
            channel.force(false);
        }
    }

    /** Removes day files older than {@code days}. Returns how many were removed. */
    public synchronized int prune(int days) throws IOException {
        Path directory = store.resolve(DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        LocalDate oldest = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(days);
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl") || Files.isSymbolicLink(file)) continue;
                if (LocalDate.parse(name.substring(0, 10)).isBefore(oldest) && Files.deleteIfExists(file)) removed++;
            }
        }
        return removed;
    }
}
