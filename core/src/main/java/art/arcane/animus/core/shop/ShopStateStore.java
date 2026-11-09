package art.arcane.animus.core.shop;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Durable shop counters. Changes are appended to {@code ledger.journal} after a short debounce on a background
 * thread and forced to disk; the journal is folded into {@code ledger.json} when it grows and at shutdown. A torn
 * last journal line from a crash is ignored with a warning. A damaged {@code ledger.json} or journal line never stops the
 * plugin: the file is copied to {@code <name>.bad-<time>}, the counters start without it, and a warning names the file. Imported counts in {@code imports/<id>.json} are merged
 * once, and the snapshot remembers which imports it holds.
 */
public final class ShopStateStore implements AutoCloseable {
    private static final long COMPACT_BYTES = 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Path directory, logs;
    /** Guarded by this store; a hotload of {@code shops.flushMillis} replaces it for the next scheduled write. */
    private long flushMillis;
    private final Consumer<String> warn;
    private final ShopLedger ledger;
    private final List<String> pending = new ArrayList<>();
    private final List<String[]> pendingLogs = new ArrayList<>();
    private final Set<String> imports = new HashSet<>();
    private final ScheduledExecutorService writer;
    private boolean scheduled, closed;
    /** Why a damaged ledger file was set aside at load, or empty. */
    private String problem = "";

    public ShopStateStore(Path directory, Path logs, long flushMillis, Consumer<String> warn) throws IOException {
        this.directory = Objects.requireNonNull(directory);
        this.logs = Objects.requireNonNull(logs);
        this.flushMillis = Math.max(0, flushMillis);
        this.warn = Objects.requireNonNull(warn);
        this.ledger = new ShopLedger(this::journal);
        Files.createDirectories(directory);
        load();
        writer = Executors.newSingleThreadScheduledExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "Animus shop state");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ShopLedger ledger() { return ledger; }

    /** Why shop counters started from less than was saved: a damaged ledger file set aside at load. Empty when all loaded. */
    public String problem() { return problem; }

    /** The write delay for changes queued from now on; a write already scheduled keeps its time. */
    public synchronized void flushMillis(long millis) { flushMillis = Math.max(0, millis); }

    /** Appends a transaction line to the shop's daily log, after the same debounce. */
    public void log(String shop, String line) {
        String text = "[" + LocalTime.now().format(TIME) + "] " + line;
        synchronized (this) {
            if (closed) return;
            pendingLogs.add(new String[]{shop, text});
            schedule();
        }
    }

    /** Writes everything queued now. */
    public void flush() throws IOException {
        List<String> lines;
        List<String[]> logLines;
        synchronized (this) {
            scheduled = false;
            lines = List.copyOf(pending);
            pending.clear();
            logLines = List.copyOf(pendingLogs);
            pendingLogs.clear();
        }
        Path journal = directory.resolve("ledger.journal");
        if (!lines.isEmpty()) append(journal, String.join("\n", lines) + "\n");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (String[] entry : logLines) append(logs.resolve(entry[0].replace(':', '/')).resolve(today + ".log"), entry[1] + "\n");
        if (Files.exists(journal) && Files.size(journal) > COMPACT_BYTES) compact();
    }

    /** Folds the journal into the snapshot. */
    public synchronized void compact() throws IOException {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", 1);
        JsonArray applied = new JsonArray();
        imports.stream().sorted().forEach(applied::add);
        snapshot.add("imports", applied);
        snapshot.add("counters", com.google.gson.JsonParser.parseString(ShopLedger.Change.writeAll(ledger.snapshot())));
        DocumentStore.atomic(directory.resolve("ledger.json"), JsonDocuments.write(snapshot));
        Files.deleteIfExists(directory.resolve("ledger.journal"));
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) warn.accept("Shop state writer did not stop in time");
            flush();
            compact();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            warn.accept("Shop state could not be saved at shutdown: " + failure.getMessage());
        }
    }

    /**
     * Sets a damaged ledger file aside as {@code <name>.bad-<time>} and warns at WARN level. The snapshot is moved, so the
     * next compaction writes a clean one; the journal is copied and its readable lines still apply.
     */
    private void quarantine(Path file, boolean move, Throwable cause) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        try {
            if (move) Files.move(file, bad); else Files.copy(file, bad);
            problem = "shop counters in " + file.getFileName() + " were damaged (" + reason + "); the file was saved as " + bad.getFileName()
                    + (move ? " and shop limits and stock counts started empty" : " and its unreadable lines were skipped");
        } catch (IOException failure) {
            problem = "shop counters in " + file.getFileName() + " were damaged (" + reason + ") and could not be set aside: " + failure.getMessage()
                    + "; shop limits and stock counts started without them";
        }
        System.getLogger("Animus").log(System.Logger.Level.WARNING, "Shops: " + problem);
    }

    private void journal(ShopLedger.Change change) {
        synchronized (this) {
            if (closed) return;
            pending.add(change.write());
            schedule();
        }
    }

    private void schedule() {
        if (scheduled || writer == null) return;
        scheduled = true;
        writer.schedule(() -> {
            try { flush(); }
            catch (IOException | RuntimeException failure) { warn.accept("Shop state write failed; it is retried with the next change: " + failure.getMessage()); }
        }, flushMillis, TimeUnit.MILLISECONDS);
    }

    private static void append(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(false);
        }
    }

    private void load() throws IOException {
        Path snapshot = directory.resolve("ledger.json");
        if (Files.exists(snapshot)) {
            Set<String> applied = new HashSet<>();
            List<ShopLedger.Change> changes;
            try {
                JsonObject json = JsonDocuments.object(JsonDocuments.read(snapshot));
                for (JsonElement id : json.getAsJsonArray("imports")) applied.add(id.getAsString());
                changes = ShopLedger.Change.parseAll(json.getAsJsonArray("counters").toString());
            } catch (IOException | RuntimeException invalid) {
                changes = List.of();
                applied.clear();
                quarantine(snapshot, true, invalid);
            }
            imports.addAll(applied);
            for (ShopLedger.Change change : changes) ledger.apply(change);
        }
        Path journal = directory.resolve("ledger.journal");
        if (Files.exists(journal)) {
            List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                try { ledger.apply(ShopLedger.Change.parse(line)); }
                catch (RuntimeException torn) {
                    if (index == lines.size() - 1) warn.accept("Ignored an incomplete last line in " + journal);
                    else if (problem.isEmpty()) quarantine(journal, false, new IOException("line " + (index + 1) + ": " + torn.getMessage(), torn));
                }
            }
        }
        Path folder = directory.resolve("imports");
        if (!Files.isDirectory(folder)) return;
        boolean merged = false;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter((Path path) -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String id = file.getFileName().toString().replaceFirst("\\.json$", "");
                if (imports.contains(id)) continue;
                try {
                    JsonObject json = JsonDocuments.object(JsonDocuments.read(file));
                    for (JsonElement element : json.getAsJsonArray("counts")) {
                        JsonObject count = element.getAsJsonObject();
                        ledger.importPlayer(count.get("shop").getAsString(), count.get("entry").getAsString(),
                                UUID.fromString(count.get("player").getAsString()), count.get("used").getAsLong());
                    }
                    imports.add(id);
                    merged = true;
                } catch (IOException | RuntimeException invalid) { warn.accept("Skipped imported shop counts " + file + ": " + invalid.getMessage()); }
            }
        }
        if (merged) {
            pending.clear();
            compact();
        }
    }
}
