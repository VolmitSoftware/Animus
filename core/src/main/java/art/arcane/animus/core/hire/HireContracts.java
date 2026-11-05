package art.arcane.animus.core.hire;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Who hired which NPC and until when, kept in {@code state/hire/contracts.json}. Each change is written whole on the
 * file lane; a restart reads the file back, so a paid hire survives it. One contract per NPC.
 */
public final class HireContracts {
    public record Contract(UUID npc, UUID player, String playerName, Instant expires) {
        public Contract { Objects.requireNonNull(npc); Objects.requireNonNull(player); Objects.requireNonNull(playerName); Objects.requireNonNull(expires); }
        public boolean active(Instant now) { return now.isBefore(expires); }
    }

    private final Path file;
    private final Executor files;
    private final Consumer<String> log;
    private final Map<UUID, Contract> contracts = new HashMap<>();

    public HireContracts(Path file, Executor files, Consumer<String> log) {
        this.file = Objects.requireNonNull(file);
        this.files = Objects.requireNonNull(files);
        this.log = Objects.requireNonNull(log);
        load();
    }

    public synchronized Optional<Contract> of(UUID npc) { return Optional.ofNullable(contracts.get(npc)); }
    public synchronized List<Contract> all() { return List.copyOf(contracts.values()); }

    public void put(Contract contract) {
        synchronized (this) { contracts.put(contract.npc(), contract); }
        save();
    }

    /** Ends the NPC's contract when it still is the given one; false when another contract replaced it. */
    public boolean end(Contract contract) {
        synchronized (this) { if (!contracts.remove(contract.npc(), contract)) return false; }
        save();
        return true;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject document = JsonDocuments.object(JsonDocuments.read(file));
            JsonArray entries = document.has("contracts") && document.get("contracts").isJsonArray() ? document.getAsJsonArray("contracts") : new JsonArray();
            for (JsonElement entry : entries) {
                JsonObject value = entry.getAsJsonObject();
                Contract contract = new Contract(UUID.fromString(value.get("npc").getAsString()), UUID.fromString(value.get("player").getAsString()),
                        value.get("playerName").getAsString(), Instant.parse(value.get("expires").getAsString()));
                contracts.put(contract.npc(), contract);
            }
        } catch (IOException | RuntimeException failure) {
            log.accept("Hire contracts could not be read from " + file + ": " + failure.getMessage());
        }
    }

    private void save() {
        String text;
        synchronized (this) {
            JsonObject document = new JsonObject();
            document.addProperty("schemaVersion", 1);
            JsonArray entries = new JsonArray();
            contracts.values().stream().sorted((Contract left, Contract right) -> left.npc().compareTo(right.npc())).forEach((Contract contract) -> {
                JsonObject value = new JsonObject();
                value.addProperty("npc", contract.npc().toString());
                value.addProperty("player", contract.player().toString());
                value.addProperty("playerName", contract.playerName());
                value.addProperty("expires", contract.expires().toString());
                entries.add(value);
            });
            document.add("contracts", entries);
            text = JsonDocuments.write(document);
        }
        try {
            files.execute(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    DocumentStore.atomic(file, text);
                } catch (IOException failure) { log.accept("Hire contracts could not be saved: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { log.accept("Hire contracts could not be saved: the file queue is full"); }
    }

    /** For status output. */
    public synchronized List<String> describe(Instant now) {
        List<String> lines = new ArrayList<>();
        for (Contract contract : contracts.values())
            lines.add(contract.npc() + " hired by " + contract.playerName() + (contract.active(now) ? " until " + contract.expires() : " (expired)"));
        return lines;
    }
}
