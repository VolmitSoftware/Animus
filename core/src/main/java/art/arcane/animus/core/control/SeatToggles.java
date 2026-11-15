package art.arcane.animus.core.control;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Players who turned sitting on NPCs off with {@code /animus seat toggle}, as GSit's {@code /gsit playertoggle} does for
 * sitting on players. Kept in {@code state/seats/toggles.json}; every player starts with sitting on. Any thread.
 */
public final class SeatToggles {
    private final Path file;
    private final Executor files;
    private final Consumer<String> log;
    private final Set<UUID> off = new HashSet<>();

    public SeatToggles(Path file, Executor files, Consumer<String> log) {
        this.file = Objects.requireNonNull(file); this.files = Objects.requireNonNull(files); this.log = Objects.requireNonNull(log);
        load();
    }

    public synchronized boolean allows(UUID player) { return !off.contains(player); }

    /** Sets the player's choice, or flips it when {@code on} is null; returns whether sitting is on now. */
    public boolean set(UUID player, Boolean on) {
        boolean now;
        synchronized (this) {
            now = on != null ? on : off.contains(player);
            if (now) off.remove(player); else off.add(player);
        }
        save();
        return now;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject document = JsonDocuments.object(JsonDocuments.read(file));
            JsonElement players = document.get("off");
            if (players != null && players.isJsonArray()) for (JsonElement player : players.getAsJsonArray()) off.add(UUID.fromString(player.getAsString()));
        } catch (IOException | RuntimeException failure) {
            log.accept("Seat toggles could not be read from " + file + ": " + failure.getMessage());
        }
    }

    private void save() {
        String text;
        synchronized (this) {
            JsonObject document = new JsonObject();
            document.addProperty("schemaVersion", 1);
            JsonArray players = new JsonArray();
            off.stream().sorted().forEach((UUID player) -> players.add(player.toString()));
            document.add("off", players);
            text = JsonDocuments.write(document);
        }
        try {
            files.execute(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    DocumentStore.atomic(file, text);
                } catch (IOException failure) { log.accept("Seat toggles could not be saved: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { log.accept("Seat toggles could not be saved: the file queue is full"); }
    }
}
