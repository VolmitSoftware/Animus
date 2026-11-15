package art.arcane.animus.core.combat;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * How much each combat NPC has fought, counted as Sentinel's {@code stats_*} fields count it and kept in
 * {@code state/combat/statistics.json}. An imported NPC's Sentinel totals stay in its {@code historicalStatistics} and
 * this file adds what happened since. Any engine thread may count; the file is written whole on the file lane.
 */
public final class CombatStatistics {
    /** One counter, with the Sentinel field it continues. */
    public enum Counter {
        TICKS_SPAWNED("stats_ticksSpawned", "Time spawned"), TIMES_SPAWNED("stats_timesSpawned", "Spawns"), ARROWS_FIRED("stats_arrowsFired", "Arrows"),
        POTIONS_THROWN("stats_potionsThrow", "Potions"), FIREBALLS_FIRED("stats_fireballsFired", "Fireballs"), SNOWBALLS_THROWN("stats_snowballsThrown", "Snowballs"),
        EGGS_THROWN("stats_eggsThrown", "Eggs"), SKULLS_THROWN("stats_skullsThrown", "Skulls"), LLAMA_SPIT_SHOT("stats_llamaSpitShot", "Spit"),
        PUNCHES("stats_punches", "Punches"), ATTACK_ATTEMPTS("stats_attackAttempts", "Attacks"), DAMAGE_TAKEN("stats_damageTaken", "Damage taken"),
        DAMAGE_GIVEN("stats_damageGiven", "Damage given"), KILLS("kills", "Kills"), DEATHS("deaths", "Deaths");
        private final String key, label;
        Counter(String key, String label) { this.key = key; this.label = label; }
        /** The {@code historicalStatistics} key, Sentinel's own where it has one. */
        public String key() { return key; }
        public String label() { return label; }
    }
    /** Where engines count. */
    public interface Sink {
        Sink NONE = (UUID npc, Counter counter, double amount) -> {};
        void add(UUID npc, Counter counter, double amount);
    }

    private final Path file;
    private final Consumer<String> warn;
    private final Map<UUID, EnumMap<Counter, Double>> counts = new HashMap<>();
    private boolean dirty;

    public CombatStatistics(Path file, Consumer<String> warn) {
        this.file = Objects.requireNonNull(file);
        this.warn = Objects.requireNonNull(warn);
        load();
    }

    public synchronized void add(UUID npc, Counter counter, double amount) {
        if (!(amount > 0) || !Double.isFinite(amount)) return;
        counts.computeIfAbsent(npc, (UUID key) -> new EnumMap<>(Counter.class)).merge(counter, amount, Double::sum);
        dirty = true;
    }
    public Sink sink() { return this::add; }

    /** What this server counted for the NPC, by counter. */
    public synchronized Map<Counter, Double> counted(UUID npc) {
        EnumMap<Counter, Double> found = counts.get(npc);
        return found == null ? Map.of() : Map.copyOf(found);
    }

    /**
     * The NPC's totals: its imported {@code historicalStatistics} plus what this server counted, in counter order, with
     * time in seconds. Counters that are zero are left out.
     */
    public Map<Counter, Double> totals(UUID npc, JsonObject historical) {
        Map<Counter, Double> counted = counted(npc);
        Map<Counter, Double> totals = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) {
            double total = counted.getOrDefault(counter, 0.0);
            JsonElement imported = historical.get(counter.key());
            if (imported != null && imported.isJsonPrimitive() && imported.getAsJsonPrimitive().isNumber() && Double.isFinite(imported.getAsDouble()))
                total += Math.max(0, imported.getAsDouble());
            if (total > 0) totals.put(counter, total);
        }
        return totals;
    }

    /** One short line for {@code inspect}: each nonzero total, time in hours or minutes. */
    public static String describe(Map<Counter, Double> totals) {
        if (totals.isEmpty()) return "no fights yet";
        StringBuilder line = new StringBuilder();
        for (Map.Entry<Counter, Double> entry : totals.entrySet()) {
            if (!line.isEmpty()) line.append(", ");
            line.append(entry.getKey().label().toLowerCase(Locale.ROOT)).append(' ').append(value(entry.getKey(), entry.getValue()));
        }
        return line.toString();
    }
    private static String value(Counter counter, double amount) {
        if (counter == Counter.TICKS_SPAWNED) {
            double minutes = amount / 1200;
            return minutes >= 120 ? String.format(Locale.ROOT, "%.1fh", minutes / 60) : String.format(Locale.ROOT, "%.0fm", minutes);
        }
        return amount == Math.rint(amount) ? Long.toString((long) amount) : String.format(Locale.ROOT, "%.1f", amount);
    }

    /** Writes the counts on the file lane when any changed since the last write. */
    public void flush(Executor files) {
        String document;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            document = serialize();
        }
        try { files.execute(() -> write(document)); }
        catch (RejectedExecutionException busy) { synchronized (this) { dirty = true; } }
    }
    /** Writes the counts now, on the caller's thread; for shutdown. */
    public void flushNow() {
        String document;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            document = serialize();
        }
        write(document);
    }

    private String serialize() {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, EnumMap<Counter, Double>> npc : counts.entrySet()) {
            JsonObject values = new JsonObject();
            for (Map.Entry<Counter, Double> counter : npc.getValue().entrySet()) values.addProperty(counter.getKey().key(), counter.getValue());
            root.add(npc.getKey().toString(), values);
        }
        return JsonDocuments.write(root);
    }
    private void write(String document) {
        try {
            Files.createDirectories(file.getParent());
            Path next = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(next, document, StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { warn.accept("Combat statistics could not be saved to " + file + ": " + failure.getMessage()); }
    }
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> npc : root.entrySet()) {
                UUID id = UUID.fromString(npc.getKey());
                EnumMap<Counter, Double> values = new EnumMap<>(Counter.class);
                for (Counter counter : Counter.values()) {
                    JsonElement value = npc.getValue().getAsJsonObject().get(counter.key());
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) values.put(counter, value.getAsDouble());
                }
                if (!values.isEmpty()) counts.put(id, values);
            }
        } catch (IOException | RuntimeException failure) {
            warn.accept("Combat statistics in " + file + " could not be read and start again from zero: " + failure.getMessage());
        }
    }
}
