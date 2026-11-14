package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Each player's menu mode, saved as {@code state/toolkit/<uuid>.json}: {@code {"schemaVersion":1,"mode":"simple"}}.
 * Reads and writes run on the file lane; {@link #mode(UUID)} answers from memory and says Simple until the player's
 * file is read. A missing, unreadable or newer file reads as Simple and is left as it is until the player picks a mode.
 * Safe to call from any thread.
 */
public final class ToolkitPrefs {
    public static final String ROOT = "state/toolkit/";
    static final int SCHEMA = 1;
    private static final System.Logger LOG = System.getLogger("Animus");

    /** One online player's mode, null while the read is queued. {@link #forget} drops the entry, so a late read lands nowhere. */
    private static final class Entry { ToolkitMode mode; }

    private final Supplier<DocumentStore> store;
    private final Executor files;
    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();

    public ToolkitPrefs(Supplier<DocumentStore> store, Executor files) {
        this.store = Objects.requireNonNull(store);
        this.files = Objects.requireNonNull(files);
    }

    public static String path(UUID player) { return ROOT + player + ".json"; }

    /** The cached mode; Simple when the player's file is not read yet. */
    public ToolkitMode mode(UUID player) {
        Entry entry = players.get(player);
        if (entry == null) return ToolkitMode.SIMPLE;
        synchronized (entry) { return entry.mode == null ? ToolkitMode.SIMPLE : entry.mode; }
    }

    /** Whether the player's mode was read or picked since they joined. */
    public boolean known(UUID player) {
        Entry entry = players.get(player);
        return entry != null && settled(entry);
    }

    /** Switches the mode at once and saves it on the file lane. Picking the mode the player already has writes nothing. */
    public void mode(UUID player, ToolkitMode mode) {
        Objects.requireNonNull(mode);
        Entry entry = players.computeIfAbsent(player, (UUID ignored) -> new Entry());
        synchronized (entry) {
            if (entry.mode == mode) return;
            entry.mode = mode;
            // Queued under the lock so two quick switches reach the serial lane in the order they were made.
            try { files.execute(() -> save(player, mode)); }
            catch (RejectedExecutionException full) { LOG.log(System.Logger.Level.WARNING, "Menu mode for " + player + " not saved: the file lane is full"); }
        }
    }

    /** Reads the player's saved mode on the file lane. */
    public void load(UUID player) { load(player, () -> {}); }

    /**
     * Reads the player's saved mode on the file lane, then runs {@code then} there. When the mode is already known, or
     * the lane is full, {@code then} runs at once and nothing is read.
     */
    public void load(UUID player, Runnable then) {
        Entry entry = players.computeIfAbsent(player, (UUID ignored) -> new Entry());
        if (settled(entry)) { then.run(); return; }
        try {
            files.execute(() -> {
                if (players.get(player) == entry && !settled(entry)) {
                    ToolkitMode saved = read(player);
                    synchronized (entry) { if (entry.mode == null) entry.mode = saved; }
                }
                then.run();
            });
        } catch (RejectedExecutionException full) { then.run(); }
    }

    /** The player left; their next join reads the file again. */
    public void forget(UUID player) { players.remove(player); }

    private static boolean settled(Entry entry) {
        synchronized (entry) { return entry.mode != null; }
    }

    private ToolkitMode read(UUID player) {
        String path = path(player);
        try {
            Path file = store.get().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return ToolkitMode.SIMPLE;
            JsonObject saved = JsonDocuments.object(JsonDocuments.read(file));
            JsonElement version = saved.get("schemaVersion"), mode = saved.get("mode");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() != SCHEMA)
                throw new IOException("schemaVersion is not " + SCHEMA);
            if (mode == null || !mode.isJsonPrimitive()) throw new IOException("mode is missing");
            return ToolkitMode.parse(mode.getAsString()).orElseThrow(() -> new IOException("mode is not simple or advanced"));
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(System.Logger.Level.WARNING, "Menu mode in " + path + " is unreadable, using Simple: " + unreadable.getMessage());
            return ToolkitMode.SIMPLE;
        }
    }

    private void save(UUID player, ToolkitMode mode) {
        JsonObject saved = new JsonObject();
        saved.addProperty("schemaVersion", SCHEMA);
        saved.addProperty("mode", mode.word());
        try { DocumentStore.atomic(store.get().resolve(path(player)), JsonDocuments.write(saved)); }
        catch (IOException | RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Menu mode for " + player + " not saved: " + failure.getMessage()); }
    }
}
