package art.arcane.animus.core.quest;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Player quest records in {@code state/quests/<uuid>.json}, all read at start. A change marks the player and a writer
 * thread saves every marked record after a short delay, each file replaced atomically; closing saves what is left. A
 * damaged file is set aside as {@code <name>.bad-<time>} with a warning and that player starts without quests.
 */
public final class QuestStore implements AutoCloseable {
    private final Path directory;
    private final long flushMillis;
    private final Consumer<String> warn;
    private final Map<UUID, QuestRecord> records = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final ScheduledExecutorService writer;
    private final List<String> problems = new ArrayList<>();
    private boolean scheduled, closed;

    public QuestStore(Path directory, long flushMillis, Consumer<String> warn) throws IOException {
        this.directory = Objects.requireNonNull(directory);
        this.flushMillis = Math.max(0, flushMillis);
        this.warn = Objects.requireNonNull(warn);
        Files.createDirectories(directory);
        load();
        ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1, (Runnable task) -> {
            Thread worker = new Thread(task, "Animus quest state");
            worker.setDaemon(true);
            return worker;
        });
        thread.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        writer = thread;
    }

    /** The player's record, made empty on first use. */
    public QuestRecord record(UUID player) { return records.computeIfAbsent(player, (UUID ignored) -> new QuestRecord()); }

    /** Players with any record, for admin listings. */
    public Set<UUID> players() { return Set.copyOf(records.keySet()); }

    /** Why some saved records were set aside at load; empty when everything loaded. */
    public synchronized List<String> problems() { return List.copyOf(problems); }

    /** Queues the player's record for the next write. */
    public void changed(UUID player) {
        synchronized (this) {
            if (closed) return;
            dirty.add(player);
            if (scheduled) return;
            scheduled = true;
        }
        writer.schedule(() -> {
            try { flush(); }
            catch (IOException | RuntimeException failure) { warn.accept("Quest state write failed; it is retried with the next change: " + failure.getMessage()); }
        }, flushMillis, TimeUnit.MILLISECONDS);
    }

    /** Writes every queued record now. */
    public void flush() throws IOException {
        List<UUID> players;
        synchronized (this) {
            scheduled = false;
            players = List.copyOf(dirty);
            dirty.clear();
        }
        IOException failed = null;
        for (UUID player : players) {
            QuestRecord record = records.get(player);
            Path file = directory.resolve(player + ".json");
            try {
                if (record == null || record.empty()) Files.deleteIfExists(file);
                else DocumentStore.atomic(file, JsonDocuments.write(record.write()));
            } catch (IOException failure) {
                synchronized (this) { dirty.add(player); }
                failed = failure;
            }
        }
        if (failed != null) throw failed;
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) warn.accept("Quest state writer did not stop in time");
            flush();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            warn.accept("Quest state could not be saved at shutdown: " + failure.getMessage());
        }
    }

    private void load() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter((Path path) -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.json$", "");
                UUID player;
                try { player = UUID.fromString(name); }
                catch (IllegalArgumentException notPlayer) { continue; }
                try { records.put(player, QuestRecord.read(JsonDocuments.object(JsonDocuments.read(file)))); }
                catch (IOException | IllegalArgumentException damaged) { quarantine(file, damaged); }
            }
        }
    }

    private void quarantine(Path file, Exception cause) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
        String problem;
        try {
            Files.move(file, bad);
            problem = file.getFileName() + " was damaged (" + cause.getMessage() + "); it was saved as " + bad.getFileName() + " and that player starts without quests";
        } catch (IOException failure) {
            problem = file.getFileName() + " was damaged (" + cause.getMessage() + ") and could not be set aside: " + failure.getMessage();
        }
        problems.add(problem);
        warn.accept("Quests: " + problem);
    }
}
